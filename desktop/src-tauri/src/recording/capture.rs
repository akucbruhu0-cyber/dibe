//! The audio-thread side of a recording: a cpal input stream whose callback only feeds the level
//! meter and pushes samples into a lock-free ring. No allocation, no lock, no I/O happens there;
//! the WAV is written by the thread in `writer`.

use cpal::traits::DeviceTrait;
use cpal::{Device, ErrorKind, FromSample, Sample, SizedSample, Stream, SupportedStreamConfig};
use eyre::{eyre, Result};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;
use tauri::{AppHandle, Emitter};

/// At most ~10 `record_level` events per second reach the webview.
const LEVEL_EMIT_INTERVAL_MS: u64 = 100;
/// Peak is taken from every Nth sample — plenty for a meter, and keeps the callback cheap.
const LEVEL_SAMPLE_STRIDE: usize = 4;
/// Audio the ring holds while the writer is held up (a slow disk) before the callback drops samples.
const RING_SECONDS: usize = 10;

/// Shared by every capture stream of a recording session, so the emitted value is the max
/// level across input + output (loopback) devices.
///
/// Everything here is atomic and allocation-free: the audio callback only does a few relaxed
/// loads/stores, and once per 100ms one callback also performs the (non-blocking) `emit_to`.
pub struct LevelMeter {
    app_handle: AppHandle,
    started_at: Instant,
    /// Max level seen since the last emit, stored as `f32::to_bits` (monotonic for +0.0..=1.0).
    peak_bits: AtomicU32,
    last_emit_ms: AtomicU64,
}

impl LevelMeter {
    pub fn new(app_handle: AppHandle) -> Self {
        Self {
            app_handle,
            started_at: Instant::now(),
            peak_bits: AtomicU32::new(0),
            last_emit_ms: AtomicU64::new(0),
        }
    }

    /// Accumulate one buffer's peak and emit if the throttle window elapsed.
    fn push(&self, peak: f32) {
        self.peak_bits.fetch_max(peak.to_bits(), Ordering::Relaxed);

        let now_ms = self.started_at.elapsed().as_millis() as u64;
        let last_ms = self.last_emit_ms.load(Ordering::Relaxed);
        if now_ms.saturating_sub(last_ms) < LEVEL_EMIT_INTERVAL_MS {
            return;
        }
        // Only the stream that wins the swap emits, so two devices can't double-emit a window.
        if self
            .last_emit_ms
            .compare_exchange(last_ms, now_ms, Ordering::Relaxed, Ordering::Relaxed)
            .is_err()
        {
            return;
        }
        let level = f32::from_bits(self.peak_bits.swap(0, Ordering::Relaxed)).clamp(0.0, 1.0);
        self.app_handle.emit_to("main", "record_level", level).ok();
    }
}

/// Peak magnitude of a buffer, normalized to 0..1.
fn buffer_peak<T>(input: &[T]) -> f32
where
    T: Sample,
    f32: FromSample<T>,
{
    let mut peak = 0.0f32;
    for &sample in input.iter().step_by(LEVEL_SAMPLE_STRIDE) {
        let value = f32::from_sample(sample).abs();
        if value > peak {
            peak = value;
        }
    }
    peak.min(1.0)
}

/// Every capture is written as 32-bit float, whatever the device delivers.
pub fn wav_spec(config: &SupportedStreamConfig) -> hound::WavSpec {
    hound::WavSpec {
        channels: config.channels(),
        sample_rate: config.sample_rate(),
        bits_per_sample: 32,
        sample_format: hound::SampleFormat::Float,
    }
}

/// The ring between one device's callback and its writer thread.
pub fn ring(spec: &hound::WavSpec) -> (rtrb::Producer<f32>, rtrb::Consumer<f32>) {
    rtrb::RingBuffer::new(spec.sample_rate as usize * spec.channels as usize * RING_SECONDS)
}

/// Builds (but does not start) the input stream for one device. `on_fatal_error` runs, on a cpal
/// thread, when the device stops delivering audio (e.g. it was unplugged).
pub fn build_capture(
    device: &Device,
    config: SupportedStreamConfig,
    tx: rtrb::Producer<f32>,
    meter: Arc<LevelMeter>,
    dropped: Arc<AtomicU64>,
    on_fatal_error: impl FnMut(cpal::Error) + Send + 'static,
) -> Result<Stream> {
    match config.sample_format() {
        cpal::SampleFormat::I8 => build_capture_typed::<i8>(device, config, tx, meter, dropped, on_fatal_error),
        cpal::SampleFormat::I16 => build_capture_typed::<i16>(device, config, tx, meter, dropped, on_fatal_error),
        cpal::SampleFormat::I32 => build_capture_typed::<i32>(device, config, tx, meter, dropped, on_fatal_error),
        cpal::SampleFormat::F32 => build_capture_typed::<f32>(device, config, tx, meter, dropped, on_fatal_error),
        sample_format => Err(eyre!("Unsupported sample format '{sample_format}'")),
    }
}

fn build_capture_typed<T>(
    device: &Device,
    config: SupportedStreamConfig,
    mut tx: rtrb::Producer<f32>,
    meter: Arc<LevelMeter>,
    dropped: Arc<AtomicU64>,
    mut on_fatal_error: impl FnMut(cpal::Error) + Send + 'static,
) -> Result<Stream>
where
    T: SizedSample,
    f32: FromSample<T>,
{
    let channels = usize::from(config.channels());
    let stream = device.build_input_stream(
        config.into(),
        move |data: &[T], _: &_| {
            meter.push(buffer_peak(data));
            push_samples(data, channels, &mut tx, &dropped);
        },
        move |error: cpal::Error| {
            // These leave the stream running; only the rest end the recording.
            if matches!(
                error.kind(),
                ErrorKind::DeviceChanged | ErrorKind::Xrun | ErrorKind::RealtimeDenied
            ) {
                tracing::warn!("Recording stream: {error}");
                return;
            }
            tracing::error!("Recording stream failed: {error}");
            on_fatal_error(error);
        },
        None,
    )?;
    Ok(stream)
}

/// Queues one callback's samples for the writer thread. Never blocks: whole frames that do not
/// fit in the ring are counted in `dropped` instead.
pub(crate) fn push_samples<T>(data: &[T], channels: usize, tx: &mut rtrb::Producer<f32>, dropped: &AtomicU64)
where
    T: Sample,
    f32: FromSample<T>,
{
    let free = tx.slots();
    let fits = data.len().min(free - free % channels);
    // Cannot fail: the writer only ever frees slots, so `fits` slots are still there.
    if let Ok(chunk) = tx.write_chunk_uninit(fits) {
        chunk.fill_from_iter(data.iter().map(|&sample| f32::from_sample(sample)));
    }
    let lost = data.len() - fits;
    if lost > 0 {
        dropped.fetch_add(lost as u64, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_ring_counts_dropped_without_blocking() {
        let (mut tx, mut rx) = rtrb::RingBuffer::new(16);
        let dropped = AtomicU64::new(0);
        let samples: Vec<i16> = (0..32).collect();

        push_samples(&samples, 1, &mut tx, &dropped);

        assert_eq!(dropped.load(Ordering::Relaxed), 16);
        // What fit is the start of the buffer, converted to f32.
        assert_eq!(rx.slots(), 16);
        assert_eq!(rx.pop(), Ok(0.0));
        assert_eq!(rx.pop(), Ok(f32::from_sample(1i16)));
    }

    #[test]
    fn full_ring_drops_whole_frames() {
        // 15 free slots hold 7 stereo frames; splitting the 8th would swap left and right for the
        // rest of the file.
        let (mut tx, rx) = rtrb::RingBuffer::new(15);
        let dropped = AtomicU64::new(0);

        push_samples(&[0.5f32; 32], 2, &mut tx, &dropped);

        assert_eq!(rx.slots(), 14);
        assert_eq!(dropped.load(Ordering::Relaxed), 18);
    }
}
