//! Bearer-token gate for the local API.
//!
//! The server only listens on loopback, but any local process, and any web page that guesses the
//! port, can still reach it: a `multipart/form-data` POST is a "simple" cross-origin request, so a
//! browser sends it without a preflight. The token keeps both out; the `Origin` check is the cheap
//! second line, since a browser always sets that header and no command-line client does.

use axum::extract::{Request, State};
use axum::http::{header, Method, StatusCode};
use axum::middleware::Next;
use axum::response::Response;

use super::{error, AppState};

pub(super) async fn require_token(State(state): State<AppState>, request: Request, next: Next) -> Response {
    let Some(expected) = state.token.as_deref() else {
        return next.run(request).await;
    };
    if is_public(&request) {
        return next.run(request).await;
    }
    if request.headers().contains_key(header::ORIGIN) {
        return error(
            StatusCode::FORBIDDEN,
            "forbidden_origin",
            "requests from a browser origin are not allowed",
        );
    }
    let presented = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    match presented {
        Some(token) if constant_time_eq(token.as_bytes(), expected.as_bytes()) => next.run(request).await,
        _ => error(StatusCode::UNAUTHORIZED, "unauthorized", "missing or invalid bearer token"),
    }
}

/// What needs no token: the liveness probe, and the API documentation for a browser, which opens it
/// by navigation and cannot send a header. Reading the docs is all that is exempt: a POST to them
/// (Swagger's "Try it out" included) is gated like any other request.
fn is_public(request: &Request) -> bool {
    let path = request.uri().path();
    path == "/health"
        || (request.method() == Method::GET && (path == "/openapi.json" || path == "/docs" || path.starts_with("/docs/")))
}

/// Compares without stopping at the first differing byte, so response time does not tell a local
/// caller how much of a guess was right. The length is not hidden; every token has the same one.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |diff, (x, y)| diff | (x ^ y)) == 0
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use axum::body::{to_bytes, Body};
    use axum::http::{Request, StatusCode};
    use tokio::sync::Mutex;
    use tower::ServiceExt;

    use crate::cli::AppConfig;
    use crate::server::unload_timeout::UnloadTimeoutRuntime;
    use crate::server::{router, AppState, ServerState};

    use super::constant_time_eq;

    const TOKEN: &str = "0b8f5c1e-3f0a-4f6e-9f55-5a2d3a3c9e11";

    fn app(token: Option<&str>) -> axum::Router {
        let inner = Arc::new(Mutex::new(ServerState::new()));
        router(AppState {
            unload_timeout: UnloadTimeoutRuntime::start("0".parse().unwrap(), Arc::clone(&inner)),
            inner,
            config: AppConfig::default(),
            token: token.map(Arc::from),
            live: Default::default(),
        })
    }

    /// Status and error code (empty when the body carries none) of one GET.
    async fn call(app: axum::Router, path: &str, headers: &[(&str, &str)]) -> (StatusCode, String) {
        send(app, "GET", path, headers).await
    }

    async fn send(app: axum::Router, method: &str, path: &str, headers: &[(&str, &str)]) -> (StatusCode, String) {
        let mut request = Request::builder().method(method).uri(path);
        for (name, value) in headers {
            request = request.header(*name, *value);
        }
        let response = app.oneshot(request.body(Body::empty()).unwrap()).await.unwrap();
        let status = response.status();
        let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap_or_default();
        (status, json["error"]["code"].as_str().unwrap_or_default().to_string())
    }

    fn bearer(token: &str) -> String {
        format!("Bearer {token}")
    }

    #[tokio::test]
    async fn health_is_open() {
        let (status, _) = call(app(Some(TOKEN)), "/health", &[]).await;
        assert_eq!(status, StatusCode::OK);
    }

    #[tokio::test]
    async fn missing_token_is_401() {
        let (status, code) = call(app(Some(TOKEN)), "/ready", &[]).await;
        assert_eq!((status, code.as_str()), (StatusCode::UNAUTHORIZED, "unauthorized"));
    }

    #[tokio::test]
    async fn wrong_or_malformed_token_is_401() {
        for value in [
            bearer("not-the-token"),
            bearer(&TOKEN[..TOKEN.len() - 1]),
            TOKEN.to_string(),
            "Bearer".to_string(),
        ] {
            let (status, code) = call(app(Some(TOKEN)), "/ready", &[("authorization", &value)]).await;
            assert_eq!((status, code.as_str()), (StatusCode::UNAUTHORIZED, "unauthorized"), "{value}");
        }
    }

    #[tokio::test]
    async fn browser_origin_is_403() {
        let (status, code) = call(app(Some(TOKEN)), "/ready", &[("origin", "https://evil.example")]).await;
        assert_eq!((status, code.as_str()), (StatusCode::FORBIDDEN, "forbidden_origin"));
    }

    #[tokio::test]
    async fn valid_token_does_not_excuse_a_browser_origin() {
        let auth = bearer(TOKEN);
        let (status, code) = call(app(Some(TOKEN)), "/ready", &[("authorization", &auth), ("origin", "null")]).await;
        assert_eq!((status, code.as_str()), (StatusCode::FORBIDDEN, "forbidden_origin"));
    }

    #[tokio::test]
    async fn valid_token_reaches_route() {
        let auth = bearer(TOKEN);
        // `/ready` is 503 while no model is loaded: that is the handler answering, not the gate.
        let (status, code) = call(app(Some(TOKEN)), "/ready", &[("authorization", &auth)]).await;
        assert_eq!((status, code.as_str()), (StatusCode::SERVICE_UNAVAILABLE, ""));
    }

    // The Settings "Swagger docs" link opens /docs in a browser, which cannot send a bearer token.
    #[tokio::test]
    async fn docs_and_openapi_are_open_without_token() {
        for path in ["/openapi.json", "/docs/", "/docs/swagger-ui.css"] {
            let (status, code) = call(app(Some(TOKEN)), path, &[]).await;
            assert_eq!((status, code.as_str()), (StatusCode::OK, ""), "{path}");
        }
        // `/docs` itself answers with a redirect to `/docs/`, not with the gate's 401.
        let (status, _) = call(app(Some(TOKEN)), "/docs", &[]).await;
        assert!(status.is_redirection(), "{status}");
    }

    #[tokio::test]
    async fn post_to_docs_still_requires_token() {
        for path in ["/docs/", "/openapi.json"] {
            let (status, code) = send(app(Some(TOKEN)), "POST", path, &[]).await;
            assert_eq!((status, code.as_str()), (StatusCode::UNAUTHORIZED, "unauthorized"), "{path}");
            // What Swagger's "Try it out" sends from the page: a browser origin, so refused before the token matters.
            let (status, code) = send(app(Some(TOKEN)), "POST", path, &[("origin", "http://127.0.0.1:1234")]).await;
            assert_eq!((status, code.as_str()), (StatusCode::FORBIDDEN, "forbidden_origin"), "{path}");
        }
    }

    #[tokio::test]
    async fn the_docs_exemption_does_not_open_neighbouring_routes() {
        for path in ["/docsx", "/openapi.json.bak", "/v1/models"] {
            let (status, code) = call(app(Some(TOKEN)), path, &[]).await;
            assert_eq!((status, code.as_str()), (StatusCode::UNAUTHORIZED, "unauthorized"), "{path}");
        }
    }

    #[tokio::test]
    async fn no_token_configured_leaves_the_api_open() {
        let (status, _) = call(app(None), "/ready", &[]).await;
        assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    }

    #[test]
    fn constant_time_eq_needs_every_byte_and_the_same_length() {
        assert!(constant_time_eq(b"secret", b"secret"));
        assert!(!constant_time_eq(b"secret", b"secreT"));
        assert!(!constant_time_eq(b"secret", b"secre"));
        assert!(!constant_time_eq(b"", b"x"));
    }
}
