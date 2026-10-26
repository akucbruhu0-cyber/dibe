//! The thread that moves captured audio from the ring to disk.
//!
//! The audio callback only pushes samples into a lock-free ring (see `capture`). This thread
//! empties it every ~20 ms into a 32-bit float WAV and checkpoints the header every
//! `checkpoint_every`, so the file on disk is readable up to the last checkpoint even if the app
//! dies without finalizing it.

use eyre::{bail, eyre, Result};
use std::fs::File;
use std::io::BufWriter;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// How often a recording's WAV header is brought up to date on disk.
pub const CHECKPOINT_EVERY: Duration = Duration::from_secs(5);
/// How often the writer empties the ring.
const DRAIN_EVERY: Duration = Duration::from_millis(20);
/// A WAV stores its sizes in 32 bits; stop before the data chunk of f32 samples outgrows them.
// ponytail: ~3.1 h of 48 kHz stereo; RF64 or rolling files if recordings get longer.
const MAX_SAMPLES: u64 = (u32::MAX as u64 - 1024) / 4;

type Wav = hound::WavWriter<BufWriter<File>>;

pub struct WriterReport {
    /// Samples in the finished file, all channels counted.
    pub samples: u64,
    /// Samples the audio callback dropped because the ring was full.
    pub dropped: u64,
}

pub struct WriterHandle {
    /// Incremented by the audio callback for every sample that did not fit in the ring.
    pub dropped: Arc<AtomicU64>,
    /// Never sent on: dropping it is what tells the thread to stop.
    stop: mpsc::Sender<()>,
    thread: JoinHandle<Result<u64>>,
}

/// Creates the WAV at `path` and starts the thread that fills it from `rx`. A handle dropped
/// without `finish` still stops the thread, which then finalizes the file on its own.
pub fn spawn_writer(
    path: PathBuf,
    spec: hound::WavSpec,
    rx: rtrb::Consumer<f32>,
    checkpoint_every: Duration,
) -> Result<WriterHandle> {
    let wav = hound::WavWriter::create(&path, spec)?;
    let (stop, stopped) = mpsc::channel();
    let thread = std::thread::Builder::new()
        .name("vibe-wav-writer".into())
        .spawn(move || write_until_stopped(wav, rx, &stopped, checkpoint_every))?;
    Ok(WriterHandle {
        dropped: Arc::default(),
        stop,
        thread,
    })
}

impl WriterHandle {
    /// Stops the thread and waits for it to write what is left in the ring and finalize the WAV.
    /// Stop the stream first: samples pushed after this are never written.
    pub fn finish(self) -> Result<WriterReport> {
        drop(self.stop);
        let samples = self.thread.join().map_err(|_| eyre!("the WAV writer thread panicked"))??;
        Ok(WriterReport {
            samples,
            dropped: self.dropped.load(Ordering::Relaxed),
        })
    }
}

fn write_until_stopped(
    mut wav: Wav,
    mut rx: rtrb::Consumer<f32>,
    stopped: &mpsc::Receiver<()>,
    checkpoint_every: Duration,
) -> Result<u64> {
    let mut last_checkpoint = Instant::now();
    loop {
        let stopping = !matches!(stopped.recv_timeout(DRAIN_EVERY), Err(RecvTimeoutError::Timeout));
        drain(&mut rx, &mut wav)?;
        if stopping {
            break;
        }
        if last_checkpoint.elapsed() >= checkpoint_every {
            // hound's flush rewrites the header's lengths, so the file is valid up to here.
            wav.flush()?;
            last_checkpoint = Instant::now();
        }
    }
    let samples = u64::from(wav.len());
    wav.finalize()?;
    Ok(samples)
}

/// Writes everything the callback has queued so far.
fn drain(rx: &mut rtrb::Consumer<f32>, wav: &mut Wav) -> Result<()> {
    // Never asks for more than is queued, so this cannot fail.
    let Ok(chunk) = rx.read_chunk(rx.slots()) else {
        return Ok(());
    };
    if u64::from(wav.len()) + chunk.len() as u64 > MAX_SAMPLES {
        bail!("the recording reached the 4 GiB size limit of a WAV file");
    }
    let (first, second) = chunk.as_slices();
    for &sample in first.iter().chain(second) {
        wav.write_sample(sample)?;
    }
    chunk.commit_all();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mono_spec() -> hound::WavSpec {
        hound::WavSpec {
            channels: 1,
            sample_rate: 48_000,
            bits_per_sample: 32,
            sample_format: hound::SampleFormat::Float,
        }
    }

    fn temp_wav(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("vibe-writer-{name}-{}.wav", crate::ffmpeg::random_string(8)))
    }

    fn push(tx: &mut rtrb::Producer<f32>, count: usize) {
        let chunk = tx.write_chunk_uninit(count).expect("ring has room");
        chunk.fill_from_iter(std::iter::repeat(0.25));
    }

    #[test]
    fn checkpoint_makes_file_readable_while_writing() {
        let path = temp_wav("checkpoint");
        let (mut tx, rx) = rtrb::RingBuffer::new(48_000);
        let writer = spawn_writer(path.clone(), mono_spec(), rx, Duration::ZERO).expect("spawn");
        push(&mut tx, 48_000);

        // The recording is still running: only a checkpoint can have put the length in the header.
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut on_disk = 0;
        while on_disk != 48_000 && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
            on_disk = hound::WavReader::open(&path).map(|reader| reader.len()).unwrap_or(0);
        }
        assert_eq!(on_disk, 48_000);

        writer.finish().expect("finish");
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn finish_reports_samples_and_drops() {
        let path = temp_wav("finish");
        let (mut tx, rx) = rtrb::RingBuffer::new(1_000);
        let writer = spawn_writer(path.clone(), mono_spec(), rx, CHECKPOINT_EVERY).expect("spawn");
        push(&mut tx, 600);
        // What the audio callback adds when the ring is full.
        writer.dropped.fetch_add(7, Ordering::Relaxed);

        // No checkpoint is due for 5 s: the count on disk comes from finish's own drain and finalize.
        let report = writer.finish().expect("finish");
        assert_eq!((report.samples, report.dropped), (600, 7));
        assert_eq!(hound::WavReader::open(&path).expect("open").len(), 600);
        std::fs::remove_file(path).ok();
    }
}
