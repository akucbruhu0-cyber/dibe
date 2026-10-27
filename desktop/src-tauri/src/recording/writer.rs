//! The thread that moves captured audio from the ring to disk.
//!
//! The audio callback only pushes samples into a lock-free ring (see `capture`). This thread
//! empties it every ~20 ms into a 32-bit float WAV and checkpoints the header every
//! `checkpoint_every`, so the file on disk is readable up to the last checkpoint even if the app
//! dies without finalizing it. Its drain tick also runs `on_tick`, the non-realtime home of
//! anything the callback must not do itself (the level meter's emit). It can also hand a copy of
//! every drained chunk to the live transcription tap (see `live_tap`).

use crate::recording::live_tap::LiveTap;
use eyre::{bail, eyre, Result};
use std::fs::File;
use std::io::BufWriter;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
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

/// The live tap a writer feeds, and which device of it the writer is.
pub type LiveFeed = (Arc<Mutex<LiveTap>>, usize);

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

/// Creates the WAV at `path` and starts the thread that fills it from `rx`, calling `on_tick`
/// after every drain. With a `live` tap, every drained chunk is also copied to it once the WAV has
/// it; the tap never holds the WAV up, and a broken one is dropped without touching the file.
/// A handle dropped without `finish` still stops the thread, which then finalizes the file on its own.
pub fn spawn_writer(
    path: PathBuf,
    spec: hound::WavSpec,
    rx: rtrb::Consumer<f32>,
    checkpoint_every: Duration,
    on_tick: impl FnMut() + Send + 'static,
    live: Option<LiveFeed>,
) -> Result<WriterHandle> {
    let wav = hound::WavWriter::create(&path, spec)?;
    let (stop, stopped) = mpsc::channel();
    let forward = live.map(|(tap, device)| Forward {
        tap,
        device,
        scratch: Vec::new(),
    });
    let thread = std::thread::Builder::new()
        .name("vibe-wav-writer".into())
        .spawn(move || write_until_stopped(wav, rx, &stopped, checkpoint_every, on_tick, forward))?;
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
    mut on_tick: impl FnMut(),
    mut forward: Option<Forward>,
) -> Result<u64> {
    let mut last_checkpoint = Instant::now();
    loop {
        let stopping = !matches!(stopped.recv_timeout(DRAIN_EVERY), Err(RecvTimeoutError::Timeout));
        drain(&mut rx, &mut wav, &mut forward)?;
        if stopping {
            break;
        }
        on_tick();
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

/// This writer's way into the live tap.
struct Forward {
    tap: Arc<Mutex<LiveTap>>,
    device: usize,
    /// The ring's two slices end to end: the tap wants one run of whole frames.
    scratch: Vec<f32>,
}

impl Forward {
    /// Copies the drained samples to the tap. False when the tap is broken (a thread panicked
    /// holding it): the caller stops forwarding. A tap whose client left just ignores the samples.
    fn send(&mut self, first: &[f32], second: &[f32]) -> bool {
        self.scratch.clear();
        self.scratch.extend_from_slice(first);
        self.scratch.extend_from_slice(second);
        let Ok(mut tap) = self.tap.lock() else {
            tracing::warn!("The live tap is broken: live transcription no longer gets this device's audio");
            return false;
        };
        tap.push(self.device, &self.scratch);
        true
    }
}

/// Writes everything the callback has queued so far, and copies it to the live tap if there is one.
fn drain(rx: &mut rtrb::Consumer<f32>, wav: &mut Wav, forward: &mut Option<Forward>) -> Result<()> {
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
    if forward.as_mut().is_some_and(|forward| !forward.send(first, second)) {
        *forward = None;
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
        let writer = spawn_writer(path.clone(), mono_spec(), rx, Duration::ZERO, || {}, None).expect("spawn");
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
        let writer = spawn_writer(path.clone(), mono_spec(), rx, CHECKPOINT_EVERY, || {}, None).expect("spawn");
        push(&mut tx, 600);
        // What the audio callback adds when the ring is full.
        writer.dropped.fetch_add(7, Ordering::Relaxed);

        // No checkpoint is due for 5 s: the count on disk comes from finish's own drain and finalize.
        let report = writer.finish().expect("finish");
        assert_eq!((report.samples, report.dropped), (600, 7));
        assert_eq!(hound::WavReader::open(&path).expect("open").len(), 600);
        std::fs::remove_file(path).ok();
    }

    /// A tap for one 48 kHz mono device, and the live client's end of its channel.
    fn live_tap() -> (Arc<Mutex<LiveTap>>, tokio::sync::mpsc::Receiver<Vec<f32>>) {
        let (live_tx, live_rx) = tokio::sync::mpsc::channel(64);
        (Arc::new(Mutex::new(LiveTap::new(&[(48_000, 1)], live_tx))), live_rx)
    }

    /// Whether the WAV holds exactly `count` samples, all of them `push`'s 0.25.
    fn wav_is_all_pushed(path: &PathBuf, count: usize) -> bool {
        let mut reader = hound::WavReader::open(path).expect("open");
        let samples: Vec<f32> = reader.samples::<f32>().map(|sample| sample.expect("sample")).collect();
        samples.len() == count && samples.iter().all(|&sample| sample == 0.25)
    }

    #[test]
    fn live_tap_gets_a_copy_and_the_wav_keeps_every_sample() {
        let path = temp_wav("live");
        let (mut tx, rx) = rtrb::RingBuffer::new(48_000);
        let (tap, mut live_rx) = live_tap();
        let writer = spawn_writer(path.clone(), mono_spec(), rx, CHECKPOINT_EVERY, || {}, Some((tap, 0))).expect("spawn");
        push(&mut tx, 9_600);

        let report = writer.finish().expect("finish");

        assert_eq!(report.samples, 9_600);
        assert!(wav_is_all_pushed(&path, 9_600));
        // 200 ms at 48 kHz reached the tap, which sent it on as two 100 ms chunks at 16 kHz.
        let chunks: Vec<Vec<f32>> = std::iter::from_fn(|| live_rx.try_recv().ok()).collect();
        assert_eq!(chunks.len(), 2);
        assert!(chunks
            .iter()
            .all(|chunk| chunk.len() == 1_600 && chunk.iter().all(|&sample| sample == 0.25)));
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn broken_tap_does_not_touch_the_wav() {
        let path = temp_wav("broken-tap");
        let (mut tx, rx) = rtrb::RingBuffer::new(48_000);
        let (tap, mut live_rx) = live_tap();
        // A writer of another device panicked while it held the tap.
        let poisoner = tap.clone();
        std::thread::spawn(move || {
            let _held = poisoner.lock();
            panic!("poisons the tap on purpose");
        })
        .join()
        .ok();
        let writer = spawn_writer(path.clone(), mono_spec(), rx, CHECKPOINT_EVERY, || {}, Some((tap, 0))).expect("spawn");
        push(&mut tx, 9_600);

        let report = writer.finish().expect("a broken tap is not the writer's failure");

        assert_eq!(report.samples, 9_600);
        assert!(wav_is_all_pushed(&path, 9_600));
        assert!(live_rx.try_recv().is_err());
        std::fs::remove_file(path).ok();
    }
}
