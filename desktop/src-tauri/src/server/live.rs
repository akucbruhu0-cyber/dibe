//! The live transcription client: while a recording runs, the live tap's 16 kHz audio goes to a
//! vibe-server live session in pushes of at least 250 ms, and each push's answer reaches the UI as
//! `live_update`. The stop sends the rest, closes the session and hands its finals to
//! `record_finish`.
//!
//! Nothing here can fail or hold up the recording. Trouble is a `live_status` of `paused`, retried
//! with backoff while the audio waits in a backlog capped at a minute, and the stop waits for the
//! last finals for at most `STOP_WAIT`. A push is never timed out or abandoned: its answer carries
//! finals the server does not send twice. [`run_live`] is the whole client, with the server's
//! routes behind [`LiveApi`] so it is tested without one.

mod run;

use eyre::eyre;
use reqwest::header::CONTENT_TYPE;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::future::Future;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};
use tauri::async_runtime::JoinHandle;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::{mpsc, Notify};
use tokio_util::sync::CancellationToken;

use run::run_live;

use crate::error::LogError;
use crate::recording::live_tap::LiveTap;
use crate::recording::writer::LiveFeed;
use crate::setup::ServerState;
use crate::transcript::Segment;

/// How long a stopping recording waits for its last finals.
const STOP_WAIT: Duration = Duration::from_secs(30);
/// Chunks of 100 ms between the tap and the client: what the client may fall behind before the
/// tap drops audio. The client empties it even while a push runs.
const CHANNEL_CHUNKS: usize = 64;

/// What `start_record` takes to turn live transcription on.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveOptions {
    language: String,
    partials: bool,
    /// The Silero VAD model the server cuts the audio into utterances with.
    vad_model: String,
}

/// What the server says about the audio, in seconds. `run_live` moves the times onto the
/// recording's clock before anyone sees them.
#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum LiveEvent {
    Final {
        start: f64,
        end: f64,
        text: String,
    },
    /// Replaces the current partial; empty text clears it.
    Partial {
        start: f64,
        end: f64,
        text: String,
    },
    Warning {
        code: String,
        message: String,
    },
}

/// A push's answer, and `live_update`'s payload (with the recording's `sessionId`).
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct LiveUpdate {
    pub events: Vec<LiveEvent>,
    /// How far the text is behind the speech.
    pub lag_ms: u64,
}

/// A failed call: the HTTP status when the server answered, `None` when it could not be reached.
#[derive(Debug)]
pub struct ApiError {
    pub status: Option<u16>,
    pub message: String,
}

impl ApiError {
    fn unreachable(message: String) -> Self {
        Self { status: None, message }
    }

    /// The session is gone: reaped after a stall (404), or with a server that died.
    fn lost_session(&self) -> bool {
        matches!(self.status, None | Some(404))
    }

    /// The server never took the pushed audio, so it can be sent again.
    fn untaken(&self) -> bool {
        matches!(self.status, None | Some(404 | 409))
    }
}

/// vibe-server's live routes.
pub trait LiveApi {
    /// `POST /v1/live`: a new session's id.
    fn start(&self) -> impl Future<Output = Result<String, ApiError>> + Send;
    /// `POST /v1/live/{id}/audio`: 16 kHz mono samples, at most 30 s.
    fn push(&self, id: &str, pcm: &[f32]) -> impl Future<Output = Result<LiveUpdate, ApiError>> + Send;
    /// `DELETE /v1/live/{id}`: the finals left, and the model released.
    fn stop(&self, id: &str) -> impl Future<Output = Result<LiveUpdate, ApiError>> + Send;
}

/// The live routes of the server the app runs.
struct HttpLive {
    app: AppHandle,
    options: LiveOptions,
}

impl HttpLive {
    /// The running server's client (it carries the bearer token) and address, looked up on every
    /// call, so a server started or restarted during the recording is found.
    async fn endpoint(&self) -> Result<(reqwest::Client, String), ApiError> {
        let not_running = || ApiError::unreachable("The transcription server is not running".to_string());
        let state = self
            .app
            .try_state::<tokio::sync::Mutex<ServerState>>()
            .ok_or_else(not_running)?;
        let state = state.lock().await;
        let process = state.process.as_ref().ok_or_else(not_running)?;
        Ok((process.client(), process.base_url()))
    }
}

impl LiveApi for HttpLive {
    async fn start(&self) -> Result<String, ApiError> {
        #[derive(Deserialize)]
        struct Started {
            id: String,
        }
        let (client, base_url) = self.endpoint().await?;
        let body = json!({
            "language": self.options.language,
            "vad_model": self.options.vad_model,
            "partials": self.options.partials,
        });
        let started: Started = answer(client.post(format!("{base_url}/v1/live")).json(&body).send().await).await?;
        Ok(started.id)
    }

    async fn push(&self, id: &str, pcm: &[f32]) -> Result<LiveUpdate, ApiError> {
        let (client, base_url) = self.endpoint().await?;
        let body: Vec<u8> = pcm.iter().flat_map(|sample| sample.to_le_bytes()).collect();
        let request = client
            .post(format!("{base_url}/v1/live/{id}/audio"))
            .header(CONTENT_TYPE, "application/octet-stream")
            .body(body);
        answer(request.send().await).await
    }

    async fn stop(&self, id: &str) -> Result<LiveUpdate, ApiError> {
        let (client, base_url) = self.endpoint().await?;
        answer(client.delete(format!("{base_url}/v1/live/{id}")).send().await).await
    }
}

/// The server's answer as `T`, or why there is none.
async fn answer<T: DeserializeOwned>(sent: reqwest::Result<reqwest::Response>) -> Result<T, ApiError> {
    let response = sent.map_err(|error| ApiError::unreachable(format!("Cannot reach the transcription server: {error}")))?;
    let status = response.status();
    if !status.is_success() {
        let body = response.text().await.unwrap_or_default();
        let message = serde_json::from_str::<super::ServerErrorResponse>(&body)
            .map(|parsed| parsed.error.message)
            .unwrap_or(body);
        return Err(ApiError {
            status: Some(status.as_u16()),
            message,
        });
    }
    response.json().await.map_err(|error| ApiError {
        status: Some(status.as_u16()),
        message: format!("The transcription server's answer is unreadable: {error}"),
    })
}

/// One recording's live client: the tap its writers feed, and the task that sends it on.
pub struct LiveClient {
    tap: Arc<Mutex<LiveTap>>,
    cancel: CancellationToken,
    wake: Arc<Notify>,
    /// Every final so far, shared with the task so a stop that stops waiting still has them.
    collected: Arc<Mutex<Vec<Segment>>>,
    task: JoinHandle<Vec<Segment>>,
}

impl LiveClient {
    /// Starts the client of recording `session_id`, whose devices deliver `formats` (rate,
    /// channels), in device order. Never fails: the task finds the server and opens the session,
    /// and reports any trouble as `live_status`.
    pub fn start(app: &AppHandle, session_id: &str, options: LiveOptions, formats: &[(u32, u16)]) -> Self {
        let api = HttpLive {
            app: app.clone(),
            options,
        };
        let (app, session_id) = (app.clone(), session_id.to_string());
        let emit = move |event: &str, mut payload: Value| {
            if let Some(fields) = payload.as_object_mut() {
                fields.insert("sessionId".to_string(), session_id.clone().into());
            }
            app.emit_to("main", event, payload).map_err(|e| eyre!("{e:?}")).log_error();
        };
        let (tx, rx) = mpsc::channel(CHANNEL_CHUNKS);
        let tap = Arc::new(Mutex::new(LiveTap::new(formats, tx)));
        let cancel = CancellationToken::new();
        let wake = Arc::new(Notify::new());
        let collected = Arc::new(Mutex::new(Vec::new()));
        let task = tauri::async_runtime::spawn(run_live(
            api,
            rx,
            emit,
            cancel.clone(),
            Arc::clone(&wake),
            Arc::clone(&collected),
        ));
        Self {
            tap,
            cancel,
            wake,
            collected,
            task,
        }
    }

    /// Device `device`'s way into the tap, for its writer.
    pub fn feed(&self, device: usize) -> LiveFeed {
        (Arc::clone(&self.tap), device)
    }

    /// Cuts a paused client's wait for its next attempt short.
    pub fn retry(&self) {
        self.wake.notify_one();
    }

    /// Ends the client once the writers have stopped (their copies of the tap went with their
    /// threads): the tap sends its last few ms and closes the channel with this handle, then the
    /// token tells the task to send what is left and close the session. Waits for none of it.
    pub fn stop(self) -> StoppingLive {
        let deadline = Instant::now() + STOP_WAIT;
        {
            let mut tap = self.tap.lock().unwrap_or_else(PoisonError::into_inner);
            tap.flush();
            if tap.dropped_ms() > 0 {
                tracing::warn!(
                    "live transcription missed {} ms that came faster than it was sent",
                    tap.dropped_ms()
                );
            }
        }
        drop(self.tap);
        self.cancel.cancel();
        StoppingLive {
            task: self.task,
            collected: self.collected,
            deadline,
        }
    }
}

#[cfg(test)]
impl LiveClient {
    /// A client whose task is `task` and whose tap feeds nothing, for tests of a recording's stop.
    pub(crate) fn with_task(task: JoinHandle<Vec<Segment>>) -> Self {
        let (tx, _) = mpsc::channel(1);
        Self {
            tap: Arc::new(Mutex::new(LiveTap::new(&[], tx))),
            cancel: CancellationToken::new(),
            wake: Arc::new(Notify::new()),
            collected: Arc::default(),
            task,
        }
    }
}

/// A stopped client, sending its last audio.
pub struct StoppingLive {
    task: JoinHandle<Vec<Segment>>,
    collected: Arc<Mutex<Vec<Segment>>>,
    deadline: Instant,
}

impl StoppingLive {
    /// The recording's finals, in order: all of them once the task is done, or those it had
    /// `STOP_WAIT` after the stop. Blocks, so it runs off the async runtime, where recordings end.
    pub fn finals(self) -> Vec<Segment> {
        let Self {
            task,
            collected,
            deadline,
        } = self;
        let wait = deadline.saturating_duration_since(Instant::now());
        // The timer is made inside the runtime: built out here it would panic with no reactor.
        match tauri::async_runtime::block_on(async move { tokio::time::timeout(wait, task).await }) {
            Ok(Ok(finals)) => return finals,
            Ok(Err(error)) => tracing::error!("live transcription stopped unexpectedly: {error}"),
            Err(_) => tracing::warn!("live transcription was still sending after {STOP_WAIT:?}; keeping the finals so far"),
        }
        let finals = collected.lock().unwrap_or_else(PoisonError::into_inner).clone();
        finals
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn finals(text: &str) -> Vec<Segment> {
        vec![Segment {
            start: 0,
            stop: 30,
            text: text.to_string(),
            speaker: None,
        }]
    }

    #[test]
    fn stop_waits_for_the_task_then_keeps_the_finals_so_far() {
        let collected = Arc::new(Mutex::new(finals("so far")));
        let done = StoppingLive {
            task: tauri::async_runtime::spawn(async { finals("all") }),
            collected: Arc::clone(&collected),
            deadline: Instant::now() + Duration::from_secs(5),
        };
        assert_eq!(done.finals(), finals("all"));

        // Still sending at the deadline: the recording goes on with what the task collected.
        let late = StoppingLive {
            task: tauri::async_runtime::spawn(std::future::pending()),
            collected,
            deadline: Instant::now(),
        };
        assert_eq!(late.finals(), finals("so far"));
    }
}
