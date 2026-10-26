use crate::error::LogError;
use crate::server::ServerEvent;
use crate::setup::ServerState;
use crate::transcript::{Segment, Transcript};
use eyre::Result;
use futures_util::{Stream, StreamExt};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Arc;
use tauri::{Emitter, EventId, Listener, State};
use tokio::sync::{Mutex, Notify};

use super::{ui::set_progress_bar, CommandError};

#[allow(dead_code)]
#[derive(Deserialize, Serialize, Clone)]
pub struct FfmpegOptions {
    pub normalize_loudness: bool,
    pub custom_command: Option<String>,
}

impl Default for FfmpegOptions {
    fn default() -> Self {
        Self {
            normalize_loudness: true,
            custom_command: None,
        }
    }
}

#[derive(Deserialize, Serialize, Clone, Debug)]
pub struct TranscribeOptions {
    pub path: String,
    pub lang: Option<String>,
    pub verbose: Option<bool>,
    pub n_threads: Option<i32>,
    pub init_prompt: Option<String>,
    pub temperature: Option<f32>,
    pub translate: Option<bool>,
    pub max_text_ctx: Option<i32>,
    pub word_timestamps: Option<bool>,
    pub max_sentence_len: Option<i32>,
    pub sampling_strategy: Option<String>,
    pub best_of: Option<i32>,
    pub beam_size: Option<i32>,
    pub diarize_model: Option<String>,
    pub stable_timestamps: Option<bool>,
    pub vad_model: Option<String>,
}

pub(crate) const SERVER_DIED: &str = "vibe-server process died during transcription";

/// Turn a transcription failure into something diagnosable: a send failure or a
/// mid-stream decode error is usually the sidecar dying, and only the child
/// itself knows the exit code, the signal, and what it printed on the way out.
async fn transcribe_error(server_state: &State<'_, Mutex<ServerState>>, error: eyre::Error) -> CommandError {
    if let Some(api_error) = error.downcast_ref::<crate::server::ServerApiError>() {
        return CommandError {
            code: api_error.code.clone(),
            message: api_error.message.clone(),
        };
    }
    match crate::server::death_report(server_state, SERVER_DIED).await {
        Some(message) => CommandError {
            code: "internal_error".to_string(),
            message,
        },
        None => {
            let mut command_error = CommandError::from(error);
            let stderr = crate::server::recent_stderr(server_state).await;
            if !stderr.is_empty() {
                command_error.message.push_str(&format!("\n\nvibe-server stderr: {stderr}"));
            }
            command_error
        }
    }
}

/// Releases the `abort_transcribe` listener on every way out of `transcribe`
/// (error, completion, abort, or the command future being dropped).
struct ListenGuard(tauri::AppHandle, EventId);

impl Drop for ListenGuard {
    fn drop(&mut self) {
        self.0.unlisten(self.1);
    }
}

enum Next<T> {
    Item(T),
    Aborted,
}

/// Next stream item, or `Aborted` as soon as `abort` is notified. Unlike checking
/// a flag between events, this also fires while the server is silently decoding
/// or diarizing and no event arrives. `None` means the stream ended.
async fn next_or_abort<S: Stream + Unpin>(stream: &mut S, abort: &Notify) -> Option<Next<S::Item>> {
    tokio::select! {
        biased;
        _ = abort.notified() => Some(Next::Aborted),
        item = stream.next() => item.map(Next::Item),
    }
}

#[tauri::command]
pub async fn transcribe(
    app_handle: tauri::AppHandle,
    options: TranscribeOptions,
    server_state: State<'_, Mutex<ServerState>>,
) -> Result<Transcript, CommandError> {
    // Validate file exists before attempting transcription
    let audio_path = PathBuf::from(&options.path);
    if !audio_path.exists() {
        return Err(CommandError {
            code: "invalid_request".to_string(),
            message: format!("Audio file not found: {}", options.path),
        });
    }
    if !audio_path.is_file() {
        return Err(CommandError {
            code: "invalid_request".to_string(),
            message: format!("Path is not a file: {}", options.path),
        });
    }

    let (client, base_url) = {
        let state = server_state.lock().await;
        let process = state.process.as_ref().ok_or_else(|| CommandError {
            code: "no_model".to_string(),
            message: "Please load model first".to_string(),
        })?;
        (process.client(), process.base_url())
    }; // lock released here, before any I/O

    // `notify_one` keeps a permit, so an abort that lands before we start waiting is not lost.
    let abort = Arc::new(Notify::new());
    let abort_c = abort.clone();

    let app_handle_c = app_handle.clone();
    let listener = app_handle.listen("abort_transcribe", move |_| {
        let _ = set_progress_bar(&app_handle_c, None);
        abort_c.notify_one();
    });
    let _listener = ListenGuard(app_handle.clone(), listener);

    let start = std::time::Instant::now();

    // The request (upload, then server-side decode and diarization until the first
    // response byte) can take minutes too; dropping it on abort closes the connection.
    let stream = tokio::select! {
        biased;
        _ = abort.notified() => {
            tracing::debug!("transcription aborted by user");
            let _ = set_progress_bar(&app_handle, None);
            return Ok(Transcript { processing_time_sec: start.elapsed().as_secs(), segments: Vec::new() });
        }
        result = crate::server::ServerProcess::transcribe_stream(&client, &base_url, &options) => match result {
            Ok(stream) => stream,
            Err(e) => return Err(transcribe_error(&server_state, e).await),
        },
    };

    tokio::pin!(stream);

    let mut segments = Vec::new();
    let mut completed = false;
    let mut aborted = false;

    while let Some(next) = next_or_abort(&mut stream, &abort).await {
        let event_result = match next {
            Next::Item(event_result) => event_result,
            Next::Aborted => {
                tracing::debug!("transcription aborted by user");
                aborted = true;
                break;
            }
        };

        match event_result {
            Ok(event) => match event {
                ServerEvent::Progress { progress } => {
                    let _ = set_progress_bar(&app_handle, Some(progress.into()));
                }
                ServerEvent::Segment {
                    start,
                    end,
                    text,
                    speaker,
                } => {
                    let segment = Segment {
                        start: (start * 100.0) as i64,
                        stop: (end * 100.0) as i64,
                        text,
                        speaker,
                    };
                    app_handle.emit_to("main", "new_segment", segment.clone()).log_error();
                    segments.push(segment);
                }
                ServerEvent::Result { .. } => {
                    tracing::debug!("transcription complete");
                    completed = true;
                }
                ServerEvent::Error { code, message } => {
                    tracing::error!("vibe-server transcription error: {}", message);
                    let _ = set_progress_bar(&app_handle, None);
                    return Err(CommandError {
                        code: code.unwrap_or_else(|| "internal_error".to_string()),
                        message,
                    });
                }
            },
            Err(e) => {
                tracing::error!("stream error: {:?}", e);
                let _ = set_progress_bar(&app_handle, None);
                return Err(transcribe_error(&server_state, e).await);
            }
        }
    }

    let _ = set_progress_bar(&app_handle, None);

    if !aborted && !completed {
        // A stream that just stops is almost always the sidecar dying under it;
        // say how it died rather than reporting a truncated stream.
        let message = crate::server::death_report(&server_state, SERVER_DIED)
            .await
            .unwrap_or_else(|| "vibe-server transcription stream ended before completion".to_string());
        return Err(CommandError {
            code: "internal_error".to_string(),
            message,
        });
    }

    let elapsed = start.elapsed();
    let transcript = Transcript {
        processing_time_sec: elapsed.as_secs(),
        segments,
    };

    Ok(transcript)
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::stream;
    use std::time::Duration;

    #[tokio::test]
    async fn abort_wins_over_pending_stream() {
        let abort = Notify::new();
        abort.notify_one();
        let mut stream = stream::pending::<u8>();
        let next = tokio::time::timeout(Duration::from_millis(50), next_or_abort(&mut stream, &abort)).await;
        assert!(matches!(next, Ok(Some(Next::Aborted))));
    }

    #[tokio::test]
    async fn item_is_returned_when_no_abort() {
        let abort = Notify::new();
        let mut stream = stream::iter([7u8]);
        let next = tokio::time::timeout(Duration::from_millis(50), next_or_abort(&mut stream, &abort)).await;
        assert!(matches!(next, Ok(Some(Next::Item(7)))));
        let end = next_or_abort(&mut stream, &abort).await;
        assert!(end.is_none());
    }
}
