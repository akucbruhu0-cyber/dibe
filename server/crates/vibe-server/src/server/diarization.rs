#[cfg(feature = "diarize")]
pub use diarize_rs::Segment;

#[cfg(not(feature = "diarize"))]
#[derive(Debug, Clone, serde::Serialize)]
pub struct Segment {
    pub start: f64,
    pub end: f64,
    pub speaker_id: usize,
}

/// An error means the caller asked for speakers and cannot have them: a missing or corrupt
/// model, a failed run, or a build without diarization. It is the caller's job to say so.
#[cfg(feature = "diarize")]
pub fn diarize(model_path: &str, samples: &[f32]) -> anyhow::Result<Vec<Segment>> {
    let mut diarizer = diarize_rs::Diarizer::new(model_path)?;
    Ok(diarizer.diarize(samples, 16_000, 1)?)
}

#[cfg(not(feature = "diarize"))]
pub fn diarize(model_path: &str, _samples: &[f32]) -> anyhow::Result<Vec<Segment>> {
    anyhow::bail!("speaker diarization is not enabled in this build (model: {model_path})")
}

#[cfg(test)]
mod tests {
    #[test]
    fn diarize_with_missing_model_is_err() {
        let model = "/definitely/not/a/model.gguf";
        let err = super::diarize(model, &[0.0; 16_000]).unwrap_err();
        assert!(err.to_string().contains(model), "the error should name the model: {err}");
    }
}
