//! The live-transcription tap: what every device captured, mixed into one 16 kHz mono stream.
//!
//! The writer threads (see `writer`) hand `push` a copy of each chunk they drain from a device's
//! ring, so the audio callback never sees any of this. Each device is downmixed and resampled on
//! its own; whenever all of them have 100 ms ready, their sum goes out over a bounded channel to
//! the live client. A full channel drops the chunk and counts it: the tap never blocks a writer.

use std::collections::VecDeque;
use std::time::{Duration, Instant};
use tokio::sync::mpsc::{error::TrySendError, Sender};

/// The rate the live transcription engine takes.
const LIVE_HZ: u32 = 16_000;
/// Every chunk sent is exactly this many samples: 100 ms.
const CHUNK_SAMPLES: usize = 1_600;
const CHUNK_MS: u64 = 100;
/// A device that has delivered nothing for this long is mixed in as silence, so one that is
/// silent or stuck cannot stall the stream.
const STALL_AFTER: Duration = Duration::from_millis(100);
/// How far a device may run ahead of the others before its oldest audio is dropped (clock drift
/// between devices, or a burst after the device's writer was held up).
const MAX_BUFFERED: usize = 2 * LIVE_HZ as usize;

/// Where a resampler left off, so one stream can be fed to it in blocks of any size.
#[derive(Default)]
pub struct ResampleState {
    /// Where the next output sample falls, in input samples counted from the first sample of the
    /// next block. Negative: between the previous block's last sample and this block's first.
    next: f64,
    /// The previous block's last (smoothed) sample, the left neighbour for a `next` below 0.
    last: f32,
    /// The previous block's last raw sample, for the smoothing across the block edge.
    prev: Option<f32>,
}

/// Converts one block of a mono stream from `from_hz` to `to_hz`, carrying `state` to the next.
// ponytail: 2-sample average + linear interpolation; switch to rubato if accuracy measurably suffers.
pub fn resample_linear(input: &[f32], from_hz: u32, to_hz: u32, state: &mut ResampleState) -> Vec<f32> {
    if from_hz == to_hz {
        return input.to_vec();
    }
    if from_hz == 0 || to_hz == 0 {
        return Vec::new();
    }
    let smoothed: Vec<f32>;
    let samples = if from_hz > to_hz {
        // Averaging neighbours is a cheap low-pass: it keeps the decimation from aliasing much.
        let mut prev = state.prev.or_else(|| input.first().copied()).unwrap_or_default();
        smoothed = input
            .iter()
            .map(|&sample| {
                let mean = (sample + prev) / 2.0;
                prev = sample;
                mean
            })
            .collect();
        if !input.is_empty() {
            state.prev = Some(prev);
        }
        smoothed.as_slice()
    } else {
        input
    };

    let step = f64::from(from_hz) / f64::from(to_hz);
    let last = state.last;
    let at = |index: isize| {
        usize::try_from(index)
            .ok()
            .and_then(|i| samples.get(i))
            .copied()
            .unwrap_or(last)
    };
    let mut out = Vec::with_capacity((samples.len() as f64 / step) as usize + 1);
    let mut next = state.next;
    // An output needs the sample after it, so the block's final stretch waits for the next block.
    while next + 1.0 < samples.len() as f64 {
        let index = next.floor();
        let (left, right) = (at(index as isize), at(index as isize + 1));
        out.push(left + (right - left) * (next - index) as f32);
        next += step;
    }
    state.next = next - samples.len() as f64;
    if let Some(&sample) = samples.last() {
        state.last = sample;
    }
    out
}

/// One device's audio as 16 kHz mono, waiting for the others.
struct Source {
    rate: u32,
    channels: usize,
    resample: ResampleState,
    buffered: VecDeque<f32>,
    /// When this device last delivered samples (when the tap was made, if it never has).
    last_delivery: Instant,
}

impl Source {
    fn feed(&mut self, now: Instant, interleaved: &[f32]) {
        self.last_delivery = now;
        // The ring holds whole frames, so no partial frame is left over.
        let mono: Vec<f32> = interleaved
            .chunks_exact(self.channels)
            .map(|frame| frame.iter().sum::<f32>() / self.channels as f32)
            .collect();
        self.buffered
            .extend(resample_linear(&mono, self.rate, LIVE_HZ, &mut self.resample));
    }

    fn has_chunk(&self) -> bool {
        self.buffered.len() >= CHUNK_SAMPLES
    }
}

pub struct LiveTap {
    sources: Vec<Source>,
    tx: Sender<Vec<f32>>,
    /// Audio lost to a full channel, or to a device running too far ahead of the others.
    dropped_ms: u64,
    /// The receiver is gone: the live client ended.
    closed: bool,
}

impl LiveTap {
    /// One `(sample rate, channels)` per recording device; `push` names a device by its index here.
    /// `tx` is the live client's bounded channel (the caller makes it 64 chunks deep).
    #[allow(dead_code)] // Task 12 builds the tap in `start_record`.
    pub fn new(devices: &[(u32, u16)], tx: Sender<Vec<f32>>) -> Self {
        let now = Instant::now();
        let sources = devices
            .iter()
            .map(|&(rate, channels)| Source {
                rate,
                channels: usize::from(channels).max(1),
                resample: ResampleState::default(),
                buffered: VecDeque::new(),
                last_delivery: now,
            })
            .collect();
        Self {
            sources,
            tx,
            dropped_ms: 0,
            closed: false,
        }
    }

    /// Takes the samples a device's writer just drained (interleaved, in the device's own format).
    /// Never blocks and never fails: once the client is gone this does nothing.
    pub fn push(&mut self, device: usize, interleaved: &[f32]) {
        self.push_at(Instant::now(), device, interleaved);
    }

    fn push_at(&mut self, now: Instant, device: usize, interleaved: &[f32]) {
        if self.closed {
            return;
        }
        if let Some(source) = self.sources.get_mut(device).filter(|_| !interleaved.is_empty()) {
            source.feed(now, interleaved);
        }
        self.send_ready_chunks(now);
        self.drop_surplus();
    }

    #[allow(dead_code)] // Task 12 reports it to the live client.
    pub fn dropped_ms(&self) -> u64 {
        self.dropped_ms
    }

    /// Sends a chunk for as long as every device has one, or has gone quiet.
    fn send_ready_chunks(&mut self, now: Instant) {
        while !self.closed
            && self.sources.iter().any(Source::has_chunk)
            && self
                .sources
                .iter()
                .all(|source| source.has_chunk() || now.saturating_duration_since(source.last_delivery) >= STALL_AFTER)
        {
            // ponytail: start-time offset between mic and system audio is not corrected.
            let mut mixed = vec![0.0f32; CHUNK_SAMPLES];
            for source in &mut self.sources {
                // A quiet device may have less than a chunk: the rest of its part is silence.
                let take = source.buffered.len().min(CHUNK_SAMPLES);
                for (out, sample) in mixed.iter_mut().zip(source.buffered.drain(..take)) {
                    *out += sample;
                }
            }
            for sample in &mut mixed {
                *sample = sample.clamp(-1.0, 1.0);
            }
            match self.tx.try_send(mixed) {
                Ok(()) => {}
                Err(TrySendError::Full(_)) => self.dropped_ms += CHUNK_MS,
                Err(TrySendError::Closed(_)) => self.closed = true,
            }
        }
    }

    /// Keeps a device that is ahead of the others from piling up audio (and delay) without bound.
    fn drop_surplus(&mut self) {
        for source in &mut self.sources {
            let surplus = source.buffered.len().saturating_sub(MAX_BUFFERED);
            source.buffered.drain(..surplus);
            self.dropped_ms += surplus as u64 * 1_000 / u64::from(LIVE_HZ);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    use tokio::sync::mpsc::Receiver;

    const CHUNK: usize = 1_600;

    fn received(rx: &mut Receiver<Vec<f32>>) -> Vec<Vec<f32>> {
        std::iter::from_fn(|| rx.try_recv().ok()).collect()
    }

    /// The levels of the chunks, one per chunk: each must be a full 100 ms of that one value.
    fn levels(chunks: &[Vec<f32>]) -> Vec<f32> {
        for chunk in chunks {
            assert_eq!(chunk.len(), CHUNK);
            assert!(chunk.iter().all(|&sample| sample == chunk[0]), "not a constant chunk");
        }
        chunks.iter().map(|chunk| chunk[0]).collect()
    }

    /// `devices` mono 16 kHz devices: nothing to resample, so samples come out exactly as they go in.
    fn tap_16k(devices: usize, capacity: usize) -> (LiveTap, Receiver<Vec<f32>>) {
        let (tx, rx) = tokio::sync::mpsc::channel(capacity);
        (LiveTap::new(&vec![(16_000, 1); devices], tx), rx)
    }

    /// Feeds `total` samples of `value` to the resampler in blocks of the given sizes, in rotation.
    fn resample_in_blocks(from_hz: u32, block_sizes: &[usize], total: usize, value: f32) -> Vec<f32> {
        let mut state = ResampleState::default();
        let mut rest = vec![value; total];
        let mut rest = rest.as_mut_slice();
        let mut out = Vec::new();
        for &size in block_sizes.iter().cycle() {
            if rest.is_empty() {
                break;
            }
            let (block, tail) = rest.split_at_mut(size.min(rest.len()));
            out.extend(resample_linear(block, from_hz, 16_000, &mut state));
            rest = tail;
        }
        out
    }

    fn assert_one_second(out: &[f32], level: f32) {
        assert!(out.len().abs_diff(16_000) <= 1, "{} samples for 1 s", out.len());
        assert!(
            out.iter().all(|sample| (sample - level).abs() < 1e-6),
            "a constant stays constant"
        );
    }

    #[test]
    fn resample_48k_to_16k_keeps_duration() {
        assert_one_second(&resample_in_blocks(48_000, &[48_000], 48_000, 0.25), 0.25);
        // The writer hands over whatever the ring held, so blocks are never a tidy size.
        assert_one_second(&resample_in_blocks(48_000, &[441, 1_000, 17], 48_000, 0.25), 0.25);
    }

    #[test]
    fn resample_44k1_to_16k_keeps_duration() {
        assert_one_second(&resample_in_blocks(44_100, &[44_100], 44_100, 0.25), 0.25);
        assert_one_second(&resample_in_blocks(44_100, &[441, 1_000, 17], 44_100, 0.25), 0.25);
    }

    #[test]
    fn resample_gives_the_same_samples_whatever_the_chunking() {
        let tone: Vec<f32> = (0..48_000).map(|n| (n as f32 * 0.05).sin()).collect();
        let mut whole_state = ResampleState::default();
        let whole = resample_linear(&tone, 48_000, 16_000, &mut whole_state);

        let mut state = ResampleState::default();
        let mut pieces = Vec::new();
        let mut rest = tone.as_slice();
        for &size in [441, 1_000, 17].iter().cycle() {
            if rest.is_empty() {
                break;
            }
            let (block, tail) = rest.split_at(size.min(rest.len()));
            pieces.extend(resample_linear(block, 48_000, 16_000, &mut state));
            rest = tail;
        }

        // A block edge that lost its neighbour sample would click here.
        assert!(whole.len() > 15_000);
        assert_eq!(pieces.len(), whole.len());
        assert!(pieces.iter().zip(&whole).all(|(a, b)| (a - b).abs() < 1e-5));
    }

    #[test]
    fn resample_same_rate_is_pass_through() {
        let input = [0.1, -0.2, 0.3];
        assert_eq!(resample_linear(&input, 16_000, 16_000, &mut ResampleState::default()), input);
    }

    #[test]
    fn two_devices_are_summed_and_clamped() {
        let (mut tap, mut rx) = tap_16k(2, 8);

        tap.push(0, &[0.25; CHUNK]);
        assert!(received(&mut rx).is_empty(), "waits for the second device");
        tap.push(1, &[0.5; CHUNK]);
        assert_eq!(levels(&received(&mut rx)), [0.75]);

        tap.push(0, &[0.75; CHUNK]);
        tap.push(1, &[0.75; CHUNK]);
        tap.push(0, &[-0.75; CHUNK]);
        tap.push(1, &[-0.75; CHUNK]);
        assert_eq!(levels(&received(&mut rx)), [1.0, -1.0]);
    }

    #[test]
    fn full_channel_drops_without_blocking() {
        let (mut tap, mut rx) = tap_16k(1, 1);

        // Three chunks into a channel that holds one and that nobody is reading.
        tap.push(0, &[0.5; 3 * CHUNK]);

        assert_eq!(received(&mut rx).len(), 1);
        assert_eq!(tap.dropped_ms(), 200);
    }

    #[test]
    fn silent_device_does_not_stall_the_stream() {
        let started = Instant::now();
        let (mut tap, mut rx) = tap_16k(2, 8);

        tap.push_at(started, 0, &[0.5; 3 * CHUNK]);
        assert!(received(&mut rx).is_empty(), "the second device may just be late");

        // 100 ms on, it has still delivered nothing: its part of the mix is silence.
        tap.push_at(started + Duration::from_millis(150), 0, &[]);
        assert_eq!(levels(&received(&mut rx)), [0.5; 3]);
    }

    #[test]
    fn device_running_ahead_is_capped() {
        let started = Instant::now();
        let (mut tap, mut rx) = tap_16k(2, 8);

        tap.push_at(started, 1, &[0.5; CHUNK]);
        // 5 s from the first device against 100 ms from the second, which is not late enough to be
        // called silent: one chunk goes out, and only 2 s of what is left is worth waiting with.
        tap.push_at(started, 0, &[0.5; 50 * CHUNK]);

        assert_eq!(received(&mut rx).len(), 1);
        assert_eq!(tap.dropped_ms(), 2_900);
    }

    #[test]
    fn stereo_is_downmixed_by_averaging() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        let mut tap = LiveTap::new(&[(16_000, 2)], tx);

        let frames = [0.75f32, 0.25].repeat(CHUNK);
        tap.push(0, &frames);

        assert_eq!(levels(&received(&mut rx)), [0.5]);
    }

    #[test]
    fn closed_receiver_stops_forwarding_without_error() {
        let (mut tap, rx) = tap_16k(1, 8);
        drop(rx);

        tap.push(0, &[0.5; 2 * CHUNK]);
        tap.push(0, &[0.5; 2 * CHUNK]);

        // The live client is gone; that is not audio lost, and it is not the recording's problem.
        assert!(tap.closed);
        assert_eq!(tap.dropped_ms(), 0);
    }
}
