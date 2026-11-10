use std::time::{SystemTime, UNIX_EPOCH};

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;

use crate::server::{
    error, AppState, ModelInfo, ModelListResponse, ModelLoadRequest, ModelMetadataRequest, ModelMetadataResponse,
    ModelStatusResponse,
};

#[utoipa::path(
    post,
    path = "/v1/models/metadata",
    request_body = ModelMetadataRequest,
    responses(
        (status = 200, description = "Model metadata", body = ModelMetadataResponse),
        (status = 400, description = "Unsupported or invalid model", body = crate::server::ErrorResponse)
    )
)]
pub(in crate::server) async fn model_metadata(Json(request): Json<ModelMetadataRequest>) -> Response {
    if request.path.trim().is_empty() {
        return error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "request body must contain a model path",
        );
    }
    let path = request.path.clone();
    match tokio::task::spawn_blocking(move || crate::engine::metadata(&path)).await {
        Ok(Ok(capabilities)) => (
            StatusCode::OK,
            Json(ModelMetadataResponse {
                format: if capabilities.engine == "whisper" { "whisper" } else { "gguf" },
                capabilities,
            }),
        )
            .into_response(),
        Ok(Err(message)) => error(StatusCode::BAD_REQUEST, "unsupported_model", &message),
        Err(err) => error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            &format!("metadata task failed: {err}"),
        ),
    }
}

#[utoipa::path(
    post,
    path = "/v1/models/load",
    request_body = ModelLoadRequest,
    responses(
        (status = 200, description = "Model loaded", body = ModelStatusResponse),
        (status = 400, description = "Invalid request", body = crate::server::ErrorResponse),
        (status = 409, description = "A live session holds the model", body = crate::server::ErrorResponse),
        (status = 500, description = "Load failed", body = crate::server::ErrorResponse)
    )
)]
pub(in crate::server) async fn load_model(State(state): State<AppState>, Json(request): Json<ModelLoadRequest>) -> Response {
    if request.path.trim().is_empty() {
        return error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "request body must contain {\"path\": \"...\"}",
        );
    }

    if let Some(response) = live_busy(&state).await {
        return response;
    }
    let mut model = state.unload_timeout.acquire(state.inner.clone()).await;
    let gpu_device = request.gpu_device.unwrap_or(-1);
    match model.load_model(&request.path, gpu_device, request.no_gpu) {
        Ok(()) => (
            StatusCode::OK,
            Json(ModelStatusResponse {
                status: "loaded",
                model: model.model_name.clone(),
            }),
        )
            .into_response(),
        Err(err) => error(
            StatusCode::INTERNAL_SERVER_ERROR,
            crate::server::engine_error_code(&err),
            &format!("failed to load model: {err}"),
        ),
    }
}

#[utoipa::path(
    delete,
    path = "/v1/models",
    responses(
        (status = 200, description = "Model unloaded", body = serde_json::Value),
        (status = 409, description = "A live session holds the model", body = crate::server::ErrorResponse)
    )
)]
pub(in crate::server) async fn unload_model(State(state): State<AppState>) -> Response {
    if let Some(response) = live_busy(&state).await {
        return response;
    }
    state.inner.lock().await.unload_model();
    (StatusCode::OK, Json(serde_json::json!({ "status": "unloaded" }))).into_response()
}

/// A live session holds the model until the recording stops, so waiting for it would hang. Checked
/// and released before the model is awaited: the slot is never held while waiting on the model.
async fn live_busy(state: &AppState) -> Option<Response> {
    let running = state.live.lock().await.is_some();
    running.then(|| error(StatusCode::CONFLICT, "live_busy", "a live session holds the model"))
}

#[utoipa::path(
    get,
    path = "/v1/models",
    responses(
        (status = 200, description = "OpenAI-style model list", body = ModelListResponse),
        (status = 429, description = "The model is in use", body = crate::server::ErrorResponse)
    )
)]
pub(in crate::server) async fn list_models(State(state): State<AppState>) -> Response {
    let Ok(state) = state.inner.try_lock() else {
        return error(StatusCode::TOO_MANY_REQUESTS, "busy", "the model is in use");
    };
    let data = if let Some(engine) = state.ctx.as_ref() {
        vec![ModelInfo {
            id: state.model_name.clone(),
            object: "model",
            created: now_unix(),
            owned_by: "local",
            capabilities: engine.capabilities(),
        }]
    } else {
        Vec::new()
    };
    (StatusCode::OK, Json(ModelListResponse { object: "list", data })).into_response()
}

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as i64)
        .unwrap_or_default()
}
