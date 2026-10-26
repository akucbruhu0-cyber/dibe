//! Turns the per-device WAVs of one recording into the single file the app keeps: merged when two
//! devices delivered audio, then normalized. Shared by a normal stop and by the recovery of a
//! recording the app never got to stop.

use crate::error::LogError;
use crate::ffmpeg::random_string;
use eyre::eyre;
use std::path::{Path, PathBuf};

/// The capture with the most audio, or `None` when no device delivered any.
pub fn best_raw_capture(wav_paths: &[(PathBuf, u64)]) -> Option<PathBuf> {
    wav_paths
        .iter()
        .filter(|(_, samples)| *samples > 0)
        .max_by_key(|(_, samples)| *samples)
        .map(|(path, _)| path.clone())
}

pub fn remove_files(paths: impl IntoIterator<Item = PathBuf>) {
    for path in paths {
        if path.exists() {
            std::fs::remove_file(path).map_err(|e| eyre!("{e:?}")).log_error();
        }
    }
}

/// Merges and normalizes `captures` (each WAV with the number of samples in it) into
/// `out_dir/<stem>.wav` and removes the captures. A failed merge or normalization keeps the best
/// raw capture instead and says why in `warnings`. `None` when no capture holds any audio; the
/// empty files are removed too.
pub fn finalize_captures(captures: &[(PathBuf, u64)], out_dir: &Path, stem: &str, warnings: &mut Vec<String>) -> Option<PathBuf> {
    let Some(best_raw) = best_raw_capture(captures) else {
        remove_files(captures.iter().map(|(path, _)| path.clone()));
        return None;
    };

    let dst = if captures.len() > 1 && captures[0].1 > 0 && captures[1].1 > 0 {
        let dst = out_dir.join(format!("{}.wav", random_string(10)));
        tracing::debug!("Merging WAV files");
        match crate::ffmpeg::merge_wav_files(captures[0].0.clone(), captures[1].0.clone(), dst.clone()) {
            Ok(()) if dst.is_file() => dst,
            Ok(()) => {
                std::fs::remove_file(&dst).ok();
                let message = "Audio merge produced no output; preserving the best raw capture".to_string();
                tracing::error!("{message}");
                warnings.push(message);
                best_raw
            }
            Err(error) => {
                std::fs::remove_file(&dst).ok();
                let message = format!("Audio merge failed; preserving the best raw capture: {error:#}");
                tracing::error!("{message}");
                warnings.push(message);
                best_raw
            }
        }
    } else {
        best_raw
    };

    let normalized = crate::cmd::files::available_path(out_dir, stem, "wav");
    let output = match crate::ffmpeg::normalize(dst.clone(), normalized.clone(), None) {
        Ok(()) if normalized.is_file() => normalized,
        result => {
            std::fs::remove_file(&normalized).ok();
            let detail = match result {
                Ok(()) => "normalization produced no output".to_string(),
                Err(error) => format!("{error:#}"),
            };
            let message = format!("Audio normalization failed; preserving the unnormalized capture: {detail}");
            tracing::error!("{message}");
            warnings.push(message);

            let recovery = crate::cmd::files::available_path(out_dir, stem, "wav");
            if dst != recovery && std::fs::rename(&dst, &recovery).is_ok() {
                recovery
            } else {
                dst.clone()
            }
        }
    };

    let intermediates = captures.iter().map(|(path, _)| path.clone()).chain(std::iter::once(dst));
    remove_files(intermediates.filter(|path| *path != output));
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovery_prefers_the_capture_with_the_most_samples() {
        let captures = vec![(PathBuf::from("short.wav"), 12), (PathBuf::from("long.wav"), 42)];
        assert_eq!(best_raw_capture(&captures), Some(PathBuf::from("long.wav")));
        assert_eq!(best_raw_capture(&[]), None);
        assert_eq!(best_raw_capture(&[(PathBuf::from("silent.wav"), 0)]), None);
    }
}
