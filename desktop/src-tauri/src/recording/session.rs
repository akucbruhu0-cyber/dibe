//! The single recording session the app can have at a time.
//!
//! Every `start_record` reserves the one slot here and gets a `session_id` back; `stop_record`
//! must present that id. A stop for another session (or for one that already finished) is a no-op,
//! so one caller can never end a recording that belongs to someone else.
use super::writer::WriterHandle;
use crate::server::live::LiveClient;
use cpal::Stream;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, PoisonError};

pub struct StreamHandle(pub Stream);
// A cpal stream is only ever played, paused and dropped by whoever owns the session.
unsafe impl Send for StreamHandle {}

/// Who started a recording. Rides along on `record_finish` / `record_error` so each listener
/// only reacts to the recordings it owns.
#[derive(Debug, Serialize, Deserialize, Clone, Copy, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum RecordingOrigin {
    Manual,
    Shortcut,
    Dictation,
    Meeting,
}

/// Returned by `try_begin` when a recording is already running.
#[derive(Debug, PartialEq)]
pub struct AlreadyRecording;

/// Everything `start_record` creates and `stop_record` needs to finish the recording.
pub struct ActiveRecording {
    pub session_id: String,
    pub origin: RecordingOrigin,
    pub recording_name: Option<String>,
    pub streams: Vec<StreamHandle>,
    pub writers: Vec<WriterHandle>,
    pub wav_paths: Vec<PathBuf>,
    /// Live transcription, when the recording asked for it.
    pub live: Option<LiveClient>,
    /// Devices whose stream stopped while the others went on, each with why; their files keep
    /// what they captured until then.
    pub failed_devices: Vec<(usize, String)>,
}

impl ActiveRecording {
    pub fn new(session_id: String, origin: RecordingOrigin, recording_name: Option<String>) -> Self {
        Self {
            session_id,
            origin,
            recording_name,
            streams: Vec::new(),
            writers: Vec::new(),
            wav_paths: Vec::new(),
            live: None,
            failed_devices: Vec::new(),
        }
    }
}

#[derive(Default)]
pub struct RecordingState(Mutex<Option<ActiveRecording>>);

impl RecordingState {
    /// The slot stays usable even if a thread panicked while holding it (release builds abort anyway).
    fn slot(&self) -> MutexGuard<'_, Option<ActiveRecording>> {
        self.0.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Reserves the slot for a new session. The streams are attached later with `install`, so a
    /// slow device never keeps the lock while a second caller is being turned away.
    pub fn try_begin(&self, origin: RecordingOrigin) -> Result<String, AlreadyRecording> {
        let mut slot = self.slot();
        if slot.is_some() {
            return Err(AlreadyRecording);
        }
        let session_id = uuid::Uuid::new_v4().to_string();
        *slot = Some(ActiveRecording::new(session_id.clone(), origin, None));
        Ok(session_id)
    }

    /// Hands the running streams to the slot `try_begin` reserved for the same session. Gives
    /// `recording` back when the session already ended (a device failed while starting).
    pub fn install(&self, recording: ActiveRecording) -> Result<(), Box<ActiveRecording>> {
        let mut slot = self.slot();
        match slot.as_mut().filter(|active| active.session_id == recording.session_id) {
            Some(active) => {
                *active = recording;
                Ok(())
            }
            None => {
                tracing::error!("recording session {} was released before it started", recording.session_id);
                Err(Box::new(recording))
            }
        }
    }

    /// Wakes the live client of `session_id` from its wait between attempts; nothing otherwise.
    pub fn retry_live(&self, session_id: &str) {
        let slot = self.slot();
        let active = slot.as_ref().filter(|active| active.session_id == session_id);
        if let Some(live) = active.and_then(|active| active.live.as_ref()) {
            live.retry();
        }
    }

    /// Notes that device `device` of `session_id` stopped delivering audio. The session goes on while
    /// another device records; once none does, it is released and returned, to be finished.
    pub fn device_failed(&self, session_id: &str, device: usize, warning: String) -> Option<ActiveRecording> {
        let mut slot = self.slot();
        let active = slot.as_mut().filter(|active| active.session_id == session_id)?;
        if !active.failed_devices.iter().any(|(failed, _)| *failed == device) {
            active.failed_devices.push((device, warning));
        }
        if active.failed_devices.len() < active.wav_paths.len() {
            return None;
        }
        slot.take()
    }

    /// Releases the slot and returns the session, or `None` when `session_id` is not the active one.
    pub fn take(&self, session_id: &str) -> Option<ActiveRecording> {
        let mut slot = self.slot();
        if slot.as_ref()?.session_id != session_id {
            return None;
        }
        slot.take()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn second_begin_is_rejected_while_active() {
        let state = RecordingState::default();
        state.try_begin(RecordingOrigin::Manual).expect("first begin");
        assert_eq!(state.try_begin(RecordingOrigin::Dictation).unwrap_err(), AlreadyRecording);
    }

    #[test]
    fn take_with_wrong_id_returns_none() {
        let state = RecordingState::default();
        let session_id = state.try_begin(RecordingOrigin::Manual).expect("begin");
        assert!(state.take("not-the-session").is_none());
        // The wrong id must not have released the real session.
        assert!(state.take(&session_id).is_some());
    }

    fn two_device_session(state: &RecordingState) -> String {
        let session_id = state.try_begin(RecordingOrigin::Meeting).expect("begin");
        let mut recording = ActiveRecording::new(session_id.clone(), RecordingOrigin::Meeting, None);
        recording.wav_paths = vec![PathBuf::from("mic.wav"), PathBuf::from("system.wav")];
        assert!(state.install(recording).is_ok());
        session_id
    }

    #[test]
    fn one_failed_device_keeps_the_session_recording() {
        let state = RecordingState::default();
        let session_id = two_device_session(&state);

        // AirPods switched to the call profile: the system audio keeps recording.
        assert!(state.device_failed(&session_id, 0, "mic stopped".to_string()).is_none());
        // cpal may report the same dead stream again; it is still one device.
        assert!(state.device_failed(&session_id, 0, "mic stopped again".to_string()).is_none());

        let recording = state.take(&session_id).expect("the session is still active");
        assert_eq!(recording.failed_devices, vec![(0, "mic stopped".to_string())]);
    }

    #[test]
    fn session_ends_when_every_device_failed() {
        let state = RecordingState::default();
        let session_id = two_device_session(&state);

        assert!(state.device_failed(&session_id, 1, "system stopped".to_string()).is_none());
        let recording = state
            .device_failed(&session_id, 0, "mic stopped".to_string())
            .expect("no device is left recording");

        assert_eq!(recording.failed_devices.len(), 2);
        assert!(state.take(&session_id).is_none(), "the slot is free again");
        assert!(state.device_failed("not-the-session", 0, String::new()).is_none());
    }

    #[test]
    fn begin_after_take_succeeds() {
        let state = RecordingState::default();
        let session_id = state.try_begin(RecordingOrigin::Shortcut).expect("begin");
        let taken = state.take(&session_id).expect("take");
        assert_eq!(taken.origin, RecordingOrigin::Shortcut);
        assert!(state.try_begin(RecordingOrigin::Dictation).is_ok());
    }
}
