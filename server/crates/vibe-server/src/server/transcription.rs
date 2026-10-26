use std::collections::HashMap;

use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use whisper_rs::TranscribeOptions;

use crate::audio;
use crate::cli::AppConfig;
use crate::server::diarization;
use crate::server::form::FormValues;
use crate::server::stream::stream_transcription;
use crate::server::unload_timeout::ModelLease;
use crate::server::{error, format, TextResponse};

pub(super) struct TranscriptionRequest {
    /// The upload, spooled to disk while it arrived; deleted when this is dropped.
    pub file: tempfile::NamedTempFile,
    pub form: HashMap<String, String>,
}

pub(super) async fn transcribe(
    config: AppConfig,
    mut model: ModelLease,
    request: TranscriptionRequest,
) -> Result<Response, Response> {
    let TranscriptionRequest { file, form } = request;
    let (diarize_model, enhance_audio, stable_timestamps, vad_model_path, stream, response_format) = {
        let values = FormValues::new(&form);
        (
            values.string("diarize_model"),
            values.bool("enhance_audio"),
            values.bool("stable_timestamps"),
            values.string("vad_model"),
            values.bool("stream"),
            values.str_or("response_format", "json").to_string(),
        )
    };
    let audio_options = audio::ReadOptions {
        enhance_audio,
        verbose: config.verbose(),
    };
    let samples = audio::read_path_with_options(file.path(), audio_options).map_err(|err| {
        error(
            StatusCode::BAD_REQUEST,
            "invalid_audio",
            &format!("invalid audio file: {err}"),
        )
    })?;
    // Decoded: a screen recording can be gigabytes, and the transcription below can run for hours.
    drop(file);

    let diarized = diarize_or_warn(diarize_model.as_deref(), &samples);

    if stable_timestamps && vad_model_path.is_none() {
        return Err(error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "'vad_model' is required when 'stable_timestamps' is true",
        ));
    }

    if stream {
        return stream_transcription(config, model, samples, form, stable_timestamps, vad_model_path, diarized)
            .map_err(|err| *err);
    }

    let verbose = config.verbose();
    let ctx = model
        .ctx
        .as_mut()
        .ok_or_else(|| error(StatusCode::SERVICE_UNAVAILABLE, "no_model", "no model loaded"))?;

    if ctx.requires_vad() && vad_model_path.is_none() {
        return Err(error(
            StatusCode::BAD_REQUEST,
            "invalid_request",
            "'vad_model' is required for this transcription engine",
        ));
    }

    let opts = build_options(&form, verbose, stable_timestamps, vad_model_path);
    let result = ctx.transcribe(&samples, opts).map_err(|err| {
        tracing::error!(samples = samples.len(), "transcription failed: {err:#}");
        error(
            StatusCode::INTERNAL_SERVER_ERROR,
            super::engine_error_code(&err),
            &format!("transcription failed: {err}"),
        )
    })?;

    Ok(format_response(&response_format, &result, diarized))
}

pub(super) fn build_options(
    form: &HashMap<String, String>,
    verbose: bool,
    stable_timestamps: bool,
    vad_model_path: Option<String>,
) -> TranscribeOptions {
    let values = FormValues::new(form);
    TranscribeOptions {
        language: values.string("language"),
        detect_language: values.bool("detect_language"),
        translate: values.bool("translate"),
        threads: values.i32("n_threads"),
        prompt: values.string("prompt"),
        verbose,
        temperature: values.f32("temperature"),
        max_text_ctx: values.i32("max_text_ctx"),
        word_timestamps: values.bool("word_timestamps"),
        max_segment_len: values.i32("max_segment_len"),
        sampling_greedy: form.get("sampling_strategy").is_none_or(|strategy| strategy != "beam_search"),
        best_of: values.i32("best_of"),
        beam_size: values.i32("beam_size"),
        stable_timestamps,
        vad_model_path,
    }
}

/// What diarization contributed to a request: speaker turns, or a warning saying why not.
#[derive(Default)]
pub(super) struct Diarized {
    pub segments: Vec<diarization::Segment>,
    pub warnings: Vec<format::Warning>,
}

/// Diarization is optional, so a failure must not fail the transcription, but it must not
/// pass unnoticed either: the transcript comes back without speakers, plus a warning.
fn diarize_or_warn(model_path: Option<&str>, samples: &[f32]) -> Diarized {
    let Some(model_path) = model_path else {
        return Diarized::default();
    };
    match diarization::diarize(model_path, samples) {
        Ok(segments) => Diarized {
            segments,
            warnings: Vec::new(),
        },
        Err(err) => {
            tracing::warn!("diarization failed, continuing without speakers: {err:#}");
            Diarized {
                segments: Vec::new(),
                warnings: vec![format::Warning {
                    code: "diarization_failed".to_string(),
                    message: format!("{err:#}"),
                }],
            }
        }
    }
}

fn format_response(response_format: &str, result: &whisper_rs::TranscribeResult, diarized: Diarized) -> Response {
    match response_format {
        "verbose_json" => Json(format::build_verbose_json(result, &diarized.segments, diarized.warnings)).into_response(),
        "text" => ([(header::CONTENT_TYPE, "text/plain; charset=utf-8")], result.text()).into_response(),
        "srt" => (
            [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
            format::format_srt(&result.segments),
        )
            .into_response(),
        "vtt" => (
            [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
            format::format_vtt(&result.segments),
        )
            .into_response(),
        _ => Json(TextResponse { text: result.text() }).into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_failed_diarization_becomes_a_warning_and_no_speakers() {
        let model = "/definitely/not/a/model.gguf";
        let diarized = diarize_or_warn(Some(model), &[0.0; 16_000]);
        assert!(diarized.segments.is_empty());
        assert_eq!(diarized.warnings.len(), 1);
        assert_eq!(diarized.warnings[0].code, "diarization_failed");
        assert!(
            diarized.warnings[0].message.contains(model),
            "{}",
            diarized.warnings[0].message
        );
    }

    #[test]
    fn no_diarization_requested_means_no_warning() {
        let diarized = diarize_or_warn(None, &[0.0; 16_000]);
        assert!(diarized.segments.is_empty());
        assert!(diarized.warnings.is_empty());
    }
}
