use std::collections::HashMap;
use std::io::Write;

use axum::extract::multipart::Field;
use axum::extract::{Multipart, State};
use axum::http::StatusCode;
use axum::response::Response;

use crate::server::transcription::TranscriptionRequest;
use crate::server::{error, AppState, MAX_UPLOAD_SIZE};

#[utoipa::path(
    post,
    path = "/v1/audio/transcriptions",
    responses(
        (status = 200, description = "Transcription result"),
        (status = 400, description = "Invalid request/audio", body = crate::server::ErrorResponse),
        (status = 429, description = "Busy", body = crate::server::ErrorResponse),
        (status = 503, description = "No model loaded", body = crate::server::ErrorResponse)
    )
)]
pub(in crate::server) async fn transcriptions(State(state): State<AppState>, multipart: Multipart) -> Response {
    let model = match state.unload_timeout.try_acquire(state.inner.clone()) {
        Ok(model) => model,
        Err(_) => {
            return error(
                StatusCode::TOO_MANY_REQUESTS,
                "busy",
                "server is busy with another transcription",
            );
        }
    };
    match parse_multipart(multipart, MAX_UPLOAD_SIZE).await {
        Ok(request) => match crate::server::transcription::transcribe(state.config, model, request).await {
            Ok(response) => response,
            Err(response) => response,
        },
        Err(response) => response,
    }
}

/// `max_file_size` caps the `file` field alone; the router's body limit caps the request as a whole.
async fn parse_multipart(mut multipart: Multipart, max_file_size: usize) -> Result<TranscriptionRequest, Response> {
    let mut file = None;
    let mut form = HashMap::new();

    while let Some(field) = multipart.next_field().await.map_err(|err| {
        error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            &format!("invalid multipart form: {err}"),
        )
    })? {
        let name = field.name().unwrap_or_default().to_string();
        if name == "file" {
            file = Some(spool_field(field, max_file_size).await.map_err(|response| *response)?);
        } else {
            let value = field.text().await.unwrap_or_default();
            form.insert(name, value);
        }
    }

    let file = file.ok_or_else(|| error(StatusCode::BAD_REQUEST, "invalid_request", "missing or invalid 'file' field"))?;

    Ok(TranscriptionRequest { file, form })
}

/// Writes the field to a temp file as it arrives, so the upload is never held in memory. The file is
/// removed when the handler returns early or the client disconnects (the future is dropped).
async fn spool_field(mut field: Field<'_>, max_size: usize) -> Result<tempfile::NamedTempFile, Box<Response>> {
    let mut file = tempfile::NamedTempFile::new().map_err(|err| {
        Box::new(error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            &format!("cannot create a temp file for the upload: {err}"),
        ))
    })?;
    let mut size = 0usize;
    while let Some(chunk) = field.chunk().await.map_err(|err| {
        Box::new(error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            &format!("missing or invalid 'file' field: {err}"),
        ))
    })? {
        size += chunk.len();
        if size > max_size {
            return Err(Box::new(error(
                StatusCode::BAD_REQUEST,
                "invalid_request",
                "uploaded file is too large",
            )));
        }
        // A blocking write per network-sized chunk, straight into the page cache; not worth a thread hop.
        file.write_all(&chunk).map_err(|err| {
            Box::new(error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                &format!("cannot write the upload to disk: {err}"),
            ))
        })?;
    }
    Ok(file)
}

#[cfg(test)]
mod tests {
    use axum::body::{to_bytes, Body};
    use axum::extract::{FromRequest, Multipart};
    use axum::http::{header, Request};
    use bytes::Bytes;
    use futures_util::stream;

    use super::*;

    const BOUNDARY: &str = "vibe-test-boundary";

    /// A multipart body with a `language` text field and a `file` field that arrives in `chunks`.
    async fn multipart(chunks: &[&[u8]]) -> Multipart {
        let head = format!(
            "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nhe\r\n\
             --{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n\r\n"
        );
        let mut parts = vec![Bytes::from(head)];
        parts.extend(chunks.iter().map(|chunk| Bytes::copy_from_slice(chunk)));
        parts.push(Bytes::from(format!("\r\n--{BOUNDARY}--\r\n")));
        let request = Request::builder()
            .header(header::CONTENT_TYPE, format!("multipart/form-data; boundary={BOUNDARY}"))
            .body(Body::from_stream(stream::iter(
                parts.into_iter().map(Ok::<_, std::convert::Infallible>),
            )))
            .unwrap();
        Multipart::from_request(request, &()).await.unwrap()
    }

    #[tokio::test]
    async fn multipart_file_is_spooled_to_disk() {
        let request = parse_multipart(multipart(&[b"aaaa", b"bbbbbb", b"cc"]).await, MAX_UPLOAD_SIZE)
            .await
            .unwrap();
        assert_eq!(request.file.as_file().metadata().unwrap().len(), 12);
        assert_eq!(std::fs::read(request.file.path()).unwrap(), b"aaaabbbbbbcc");
        assert_eq!(request.form.get("language").map(String::as_str), Some("he"));
        let path = request.file.path().to_path_buf();
        drop(request);
        assert!(!path.exists(), "the temp file outlived the request");
    }

    #[tokio::test]
    async fn oversized_file_is_rejected_while_streaming() {
        let response = parse_multipart(multipart(&[b"aaaa", b"bbbbbb", b"cc"]).await, 11)
            .await
            .err()
            .expect("an 11-byte limit must reject 12 bytes");
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
        assert!(String::from_utf8_lossy(&body).contains("uploaded file is too large"));
    }
}
