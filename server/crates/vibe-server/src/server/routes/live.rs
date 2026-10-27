//! Live transcription over HTTP: start a session, push audio to it while recording, stop it.
//! One session at a time, and it holds the model (its `ModelLease`) from start to stop.
//!
//! Lock order: the live slot, then the model, and the model is only ever *tried* while the slot is
//! held. The other way round is real: a push worker holds the model (inside its session) while it
//! waits for the slot to take the session back, so awaiting the model under the slot would deadlock.

use std::panic::{self, AssertUnwindSafe};
use std::sync::Arc;
use std::time::Duration;

use anyhow::Context as _;
use axum::body::Body;
use axum::extract::rejection::JsonRejection;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use tokio::time::Instant;
use vad_rs::SpeechSegment;
use whisper_rs::{Segment, TranscribeOptions};

use crate::server::live::{LiveCore, LiveEvent, IDLE_TTL};
use crate::server::unload_timeout::ModelLease;
use crate::server::{error, AppState};

/// 30 s of 16 kHz f32 samples.
const MAX_PUSH_BYTES: usize = 30 * vad_rs::SAMPLE_RATE * 4;
const REAP_INTERVAL: Duration = Duration::from_secs(5);

pub(in crate::server) type SharedLiveSlot = Arc<Mutex<Option<LiveSlot>>>;

pub(in crate::server) struct LiveSlot {
    id: String,
    /// `None` while a push has the session out.
    session: Option<LiveSession>,
}

type Vad = Box<dyn FnMut(&[f32]) -> anyhow::Result<Vec<SpeechSegment>> + Send>;

pub(in crate::server) struct LiveSession {
    core: LiveCore,
    /// Held for the whole session, so no file transcription or model change cuts in.
    model: ModelLease,
    vad: Vad,
    options: TranscribeOptions,
    last_seen: Instant,
}

impl LiveSession {
    fn advance(&mut self) -> anyhow::Result<Vec<LiveEvent>> {
        let Self {
            core,
            model,
            vad,
            options,
            ..
        } = self;
        core.advance(std::time::Instant::now(), vad, engine(model, options))
    }

    /// Consumes the session: the model is released when this returns.
    fn finish(self) -> anyhow::Result<Vec<LiveEvent>> {
        let Self {
            core,
            mut model,
            vad,
            options,
            ..
        } = self;
        core.finish(vad, engine(&mut model, &options))
    }
}

fn engine<'a>(
    model: &'a mut ModelLease,
    options: &'a TranscribeOptions,
) -> impl FnMut(&[f32], Option<&str>) -> anyhow::Result<Vec<Segment>> + 'a {
    move |samples, prompt| {
        let engine = model.ctx.as_mut().context("no model loaded")?;
        let options = TranscribeOptions {
            prompt: prompt.map(str::to_owned),
            ..options.clone()
        };
        Ok(engine.transcribe(samples, options)?.segments)
    }
}

#[derive(Debug, Deserialize, utoipa::ToSchema)]
pub(in crate::server) struct LiveStartRequest {
    /// Fixed for the session: detection on short utterances flips between languages.
    language: String,
    /// Silero VAD model; it is what cuts the audio into utterances.
    vad_model: String,
    /// Opening context for Whisper's prompt; engines without text prompts ignore it.
    prompt: Option<String>,
    n_threads: Option<i32>,
    /// Partials on (the default) or off.
    partials: Option<bool>,
}

#[derive(Debug, Serialize, utoipa::ToSchema)]
pub(in crate::server) struct LiveStartResponse {
    id: String,
    sample_rate: u32,
}

#[derive(Debug, Serialize, utoipa::ToSchema)]
pub(in crate::server) struct LiveUpdate {
    events: Vec<LiveEvent>,
    /// Audio received and not yet covered by a final.
    lag_ms: u64,
}

#[utoipa::path(
    post,
    path = "/v1/live",
    request_body = LiveStartRequest,
    responses(
        (status = 200, description = "Session started; push audio to it", body = LiveStartResponse),
        (status = 400, description = "Invalid request or VAD model", body = crate::server::ErrorResponse),
        (status = 409, description = "A live session is already running", body = crate::server::ErrorResponse),
        (status = 429, description = "The model is busy with another transcription", body = crate::server::ErrorResponse),
        (status = 503, description = "No model loaded", body = crate::server::ErrorResponse)
    )
)]
pub(in crate::server) async fn start_live(
    State(state): State<AppState>,
    request: Result<Json<LiveStartRequest>, JsonRejection>,
) -> Response {
    let request = match request {
        Ok(Json(request)) => request,
        Err(rejection) => return error(StatusCode::BAD_REQUEST, "invalid_request", &rejection.body_text()),
    };
    if request.language.trim().is_empty() || request.vad_model.trim().is_empty() {
        return error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "'language' and 'vad_model' are required",
        );
    }

    let mut slot = state.live.lock().await;
    if slot.is_some() {
        return error(StatusCode::CONFLICT, "live_busy", "a live session is already running");
    }
    let Ok(model) = state.unload_timeout.try_acquire(Arc::clone(&state.inner)) else {
        return error(
            StatusCode::TOO_MANY_REQUESTS,
            "busy",
            "server is busy with another transcription",
        );
    };
    let Some(engine) = model.ctx.as_ref() else {
        return error(StatusCode::SERVICE_UNAVAILABLE, "no_model", "no model loaded");
    };
    let text_prompts = engine.capabilities().text_prompts;
    let mut vad = match vad_rs::Vad::new(&request.vad_model, vad_rs::Options::default()) {
        Ok(vad) => vad,
        Err(err) => {
            return error(
                StatusCode::BAD_REQUEST,
                "invalid_request",
                &format!("cannot load the VAD model: {err}"),
            )
        }
    };

    let id = session_id();
    *slot = Some(LiveSlot {
        id: id.clone(),
        session: Some(LiveSession {
            core: LiveCore::new(request.prompt, text_prompts, request.partials.unwrap_or(true)),
            model,
            vad: Box::new(move |samples| Ok(vad.speech(samples)?)),
            options: TranscribeOptions {
                language: Some(request.language),
                threads: request.n_threads.unwrap_or(0),
                verbose: state.config.verbose(),
                vad_model_path: Some(request.vad_model),
                ..TranscribeOptions::default()
            },
            last_seen: Instant::now(),
        }),
    });
    Json(LiveStartResponse {
        id,
        sample_rate: vad_rs::SAMPLE_RATE as u32,
    })
    .into_response()
}

#[utoipa::path(
    post,
    path = "/v1/live/{id}/audio",
    params(("id" = String, Path, description = "Live session id")),
    request_body(
        content = Vec<u8>,
        description = "f32 little-endian mono PCM at 16 kHz, at most 30 s",
        content_type = "application/octet-stream"
    ),
    responses(
        (status = 200, description = "Finals and partial since the last push", body = LiveUpdate),
        (status = 400, description = "Not whole f32 samples, or over 30 s", body = crate::server::ErrorResponse),
        (status = 404, description = "No such session", body = crate::server::ErrorResponse),
        (status = 409, description = "A push to this session is still running", body = crate::server::ErrorResponse)
    )
)]
pub(in crate::server) async fn push_audio(State(state): State<AppState>, Path(id): Path<String>, body: Body) -> Response {
    // Read under its own cap, not the router's 15 GB upload limit.
    let bytes = match axum::body::to_bytes(body, MAX_PUSH_BYTES).await {
        Ok(bytes) => bytes,
        Err(err) => {
            return error(
                StatusCode::BAD_REQUEST,
                "invalid_request",
                &format!("cannot read the audio (at most 30 s, {MAX_PUSH_BYTES} bytes, per push): {err}"),
            )
        }
    };
    if bytes.len() % 4 != 0 {
        return error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "the body must be f32 samples: a multiple of 4 bytes",
        );
    }
    let pcm: Vec<f32> = bytes
        .as_chunks::<4>()
        .0
        .iter()
        .map(|sample| f32::from_le_bytes(*sample))
        .collect();

    let mut session = {
        let mut slot = state.live.lock().await;
        let Some(slot) = slot.as_mut().filter(|slot| slot.id == id) else {
            return error(StatusCode::NOT_FOUND, "not_found", "no such live session");
        };
        let Some(session) = slot.session.take() else {
            return error(StatusCode::CONFLICT, "live_busy", "a push to this session is still running");
        };
        session
    };

    // The worker, not this handler, settles the slot: a client that hangs up mid-push drops the
    // handler, and the session must still go back, or be cleared if the push panicked.
    let live = Arc::clone(&state.live);
    let worker = tokio::task::spawn_blocking(move || {
        let advanced = panic::catch_unwind(AssertUnwindSafe(|| {
            session.core.push(&pcm);
            session.advance()
        }));
        let Ok(events) = advanced else {
            drop(session); // releases the model before the slot says it is free
            let mut slot = live.blocking_lock();
            if slot.as_ref().is_some_and(|slot| slot.id == id && slot.session.is_none()) {
                *slot = None;
            }
            anyhow::bail!("live transcription panicked; the session is closed");
        };
        let lag_ms = session.core.lag_ms();
        session.last_seen = Instant::now();
        if let Some(slot) = live.blocking_lock().as_mut().filter(|slot| slot.id == id) {
            slot.session = Some(session);
        }
        Ok((events?, lag_ms))
    });
    match worker.await.map_err(anyhow::Error::from).and_then(|update| update) {
        Ok((events, lag_ms)) => Json(LiveUpdate { events, lag_ms }).into_response(),
        Err(err) => error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            &format!("live transcription failed: {err:#}"),
        ),
    }
}

#[utoipa::path(
    delete,
    path = "/v1/live/{id}",
    params(("id" = String, Path, description = "Live session id")),
    responses(
        (status = 200, description = "The remaining speech as finals; the model is released", body = LiveUpdate),
        (status = 404, description = "No such session", body = crate::server::ErrorResponse),
        (status = 409, description = "A push to this session is still running", body = crate::server::ErrorResponse)
    )
)]
pub(in crate::server) async fn stop_live(State(state): State<AppState>, Path(id): Path<String>) -> Response {
    let session = {
        let mut slot = state.live.lock().await;
        let Some(running) = slot.as_mut().filter(|slot| slot.id == id) else {
            return error(StatusCode::NOT_FOUND, "not_found", "no such live session");
        };
        let Some(session) = running.session.take() else {
            return error(StatusCode::CONFLICT, "live_busy", "a push to this session is still running");
        };
        *slot = None;
        session
    };
    let finished = tokio::task::spawn_blocking(move || session.finish()).await;
    match finished.map_err(anyhow::Error::from).and_then(|events| events) {
        Ok(events) => Json(LiveUpdate { events, lag_ms: 0 }).into_response(),
        Err(err) => error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            &format!("live transcription failed: {err:#}"),
        ),
    }
}

/// Drops a session no push has reached for `IDLE_TTL`, so a desktop that died mid-recording does
/// not hold the model forever. A session out on a push is busy, not idle.
pub(in crate::server) async fn reap_idle(live: SharedLiveSlot) {
    loop {
        tokio::time::sleep(REAP_INTERVAL).await;
        let mut slot = live.lock().await;
        let idle = slot
            .as_ref()
            .and_then(|slot| slot.session.as_ref())
            .is_some_and(|session| session.last_seen.elapsed() >= IDLE_TTL);
        if idle {
            tracing::warn!("live session idle for {}s; releasing the model", IDLE_TTL.as_secs());
            *slot = None;
        }
    }
}

/// 128 bits from the standard library's randomly keyed hasher: an id without a `uuid` dependency.
fn session_id() -> String {
    use std::hash::{BuildHasher, RandomState};
    let half = || RandomState::new().hash_one(std::time::SystemTime::now());
    format!("{:016x}{:016x}", half(), half())
}

#[cfg(test)]
mod tests {
    use std::future::Future;

    use axum::body::to_bytes;
    use axum::http::{header, Request};
    use serde_json::{json, Value};
    use tower::ServiceExt;

    use super::*;
    use crate::cli::AppConfig;
    use crate::server::unload_timeout::UnloadTimeoutRuntime;
    use crate::server::{router, ServerState};

    const OCTETS: &str = "application/octet-stream";

    fn state() -> AppState {
        let inner = Arc::new(Mutex::new(ServerState::new()));
        AppState {
            unload_timeout: UnloadTimeoutRuntime::start("0".parse().unwrap(), Arc::clone(&inner)),
            inner,
            config: AppConfig::default(),
            token: None,
            live: SharedLiveSlot::default(),
        }
    }

    /// A running session on the (empty) model whose VAD never hears speech, so no engine is needed.
    async fn start_fake_session(state: &AppState) -> String {
        start_session_with(state, Box::new(|_| Ok(Vec::new()))).await
    }

    async fn start_session_with(state: &AppState, vad: Vad) -> String {
        let model = state
            .unload_timeout
            .try_acquire(Arc::clone(&state.inner))
            .expect("the model is free");
        let session = LiveSession {
            core: LiveCore::new(None, false, true),
            model,
            vad,
            options: TranscribeOptions::default(),
            last_seen: Instant::now(),
        };
        *state.live.lock().await = Some(LiveSlot {
            id: "session".into(),
            session: Some(session),
        });
        "session".into()
    }

    async fn send(state: &AppState, method: &str, path: &str, content_type: &str, body: Vec<u8>) -> (StatusCode, Value) {
        let request = Request::builder()
            .method(method)
            .uri(path)
            .header(header::CONTENT_TYPE, content_type)
            .body(Body::from(body))
            .unwrap();
        let response = router(state.clone()).oneshot(request).await.unwrap();
        let status = response.status();
        let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
        (status, serde_json::from_slice(&body).unwrap_or_default())
    }

    /// Fails instead of hanging when a route waits on a model someone holds.
    async fn promptly<T>(response: impl Future<Output = T>) -> T {
        tokio::time::timeout(Duration::from_secs(5), response)
            .await
            .expect("the route waited for the model")
    }

    fn code(json: &Value) -> &str {
        json["error"]["code"].as_str().unwrap_or_default()
    }

    async fn start(state: &AppState, body: Value) -> (StatusCode, Value) {
        send(state, "POST", "/v1/live", "application/json", body.to_string().into_bytes()).await
    }

    fn start_body() -> Value {
        json!({ "language": "he", "vad_model": "/no/such/vad.bin" })
    }

    /// A transcription request without a file: 429 while someone holds the model, 400 once it is free.
    async fn transcription_status(state: &AppState) -> StatusCode {
        let form = b"--x--\r\n".to_vec();
        send(
            state,
            "POST",
            "/v1/audio/transcriptions",
            "multipart/form-data; boundary=x",
            form,
        )
        .await
        .0
    }

    fn pcm(samples: usize) -> Vec<u8> {
        vec![0.0f32; samples].iter().flat_map(|sample| sample.to_le_bytes()).collect()
    }

    #[tokio::test]
    async fn second_live_session_is_409() {
        let state = state();
        start_fake_session(&state).await;
        let (status, json) = start(&state, start_body()).await;
        assert_eq!((status, code(&json)), (StatusCode::CONFLICT, "live_busy"));
    }

    #[tokio::test(start_paused = true)]
    async fn idle_session_released_after_ttl() {
        let state = state();
        start_fake_session(&state).await;
        tokio::spawn(reap_idle(Arc::clone(&state.live)));
        assert_eq!(transcription_status(&state).await, StatusCode::TOO_MANY_REQUESTS);

        tokio::time::advance(Duration::from_secs(25)).await;
        tokio::task::yield_now().await;
        assert_eq!(
            transcription_status(&state).await,
            StatusCode::TOO_MANY_REQUESTS,
            "released before the TTL"
        );

        tokio::time::advance(Duration::from_secs(6)).await;
        tokio::task::yield_now().await;
        assert!(state.live.lock().await.is_none());
        assert_ne!(transcription_status(&state).await, StatusCode::TOO_MANY_REQUESTS);
    }

    #[tokio::test]
    async fn live_start_needs_a_vad_model_and_a_free_loaded_model() {
        let state = state();
        for body in [json!({ "language": "he" }), json!({ "language": "he", "vad_model": " " })] {
            let (status, json) = start(&state, body).await;
            assert_eq!((status, code(&json)), (StatusCode::BAD_REQUEST, "invalid_request"));
        }
        let (status, json) = start(&state, start_body()).await;
        assert_eq!((status, code(&json)), (StatusCode::SERVICE_UNAVAILABLE, "no_model"));

        let _file = state.unload_timeout.try_acquire(Arc::clone(&state.inner)).unwrap();
        let (status, json) = start(&state, start_body()).await;
        assert_eq!((status, code(&json)), (StatusCode::TOO_MANY_REQUESTS, "busy"));
        assert!(state.live.lock().await.is_none());
    }

    #[tokio::test]
    async fn unknown_live_session_is_404() {
        let state = state();
        start_fake_session(&state).await;
        let (status, json) = send(&state, "POST", "/v1/live/other/audio", OCTETS, pcm(160)).await;
        assert_eq!((status, code(&json)), (StatusCode::NOT_FOUND, "not_found"));
        let (status, json) = send(&state, "DELETE", "/v1/live/other", OCTETS, Vec::new()).await;
        assert_eq!((status, code(&json)), (StatusCode::NOT_FOUND, "not_found"));
    }

    #[tokio::test]
    async fn push_must_be_whole_samples_and_at_most_30s() {
        let state = state();
        let id = start_fake_session(&state).await;
        let path = format!("/v1/live/{id}/audio");
        for body in [vec![0u8; 5], vec![0u8; MAX_PUSH_BYTES + 4]] {
            let (status, json) = send(&state, "POST", &path, OCTETS, body).await;
            assert_eq!((status, code(&json)), (StatusCode::BAD_REQUEST, "invalid_request"));
        }
        let (status, json) = send(&state, "POST", &path, OCTETS, pcm(30 * vad_rs::SAMPLE_RATE)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(json, json!({ "events": [], "lag_ms": 0 }), "silence leaves no text behind");
    }

    #[tokio::test]
    async fn push_answers_with_an_update_and_delete_releases_the_model() {
        let state = state();
        let id = start_fake_session(&state).await;
        let (status, json) = send(&state, "POST", &format!("/v1/live/{id}/audio"), OCTETS, pcm(8_000)).await;
        assert_eq!((status, json), (StatusCode::OK, json!({ "events": [], "lag_ms": 0 })));
        assert_eq!(transcription_status(&state).await, StatusCode::TOO_MANY_REQUESTS);

        let (status, json) = send(&state, "DELETE", &format!("/v1/live/{id}"), OCTETS, Vec::new()).await;
        assert_eq!((status, json), (StatusCode::OK, json!({ "events": [], "lag_ms": 0 })));
        assert!(state.live.lock().await.is_none());
        assert_ne!(transcription_status(&state).await, StatusCode::TOO_MANY_REQUESTS);
    }

    #[tokio::test]
    async fn a_push_in_flight_makes_the_session_busy() {
        let state = state();
        let id = start_fake_session(&state).await;
        // What a push does while it runs: the session is out of its slot.
        let session = state.live.lock().await.as_mut().and_then(|slot| slot.session.take());
        let (status, json) = send(&state, "POST", &format!("/v1/live/{id}/audio"), OCTETS, pcm(160)).await;
        assert_eq!((status, code(&json)), (StatusCode::CONFLICT, "live_busy"));
        let (status, json) = send(&state, "DELETE", &format!("/v1/live/{id}"), OCTETS, Vec::new()).await;
        assert_eq!((status, code(&json)), (StatusCode::CONFLICT, "live_busy"));
        drop(session);
    }

    #[tokio::test]
    async fn a_push_that_panics_after_its_client_hung_up_frees_the_slot_and_the_model() {
        let state = state();
        let (started, worker_started) = tokio::sync::oneshot::channel();
        let (release, released) = std::sync::mpsc::channel::<()>();
        let mut started = Some(started);
        let vad = move |_: &[f32]| -> anyhow::Result<Vec<SpeechSegment>> {
            if let Some(started) = started.take() {
                let _ = started.send(());
            }
            let _ = released.recv();
            panic!("the VAD blew up");
        };
        let id = start_session_with(&state, Box::new(vad)).await;

        let push = {
            let state = state.clone();
            let path = format!("/v1/live/{id}/audio");
            tokio::spawn(async move { send(&state, "POST", &path, OCTETS, pcm(160)).await })
        };
        worker_started.await.unwrap();
        // The client hangs up while the worker has the session, then the worker panics.
        push.abort();
        let _ = push.await;
        release.send(()).unwrap();

        tokio::time::timeout(Duration::from_secs(5), async {
            while state.live.lock().await.is_some() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("the slot was never freed");
        // Past the slot (no 409) and past the lease (no 429): only the missing model stops it.
        let (status, json) = start(&state, start_body()).await;
        assert_eq!((status, code(&json)), (StatusCode::SERVICE_UNAVAILABLE, "no_model"));
        assert_ne!(transcription_status(&state).await, StatusCode::TOO_MANY_REQUESTS);
    }

    #[tokio::test]
    async fn ready_and_model_list_do_not_wait_for_a_held_model() {
        let state = state();
        let _held = state.unload_timeout.try_acquire(Arc::clone(&state.inner)).unwrap();
        let (status, json) = promptly(send(&state, "GET", "/ready", OCTETS, Vec::new())).await;
        assert_eq!((status, json), (StatusCode::OK, json!({ "status": "busy" })));
        let (status, json) = promptly(send(&state, "GET", "/v1/models", OCTETS, Vec::new())).await;
        assert_eq!((status, code(&json)), (StatusCode::TOO_MANY_REQUESTS, "busy"));
    }

    #[test]
    fn live_routes_are_in_the_api_docs() {
        use utoipa::OpenApi as _;
        let docs = crate::server::ApiDoc::openapi();
        for path in ["/v1/live", "/v1/live/{id}/audio", "/v1/live/{id}"] {
            assert!(docs.paths.paths.contains_key(path), "{path}");
        }
    }

    #[tokio::test]
    async fn model_load_and_unload_are_409_during_a_live_session() {
        let state = state();
        start_fake_session(&state).await;
        let load = json!({ "path": "/no/such/model.bin" }).to_string().into_bytes();
        let (status, json) = promptly(send(&state, "POST", "/v1/models/load", "application/json", load)).await;
        assert_eq!((status, code(&json)), (StatusCode::CONFLICT, "live_busy"));
        let (status, json) = promptly(send(&state, "DELETE", "/v1/models", OCTETS, Vec::new())).await;
        assert_eq!((status, code(&json)), (StatusCode::CONFLICT, "live_busy"));
    }
}
