use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Device, SupportedStreamConfig};
use eyre::{eyre, Context, ContextCompat, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager, State};

use super::CommandError;
use crate::error::LogError;
use crate::ffmpeg::{get_local_time, get_vibe_temp_folder};
use crate::recording::capture::{self, build_capture, LevelMeter};
use crate::recording::finalize::{finalize_captures, remove_files};
use crate::recording::recovery::{
    self, capture_path, in_progress_dir, repair_wav_header, PendingRecovery, RecoveredRecording, BYTES_PER_SAMPLE,
};
use crate::recording::session::{ActiveRecording, AlreadyRecording, RecordingOrigin, RecordingState, StreamHandle};
use crate::recording::writer::{spawn_writer, WriterHandle, CHECKPOINT_EVERY};

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioDevice {
    pub is_default: bool,
    pub is_input: bool,
    pub id: String,
    pub name: String,
}

#[tauri::command]
pub fn get_audio_devices() -> Result<Vec<AudioDevice>> {
    let host = cpal::default_host();
    let mut audio_devices = Vec::new();

    let default_in = host
        .default_input_device()
        .map(|e| e.description().map(|d| d.to_string()))
        .context("name")?;
    let default_out = host
        .default_output_device()
        .map(|e| e.description().map(|d| d.to_string()))
        .context("name")?;
    tracing::debug!("Default Input Device:\n{:?}", default_in);
    tracing::debug!("Default Output Device:\n{:?}", default_out);

    let devices = host.devices()?;
    tracing::debug!("Devices: ");
    for (device_index, device) in devices.enumerate() {
        let name = device.description()?.to_string();
        let is_default_in = default_in.as_ref().is_ok_and(|d| d == &name);
        let is_default_out = default_out.as_ref().is_ok_and(|d| d == &name);

        let audio_device = AudioDevice {
            is_default: is_default_in || is_default_out,
            is_input: device.supports_input(),
            id: device_index.to_string(),
            name,
        };
        audio_devices.push(audio_device);
    }

    Ok(audio_devices)
}

impl From<AlreadyRecording> for CommandError {
    fn from(_: AlreadyRecording) -> Self {
        CommandError {
            code: "already_recording".to_string(),
            message: "A recording is already in progress".to_string(),
        }
    }
}

#[tauri::command]
/// Record audio from the given devices into wav files and return the session id that `stop_record`
/// needs. Only one recording can run at a time; a second call fails with code `already_recording`.
pub async fn start_record(
    app_handle: AppHandle,
    state: State<'_, RecordingState>,
    devices: Vec<AudioDevice>,
    recording_name: Option<String>,
    origin: RecordingOrigin,
) -> Result<String, CommandError> {
    if devices.is_empty() {
        return Err(eyre!("At least one audio device is required").into());
    }
    let session_id = state.try_begin(origin)?;
    let mut recording = ActiveRecording::new(session_id.clone(), origin, recording_name);
    if let Err(error) = start_streams(&app_handle, devices, &mut recording) {
        // Free the slot for the next attempt and stop the streams that did start.
        state.take(&session_id);
        discard_recording(recording);
        return Err(CommandError {
            code: "internal_error".to_string(),
            message: format!("{error:#}"),
        });
    }
    if let Err(recording) = state.install(recording) {
        discard_recording(*recording);
        return Err(CommandError {
            code: "internal_error".to_string(),
            message: "The recording device stopped while the recording was starting".to_string(),
        });
    }

    crate::meeting_prompt::recording_started(&app_handle);
    Ok(session_id)
}

/// Stops a recording that never got going and deletes what it wrote. Left in the in-progress
/// folder, it would be recovered at the next launch as if the app had crashed.
fn discard_recording(recording: ActiveRecording) {
    let closed = close_captures(recording.streams, recording.writers, recording.wav_paths);
    remove_files(closed.files.into_iter().map(|(path, _)| path));
}

fn start_streams(app_handle: &AppHandle, devices: Vec<AudioDevice>, recording: &mut ActiveRecording) -> Result<()> {
    let host = cpal::default_host();
    // One meter for the whole session: every stream raises its peak and every writer's tick may
    // emit it, so the UI sees the max of all devices under a single throttled `record_level` event.
    let meter = Arc::new(LevelMeter::default());
    // Not the temp folder: it is wiped on the next day's launch, which a crash would never outlive.
    let dir = in_progress_dir(app_handle);

    for (device_index, device) in devices.into_iter().enumerate() {
        tracing::debug!("Recording from device: {}", device.name);
        tracing::debug!("Device ID: {}", device.id);

        let is_input = device.is_input;
        let (device, config) = if is_input {
            let device_id: usize = device.id.parse().context("Failed to parse device ID")?;
            let dev = host.devices()?.nth(device_id).context("Failed to get device by ID")?;
            let config = dev.default_input_config().context("Failed to get default input config")?;
            (dev, config)
        } else {
            get_output_device_and_config(&host, &device)?
        };
        let spec = capture::wav_spec(&config);

        let path = capture_path(&dir, &recording.session_id, device_index);
        tracing::debug!("WAV file path: {:?}", path);

        let (tx, rx) = capture::ring(&spec);
        let emit_level = {
            let (meter, app_handle) = (meter.clone(), app_handle.clone());
            move || meter.emit_if_due(&app_handle)
        };
        let writer = spawn_writer(path.clone(), spec, rx, CHECKPOINT_EVERY, emit_level)?;
        let dropped = writer.dropped.clone();
        recording.writers.push(writer);
        recording.wav_paths.push(path);

        let on_error = end_on_stream_error(app_handle, &recording.session_id);
        let stream = build_capture(&device, config, tx, meter.clone(), dropped, on_error)?;
        stream.play()?;
        tracing::debug!("Stream started playing");
        recording.streams.push(StreamHandle(stream));
    }
    Ok(())
}

#[tauri::command]
/// Stop the session `start_record` returned, merge its captures and emit `record_finish`
/// (`record_error` only when no audio was saved at all).
/// A session that is not the active one (already finished, or never started) is ignored.
pub async fn stop_record(app_handle: AppHandle, state: State<'_, RecordingState>, session_id: String) -> Result<()> {
    let Some(recording) = state.take(&session_id) else {
        tracing::debug!("stop_record ignored: session {session_id} is not active");
        return Ok(());
    };
    // Merging and normalizing shell out to ffmpeg, which must not block the async runtime.
    tauri::async_runtime::spawn_blocking(move || finish_recording(&app_handle, recording, None)).await?;
    Ok(())
}

#[tauri::command]
/// The recordings that an earlier run never got to finish, rebuilt at launch, plus any rebuilt
/// earlier and not yet forgotten. Each is a WAV in app data waiting to become a project; this
/// launch's list is handed over once, so a second call gets nothing.
pub async fn take_recovered_recordings(state: State<'_, PendingRecovery>) -> Result<Vec<RecoveredRecording>> {
    Ok(state.take().await)
}

#[tauri::command]
/// Deletes a recovered recording once the UI has saved it as a project; until then every launch
/// offers it again. Only files directly inside the recovered folder can be deleted.
pub fn forget_recovered(app_handle: AppHandle, path: String) -> Result<(), CommandError> {
    let recovered = recovery::recovered_dir(&in_progress_dir(&app_handle));
    Ok(recovery::forget(&recovered, Path::new(&path))?)
}

/// cpal's error callback for a session's streams. A device that stops delivering audio (e.g.
/// AirPods unplugged mid-meeting) ends the session; what was captured until then still becomes a
/// recording, with the failure as its warning.
fn end_on_stream_error(app_handle: &AppHandle, session_id: &str) -> impl FnMut(cpal::Error) + Send + 'static {
    let app_handle = app_handle.clone();
    let session_id = session_id.to_string();
    move |error| {
        let app_handle = app_handle.clone();
        let session_id = session_id.clone();
        // Finishing joins the writer threads and runs ffmpeg, so it must not run on cpal's thread.
        tauri::async_runtime::spawn_blocking(move || {
            // Only the first failure finds the session; a second device or a racing stop_record is a no-op.
            if let Some(recording) = app_handle.state::<RecordingState>().take(&session_id) {
                let failure = format!("The recording device stopped ({error}); the recording was saved up to that point");
                finish_recording(&app_handle, recording, Some(failure));
            }
        });
    }
}

/// What the writers left on disk once a session's streams stopped.
struct ClosedCaptures {
    /// Each WAV with the number of samples in it.
    files: Vec<(PathBuf, u64)>,
    /// Samples the audio callbacks dropped because a ring was full.
    dropped: u64,
    /// Writers that failed; their file still holds the audio written until then.
    failures: Vec<String>,
}

/// Stops the streams, then lets every writer drain what was captured and finalize its WAV.
fn close_captures(streams: Vec<StreamHandle>, writers: Vec<WriterHandle>, wav_paths: Vec<PathBuf>) -> ClosedCaptures {
    for stream in &streams {
        tracing::debug!("Pausing stream");
        stream.0.pause().map_err(|e| eyre!("{e:?}")).log_error();
    }
    // Dropping the streams ends their callbacks, so each writer's last drain gets every sample.
    drop(streams);

    let mut closed = ClosedCaptures {
        files: Vec::new(),
        dropped: 0,
        failures: Vec::new(),
    };
    for (writer, path) in writers.into_iter().zip(wav_paths) {
        let samples = match writer.finish() {
            Ok(report) => {
                closed.dropped += report.dropped;
                report.samples
            }
            Err(error) => {
                let message = format!("Saving {} failed: {error:#}", path.display());
                tracing::error!("{message}");
                closed.failures.push(message);
                // The writer stopped at the error, so its header may not say how much audio the
                // file holds: measure the file. One that cannot be measured is not known to be
                // empty; it stays on disk for the next launch's scan instead of being deleted.
                match repair_wav_header(&path) {
                    Ok(bytes) => bytes / BYTES_PER_SAMPLE,
                    Err(error) => {
                        tracing::error!("leaving {} as it is: {error:#}", path.display());
                        continue;
                    }
                }
            }
        };
        closed.files.push((path, samples));
    }
    closed
}

/// The event that ends a session for its listeners: `record_finish` whenever audio was saved, so
/// the UI keeps it as a project and shows `warnings` (a failed device or writer included);
/// `record_error` only when there is no `output` to keep.
fn recording_event(
    session_id: &str,
    origin: RecordingOrigin,
    output: Option<&Path>,
    warnings: Vec<String>,
) -> (&'static str, Value) {
    let Some(output) = output else {
        let mut message = "Recording stopped without any captured audio".to_string();
        if !warnings.is_empty() {
            message = format!("{message}: {}", warnings.join("; "));
        }
        return (
            "record_error",
            json!({ "sessionId": session_id, "origin": origin, "message": message }),
        );
    };
    let warning = (!warnings.is_empty()).then(|| warnings.join("; "));
    (
        "record_finish",
        json!({
            "sessionId": session_id,
            "origin": origin,
            "path": output.to_string_lossy(),
            "name": output.file_name().map(|name| name.to_string_lossy()).unwrap_or_default(),
            "warning": warning,
        }),
    )
}

/// Ends a session: saves its audio, merges and normalizes it, and tells the listeners. `failure`
/// is why it ended on its own, when it did.
fn finish_recording(app_handle: &AppHandle, recording: ActiveRecording, failure: Option<String>) {
    let ActiveRecording {
        session_id,
        origin,
        recording_name,
        streams,
        writers,
        wav_paths,
    } = recording;
    let closed = close_captures(streams, writers, wav_paths);
    let wav_paths = closed.files;
    // What cut the recording short rides on it as a warning: the saved audio still becomes a project.
    let mut warnings: Vec<String> = failure.into_iter().chain(closed.failures).collect();
    if closed.dropped > 0 {
        let message = format!(
            "{} audio samples were dropped because the disk could not keep up",
            closed.dropped
        );
        tracing::warn!("{message}");
        warnings.push(message);
    }

    let recording_stem = recording_name
        .as_deref()
        .map(crate::cmd::files::sanitize_filename_stem)
        .filter(|name| !name.is_empty())
        .unwrap_or_else(get_local_time);
    let Some(output) = finalize_captures(&wav_paths, &get_vibe_temp_folder(), &recording_stem, &mut warnings) else {
        tracing::error!("Recording stopped without any captured audio");
        let (event, payload) = recording_event(&session_id, origin, None, warnings);
        app_handle.emit(event, payload).map_err(|e| eyre!("{e:?}")).log_error();
        crate::meeting_prompt::recording_stopped(app_handle);
        return;
    };

    let (event, payload) = recording_event(&session_id, origin, Some(&output), warnings);
    tracing::debug!("Emitting {event} event");
    app_handle.emit(event, payload).map_err(|e| eyre!("{e:?}")).log_error();
    crate::meeting_prompt::recording_stopped(app_handle);
}

#[allow(unused_variables)]
fn get_output_device_and_config(host: &cpal::Host, audio_device: &AudioDevice) -> Result<(Device, SupportedStreamConfig)> {
    // On macOS, use the default output device directly — cpal's loopback support
    // requires this path to build an input stream from an output device.
    #[cfg(target_os = "macos")]
    {
        let device = host.default_output_device().context("Failed to get default output device")?;
        let config = device
            .default_output_config()
            .context("Failed to get default output config")?;
        Ok((device, config))
    }

    #[cfg(not(target_os = "macos"))]
    {
        let device_id: usize = audio_device.id.parse().context("Failed to parse device ID")?;
        let device = host.devices()?.nth(device_id).context("Failed to get device by ID")?;
        let config = device
            .default_output_config()
            .context("Failed to get default output config")?;
        Ok((device, config))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffmpeg::random_string;
    use crate::recording::finalize::best_raw_capture;

    #[test]
    fn discarded_recording_leaves_nothing_to_recover() {
        let path = std::env::temp_dir().join(format!("vibe-discard-{}.wav", random_string(8)));
        let spec = hound::WavSpec {
            channels: 1,
            sample_rate: 48_000,
            bits_per_sample: 32,
            sample_format: hound::SampleFormat::Float,
        };
        let (mut tx, rx) = capture::ring(&spec);
        let writer = spawn_writer(path.clone(), spec, rx, CHECKPOINT_EVERY, || {}).expect("spawn");
        // A first device was already recording when the second one failed to start.
        capture::push_samples(&[0.5f32; 4_800], 1, &mut tx, &writer.dropped);
        let mut recording = ActiveRecording::new("session".to_string(), RecordingOrigin::Meeting, None);
        recording.writers.push(writer);
        recording.wav_paths.push(path.clone());

        discard_recording(recording);

        // Left in the in-progress folder it would be recovered at the next launch as a crash.
        assert!(!path.exists());
    }

    #[test]
    fn stream_error_finishes_session_with_audio() {
        let path = std::env::temp_dir().join(format!("vibe-stream-error-{}.wav", random_string(8)));
        let spec = hound::WavSpec {
            channels: 1,
            sample_rate: 48_000,
            bits_per_sample: 32,
            sample_format: hound::SampleFormat::Float,
        };
        let (mut tx, rx) = capture::ring(&spec);
        let writer = spawn_writer(path.clone(), spec, rx, CHECKPOINT_EVERY, || {}).expect("spawn");
        let state = RecordingState::default();
        let session_id = state.try_begin(RecordingOrigin::Meeting).expect("begin");
        // The device delivered 0.1 s of audio and was unplugged: cpal calls the error callback and
        // never the data callback again.
        capture::push_samples(&[0.5f32; 4_800], 1, &mut tx, &writer.dropped);
        drop(tx);
        let mut recording = ActiveRecording::new(session_id.clone(), RecordingOrigin::Meeting, None);
        recording.writers.push(writer);
        recording.wav_paths.push(path.clone());
        assert!(state.install(recording).is_ok());

        // What the error callback does: take the session, close its captures, keep what was saved
        // and carry the failure as a warning.
        let recording = state.take(&session_id).expect("the failure ends the active session");
        let closed = close_captures(recording.streams, recording.writers, recording.wav_paths);
        let saved = best_raw_capture(&closed.files).expect("the audio before the failure was saved");
        let failure = "The recording device stopped (Device disconnected)".to_string();
        let (event, payload) = recording_event(&session_id, recording.origin, Some(&saved), vec![failure.clone()]);

        assert_eq!(closed.files, vec![(path.clone(), 4_800)]);
        assert_eq!(hound::WavReader::open(&path).expect("saved WAV").len(), 4_800);
        assert_eq!(event, "record_finish", "saved audio becomes a project");
        assert_eq!(payload["path"], path.to_string_lossy().as_ref());
        assert_eq!(payload["origin"], "meeting");
        assert!(payload["warning"]
            .as_str()
            .is_some_and(|warning| warning.contains("Device disconnected")));
        // A second failing device finds no session left to end.
        assert!(state.take(&session_id).is_none());
        // Had the device failed before delivering any audio, there would be no project: an error says why.
        let (event, payload) = recording_event(&session_id, recording.origin, None, vec![failure]);
        assert_eq!(event, "record_error");
        assert!(payload["message"]
            .as_str()
            .is_some_and(|message| message.contains("Device disconnected")));
        std::fs::remove_file(path).ok();
    }
}
