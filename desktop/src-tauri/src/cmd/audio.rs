use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Device, DeviceId, SupportedStreamConfig};
use eyre::{eyre, Context, Result};
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
use crate::server::live::{LiveClient, LiveOptions, LiveTranscript, StoppingLive};

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioDevice {
    pub is_default: bool,
    pub is_input: bool,
    pub id: String,
    pub name: String,
}

/// Which of `names` are the default input or output. `None` means the machine has no such device
/// (a Mac mini without a microphone), which marks nothing instead of being an error.
fn mark_defaults(names: &[String], default_in: Option<&str>, default_out: Option<&str>) -> Vec<bool> {
    names
        .iter()
        .map(|name| [default_in, default_out].contains(&Some(name.as_str())))
        .collect()
}

#[tauri::command]
/// Every audio device the host lists, each with the stable id `start_record` finds it by. A machine
/// without a default microphone or speakers still gets its list.
pub fn get_audio_devices() -> Result<Vec<AudioDevice>> {
    let host = cpal::default_host();
    let default_name = |device: Option<Device>| device.and_then(|device| device.description().ok()).map(|d| d.to_string());
    let default_in = default_name(host.default_input_device());
    let default_out = default_name(host.default_output_device());
    tracing::debug!("Default Input Device:\n{:?}", default_in);
    tracing::debug!("Default Output Device:\n{:?}", default_out);

    // (id, name, is_input) of each device that can be told apart and selected again later.
    let mut found = Vec::new();
    tracing::debug!("Devices: ");
    for device in host.devices()? {
        match (device.id(), device.description()) {
            (Ok(id), Ok(name)) => found.push((id.to_string(), name.to_string(), device.supports_input())),
            (id, name) => tracing::warn!("Skipping a device that cannot be identified: id {id:?}, name {name:?}"),
        }
    }

    let names: Vec<String> = found.iter().map(|(_, name, _)| name.clone()).collect();
    let defaults = mark_defaults(&names, default_in.as_deref(), default_out.as_deref());
    Ok(found
        .into_iter()
        .zip(defaults)
        .map(|((id, name, is_input), is_default)| AudioDevice {
            is_default,
            is_input,
            id,
            name,
        })
        .collect())
}

/// A device `start_record` was asked for is not connected (anymore). The UI shows it by name.
#[derive(Debug)]
struct DeviceUnavailable(String);

impl std::fmt::Display for DeviceUnavailable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "The audio device \"{}\" is not connected", self.0)
    }
}

impl std::error::Error for DeviceUnavailable {}

impl From<DeviceUnavailable> for CommandError {
    fn from(error: DeviceUnavailable) -> Self {
        CommandError {
            code: "device_unavailable".to_string(),
            message: error.to_string(),
        }
    }
}

/// The device a recording asked for, found by the stable id `get_audio_devices` gave it. An id
/// that no connected device has (unplugged since, or an index an older version saved) is
/// `DeviceUnavailable`, never another device that happens to sit at that position now.
fn find_device(host: &cpal::Host, audio_device: &AudioDevice) -> Result<Device> {
    audio_device
        .id
        .parse::<DeviceId>()
        .ok()
        .and_then(|id| host.device_by_id(&id))
        .ok_or_else(|| DeviceUnavailable(audio_device.name.clone()).into())
}

/// How a failed start reaches the UI: a missing device has its own code, anything else is internal.
fn start_error(error: eyre::Report) -> CommandError {
    match error.downcast::<DeviceUnavailable>() {
        Ok(unavailable) => unavailable.into(),
        Err(error) => CommandError {
            code: "internal_error".to_string(),
            message: format!("{error:#}"),
        },
    }
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
/// With `live`, the audio is also transcribed as it is recorded (`live_update` / `live_status`);
/// that never makes the recording fail, whatever happens to it.
pub async fn start_record(
    app_handle: AppHandle,
    state: State<'_, RecordingState>,
    devices: Vec<AudioDevice>,
    recording_name: Option<String>,
    origin: RecordingOrigin,
    live: Option<LiveOptions>,
) -> Result<String, CommandError> {
    if devices.is_empty() {
        return Err(eyre!("At least one audio device is required").into());
    }
    let session_id = state.try_begin(origin)?;
    let mut recording = ActiveRecording::new(session_id.clone(), origin, recording_name);
    if let Err(error) = start_streams(&app_handle, devices, &mut recording, live) {
        // Free the slot for the next attempt and stop the streams that did start.
        state.take(&session_id);
        discard_recording(recording);
        return Err(start_error(error));
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
    // Its task closes any session it opened; nothing waits for it.
    if let Some(live) = recording.live {
        live.stop();
    }
    remove_files(closed.files.into_iter().map(|(path, _)| path));
}

fn start_streams(
    app_handle: &AppHandle,
    devices: Vec<AudioDevice>,
    recording: &mut ActiveRecording,
    live: Option<LiveOptions>,
) -> Result<()> {
    let host = cpal::default_host();
    // One meter for the whole session: every stream raises its peak and every writer's tick may
    // emit it, so the UI sees the max of all devices under a single throttled `record_level` event.
    let meter = Arc::new(LevelMeter::default());
    // Not the temp folder: it is wiped on the next day's launch, which a crash would never outlive.
    let dir = in_progress_dir(app_handle);

    // Every device first: the live tap mixes them all, so it needs every format before any writer.
    let devices = devices
        .iter()
        .map(|device| open_device(&host, device))
        .collect::<Result<Vec<_>>>()?;
    if let Some(options) = live {
        let formats: Vec<(u32, u16)> = devices
            .iter()
            .map(|(_, config)| (config.sample_rate(), config.channels()))
            .collect();
        recording.live = Some(LiveClient::start(
            app_handle,
            &recording.session_id,
            options,
            &formats,
            recording.paused.clone(),
        ));
    }

    for (device_index, (device, config)) in devices.into_iter().enumerate() {
        let spec = capture::wav_spec(&config);

        let path = capture_path(&dir, &recording.session_id, device_index);
        tracing::debug!("WAV file path: {:?}", path);

        let (tx, rx) = capture::ring(&spec);
        let emit_level = {
            let (meter, app_handle) = (meter.clone(), app_handle.clone());
            move || meter.emit_if_due(&app_handle)
        };
        let live = recording.live.as_ref().map(|live| live.feed(device_index));
        let writer = spawn_writer(
            path.clone(),
            spec,
            rx,
            CHECKPOINT_EVERY,
            emit_level,
            live,
            recording.paused.clone(),
        )?;
        let dropped = writer.dropped.clone();
        recording.writers.push(writer);
        recording.wav_paths.push(path);

        let on_error = end_on_stream_error(app_handle, &recording.session_id, device_index);
        let stream = build_capture(&device, config, tx, meter.clone(), dropped, on_error)?;
        stream.play()?;
        tracing::debug!("Stream started playing");
        recording.streams.push(StreamHandle(stream));
    }
    Ok(())
}

/// A device `start_record` was asked for, and the format it records in.
fn open_device(host: &cpal::Host, device: &AudioDevice) -> Result<(Device, SupportedStreamConfig)> {
    tracing::debug!("Recording from device: {}", device.name);
    tracing::debug!("Device ID: {}", device.id);
    if !device.is_input {
        return get_output_device_and_config(host, device);
    }
    let found = find_device(host, device)?;
    let config = found.default_input_config().context("Failed to get default input config")?;
    Ok((found, config))
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
    tauri::async_runtime::spawn_blocking(move || finish_recording(&app_handle, recording)).await?;
    Ok(())
}

#[tauri::command]
/// Pauses or resumes the recording `session_id`: while paused the devices are stopped and nothing is
/// saved or transcribed, and the pause shows in the file as one second of silence. Ignored for a
/// session that is not active.
pub fn pause_record(state: State<'_, RecordingState>, session_id: String, paused: bool) {
    if !state.set_paused(&session_id, paused) {
        tracing::debug!("pause_record ignored: session {session_id} is not active");
    }
}

#[tauri::command]
/// Retries a paused live transcription of `session_id` now, instead of after its backoff.
pub fn retry_live(state: State<'_, RecordingState>, session_id: String) {
    state.retry_live(&session_id);
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

/// cpal's error callback for device `device` of a session. A device that stops delivering audio
/// (AirPods unplugged mid-meeting, or switched to the call profile) keeps what it captured; the
/// other devices go on recording, and once none is left the session ends and becomes a recording,
/// with the failures as its warning.
fn end_on_stream_error(app_handle: &AppHandle, session_id: &str, device: usize) -> impl FnMut(cpal::Error) + Send + 'static {
    let app_handle = app_handle.clone();
    let session_id = session_id.to_string();
    move |error| {
        let app_handle = app_handle.clone();
        let session_id = session_id.clone();
        // Finishing joins the writer threads and runs ffmpeg, so it must not run on cpal's thread.
        tauri::async_runtime::spawn_blocking(move || {
            let warning = format!("A recording device stopped ({error}); what it captured until then was saved");
            // A repeated error of the same device, or a racing stop_record, finds nothing to end.
            if let Some(recording) = app_handle
                .state::<RecordingState>()
                .device_failed(&session_id, device, warning)
            {
                finish_recording(&app_handle, recording);
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
/// the UI keeps it as a project and shows `warnings` (a failed device or writer included, with
/// `device_disconnected` set when a device stopped), with the live transcription's finals and
/// whether they cover all of the audio when it had one; `record_error` only when there is no
/// `output` to keep.
fn recording_event(
    session_id: &str,
    origin: RecordingOrigin,
    output: Option<&Path>,
    warnings: Vec<String>,
    device_disconnected: bool,
    live: Option<LiveTranscript>,
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
            "deviceDisconnected": device_disconnected,
            "liveSegments": live.as_ref().map(|live| &live.finals),
            "liveComplete": live.map(|live| live.complete),
        }),
    )
}

/// Ends a session: saves its audio, merges and normalizes it, and tells the listeners. Blocks on
/// the writers, ffmpeg and the live client's last finals, so it runs off the async runtime.
fn finish_recording(app_handle: &AppHandle, recording: ActiveRecording) {
    let (event, payload) = finished_event(recording, &get_vibe_temp_folder());
    tracing::debug!("Emitting {event} event");
    app_handle.emit(event, payload).map_err(|e| eyre!("{e:?}")).log_error();
    crate::meeting_prompt::recording_stopped(app_handle);
}

/// Closes a session's captures, collects its live finals, finalizes the captures into `out_dir`,
/// then builds the event that tells the listeners.
fn finished_event(recording: ActiveRecording, out_dir: &Path) -> (&'static str, Value) {
    let ActiveRecording {
        session_id,
        origin,
        recording_name,
        streams,
        writers,
        wav_paths,
        live,
        failed_devices,
        paused: _,
        started_at,
    } = recording;
    let closed = close_captures(streams, writers, wav_paths);
    // The writers are done, so the tap has all the audio: the live client sends the rest. Its last
    // finals are awaited before the captures are finalized, which deletes them: until then they sit
    // in the in-progress folder, where a quit during the wait leaves them for the next launch.
    let live = live.map(LiveClient::stop).map(StoppingLive::finals);
    let wav_paths = closed.files;
    // What cut a device or the recording short rides on it as a warning: the saved audio still
    // becomes a project.
    let device_disconnected = !failed_devices.is_empty();
    let device_warnings = failed_devices.into_iter().map(|(_, warning)| warning);
    let mut warnings: Vec<String> = device_warnings.chain(closed.failures).collect();
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
    let output = finalize_captures(&wav_paths, out_dir, &recording_stem, &mut warnings);
    if output.is_none() {
        tracing::error!("Recording stopped without any captured audio");
    }
    let (event, mut payload) = recording_event(&session_id, origin, output.as_deref(), warnings, device_disconnected, live);
    if event == "record_finish" {
        let started_ms = started_at.duration_since(std::time::UNIX_EPOCH).map(|since| since.as_millis() as u64).unwrap_or(0);
        payload["startedAt"] = json!(started_ms);
    }
    (event, payload)
}

fn get_output_device_and_config(host: &cpal::Host, audio_device: &AudioDevice) -> Result<(Device, SupportedStreamConfig)> {
    // On macOS, use the default output device directly — cpal's loopback support
    // requires this path to build an input stream from an output device.
    #[cfg(target_os = "macos")]
    let device = host
        .default_output_device()
        .ok_or_else(|| DeviceUnavailable(audio_device.name.clone()))?;

    #[cfg(not(target_os = "macos"))]
    let device = find_device(host, audio_device)?;

    let config = device
        .default_output_config()
        .context("Failed to get default output config")?;
    Ok((device, config))
}

#[cfg(test)]
mod tests;
