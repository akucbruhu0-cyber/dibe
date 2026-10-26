use crate::error::LogError;
use crate::server::ServerEvent;
use crate::setup::ServerState;
use crate::transcript::{Segment, Transcript};
use eyre::Result;
use futures_util::{Stream, StreamExt};
use serde::{Deserialize, Serialize};
use std::future::Future;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
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

/// Payload of the `transcribe_warning` event: the transcription carries on, minus something asked for.
#[derive(Serialize, Clone, Debug)]
struct TranscribeWarning {
    code: String,
    message: String,
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

/// How often to ask a busy server again, and how long to keep asking.
const BUSY_RETRY_INTERVAL: Duration = Duration::from_millis(500);
const BUSY_RETRY_DEADLINE: Duration = Duration::from_secs(10 * 60);

/// Runs `attempt` until it stops failing with the server's "busy" error. The server
/// decodes and diarizes synchronously and cannot be interrupted, so right after a
/// cancel it is still busy with the cancelled file and rejects the next request.
/// `None` means `abort` fired, during an attempt or a wait; after `deadline` the
/// busy error is returned as is.
async fn retry_while_busy<F, Fut, T>(mut attempt: F, abort: &Notify, interval: Duration, deadline: Duration) -> Option<Result<T>>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<T>>,
{
    let give_up = tokio::time::Instant::now() + deadline;
    loop {
        let result = tokio::select! {
            biased;
            _ = abort.notified() => return None,
            result = attempt() => result,
        };
        match result {
            Err(error) if is_busy(&error) && tokio::time::Instant::now() < give_up => {
                tracing::debug!("vibe-server is busy, trying again in {interval:?}");
                tokio::select! {
                    biased;
                    _ = abort.notified() => return None,
                    _ = tokio::time::sleep(interval) => {}
                }
            }
            result => return Some(result),
        }
    }
}

fn is_busy(error: &eyre::Error) -> bool {
    error
        .downcast_ref::<crate::server::ServerApiError>()
        .is_some_and(|api_error| api_error.code == "busy")
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
    // The server may still be busy finishing a cancelled file, so "busy" is waited out.
    let attempt = || crate::server::ServerProcess::transcribe_stream(&client, &base_url, &options);
    let stream = match retry_while_busy(attempt, &abort, BUSY_RETRY_INTERVAL, BUSY_RETRY_DEADLINE).await {
        Some(Ok(stream)) => stream,
        Some(Err(e)) => return Err(transcribe_error(&server_state, e).await),
        None => {
            tracing::debug!("transcription aborted by user");
            let _ = set_progress_bar(&app_handle, None);
            return Ok(Transcript {
                processing_time_sec: start.elapsed().as_secs(),
                segments: Vec::new(),
            });
        }
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
                ServerEvent::Warning { code, message } => {
                    tracing::warn!("vibe-server transcription warning [{code}]: {message}");
                    app_handle
                        .emit_to("main", "transcribe_warning", TranscribeWarning { code, message })
                        .log_error();
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
    use crate::server::ServerApiError;
    use futures_util::stream;
    use std::cell::Cell;

    fn api_error(code: &str) -> eyre::Report {
        eyre::Report::new(ServerApiError {
            code: code.to_string(),
            message: "test".to_string(),
        })
    }

    fn error_code(error: &eyre::Report) -> Option<&str> {
        error.downcast_ref::<ServerApiError>().map(|e| e.code.as_str())
    }

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

    #[tokio::test]
    async fn busy_twice_then_ok_succeeds_after_two_retries() {
        let calls = Cell::new(0);
        let outcome = retry_while_busy(
            || {
                calls.set(calls.get() + 1);
                let n = calls.get();
                async move {
                    if n <= 2 {
                        Err(api_error("busy"))
                    } else {
                        Ok(7u8)
                    }
                }
            },
            &Notify::new(),
            Duration::from_millis(1),
            Duration::from_secs(5),
        )
        .await;
        assert!(matches!(outcome, Some(Ok(7))));
        assert_eq!(calls.get(), 3);
    }

    #[tokio::test]
    async fn abort_during_the_wait_returns_aborted_promptly() {
        let abort = Notify::new();
        let retry = retry_while_busy(
            || async { Err::<u8, _>(api_error("busy")) },
            &abort,
            Duration::from_secs(30),
            Duration::from_secs(60),
        );
        let abort_later = async {
            tokio::time::sleep(Duration::from_millis(10)).await;
            abort.notify_one();
        };
        let (outcome, ()) = tokio::join!(tokio::time::timeout(Duration::from_millis(500), retry), abort_later);
        assert!(matches!(outcome, Ok(None)));
    }

    #[tokio::test]
    async fn abort_during_a_pending_attempt_returns_aborted() {
        let abort = Notify::new();
        abort.notify_one();
        let retry = retry_while_busy(
            std::future::pending::<Result<u8>>,
            &abort,
            Duration::from_secs(30),
            Duration::from_secs(60),
        );
        let outcome = tokio::time::timeout(Duration::from_millis(50), retry).await;
        assert!(matches!(outcome, Ok(None)));
    }

    #[tokio::test]
    async fn other_errors_are_returned_without_retrying() {
        for error in [api_error("invalid_request"), eyre::eyre!("connection reset")] {
            let calls = Cell::new(0);
            let mut error = Some(error);
            let outcome = retry_while_busy(
                || {
                    calls.set(calls.get() + 1);
                    let error = error.take();
                    async move { Err::<u8, _>(error.unwrap_or_else(|| api_error("busy"))) }
                },
                &Notify::new(),
                Duration::from_millis(1),
                Duration::from_secs(5),
            )
            .await;
            assert!(matches!(outcome, Some(Err(_))));
            assert_eq!(calls.get(), 1);
        }
    }

    #[tokio::test]
    async fn past_the_deadline_the_busy_error_is_returned() {
        let calls = Cell::new(0);
        let outcome = retry_while_busy(
            || {
                calls.set(calls.get() + 1);
                async { Err::<u8, _>(api_error("busy")) }
            },
            &Notify::new(),
            Duration::from_millis(5),
            Duration::from_millis(40),
        )
        .await;
        match outcome {
            Some(Err(error)) => assert_eq!(error_code(&error), Some("busy")),
            _ => panic!("expected the busy error"),
        }
        assert!(calls.get() > 1);
    }
}
