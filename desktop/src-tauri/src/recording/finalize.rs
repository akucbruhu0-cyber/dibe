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

/// Moves a capture a failed merge left out into the recovered folder beside it, where the next
/// launch offers it as a recording of its own. `false` when it could not be moved.
fn offer_unmerged(capture: &Path, stem: &str) -> bool {
    let Some(in_progress) = capture.parent() else {
        return false;
    };
    let recovered = super::recovery::recovered_dir(in_progress);
    let moved = std::fs::create_dir_all(&recovered).and_then(|()| {
        let target = crate::cmd::files::available_path(&recovered, stem, "wav");
        std::fs::rename(capture, target)
    });
    moved
        .map_err(|error| tracing::error!("could not move {} to the recovered folder: {error}", capture.display()))
        .is_ok()
}

/// Merges and normalizes `captures` (each WAV with the number of samples in it) into
/// `out_dir/<stem>.wav` and removes the captures. A failed merge keeps the best raw capture and
/// offers the other one at the next launch; a failed normalization keeps the capture unnormalized;
/// either says why in `warnings`. `None` when no capture holds any audio; the empty files are
/// removed too.
pub fn finalize_captures(captures: &[(PathBuf, u64)], out_dir: &Path, stem: &str, warnings: &mut Vec<String>) -> Option<PathBuf> {
    let Some(best_raw) = best_raw_capture(captures) else {
        remove_files(captures.iter().map(|(path, _)| path.clone()));
        return None;
    };

    // A capture the merge left out, kept for the next launch rather than deleted.
    let mut unmerged = None;
    let dst = if captures.len() > 1 && captures[0].1 > 0 && captures[1].1 > 0 {
        let dst = out_dir.join(format!("{}.wav", random_string(10)));
        tracing::debug!("Merging WAV files");
        match crate::ffmpeg::merge_wav_files(captures[0].0.clone(), captures[1].0.clone(), dst.clone()) {
            Ok(()) if dst.is_file() => dst,
            merged => {
                std::fs::remove_file(&dst).ok();
                let detail = match merged {
                    Ok(()) => "it produced no output".to_string(),
                    Err(error) => format!("{error:#}"),
                };
                let other = if captures[0].0 == best_raw {
                    &captures[1].0
                } else {
                    &captures[0].0
                };
                // Where it cannot be moved it stays put, and the next launch's scan rebuilds it from there.
                let offered = if offer_unmerged(other, stem) {
                    "offered as a recording of its own at"
                } else {
                    "left for"
                };
                let message = format!(
                    "Audio merge failed; preserving the device with the most audio, and the other device's audio is {offered} the next launch: {detail}"
                );
                tracing::error!("{message}");
                warnings.push(message);
                unmerged = Some(other.clone());
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
    remove_files(intermediates.filter(|path| *path != output && Some(path) != unmerged.as_ref()));
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_failed_merge_offers_the_other_device_at_the_next_launch() {
        let in_progress = std::env::temp_dir().join(format!("vibe-merge-failed-{}", random_string(8)));
        let out_dir = in_progress.join("out");
        std::fs::create_dir_all(&out_dir).expect("temp dirs");
        let mic = in_progress.join("session-0.wav");
        let spec = hound::WavSpec {
            channels: 1,
            sample_rate: 16_000,
            bits_per_sample: 32,
            sample_format: hound::SampleFormat::Float,
        };
        let mut writer = hound::WavWriter::create(&mic, spec).expect("create");
        for _ in 0..16_000 {
            writer.write_sample(0.25f32).expect("write");
        }
        writer.finalize().expect("finalize");
        // ffmpeg cannot read this one, so the merge fails.
        let system = in_progress.join("session-1.wav");
        std::fs::write(&system, b"not audio").expect("write");
        let mut warnings = Vec::new();

        let captures = [(mic.clone(), 16_000), (system.clone(), 100)];
        let output = finalize_captures(&captures, &out_dir, "talk", &mut warnings).expect("the mic's audio");

        assert!(output.starts_with(&out_dir) && output.is_file());
        // Not deleted: it waits where recovery offers it as a recording of its own.
        let offered = super::super::recovery::recovered_dir(&in_progress).join("talk.wav");
        assert_eq!(std::fs::read(&offered).ok().as_deref(), Some(&b"not audio"[..]));
        assert!(!system.exists() && !mic.exists());
        assert!(warnings.iter().any(|warning| warning.contains("next launch")), "{warnings:?}");
        std::fs::remove_dir_all(in_progress).ok();
    }

    #[test]
    fn recovery_prefers_the_capture_with_the_most_samples() {
        let captures = vec![(PathBuf::from("short.wav"), 12), (PathBuf::from("long.wav"), 42)];
        assert_eq!(best_raw_capture(&captures), Some(PathBuf::from("long.wav")));
        assert_eq!(best_raw_capture(&[]), None);
        assert_eq!(best_raw_capture(&[(PathBuf::from("silent.wav"), 0)]), None);
    }
}
