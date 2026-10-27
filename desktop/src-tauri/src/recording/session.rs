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

    #[test]
    fn begin_after_take_succeeds() {
        let state = RecordingState::default();
        let session_id = state.try_begin(RecordingOrigin::Shortcut).expect("begin");
        let taken = state.take(&session_id).expect("take");
        assert_eq!(taken.origin, RecordingOrigin::Shortcut);
        assert!(state.try_begin(RecordingOrigin::Dictation).is_ok());
    }
}
