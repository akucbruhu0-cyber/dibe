//! Recordings the app never got to stop (a crash, a force-quit, a power cut).
//!
//! While recording, every device writes `<session id>-<device index>.wav` into the in-progress
//! folder in app data, which, unlike the temp folder, is never wiped. A normal stop removes those
//! files; whatever is still there at the next launch belongs to a recording that did not finish.
//! `scan` turns each such session back into one WAV in the `recovered` folder, and the UI makes a
//! project of it and then `forget`s it. Until then every launch offers it again, so it survives a
//! UI that quit or failed halfway.

use super::finalize::finalize_captures;
use crate::error::LogError;
use crate::ffmpeg::get_vibe_temp_folder;
use chrono::{DateTime, Local};
use eyre::{bail, eyre, Context, Result};
use serde::Serialize;
use std::collections::BTreeMap;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::SystemTime;
use tauri::async_runtime::JoinHandle;
use tauri::{AppHandle, Manager};

const IN_PROGRESS_DIR: &str = "recordings-in-progress";
/// Inside the in-progress folder: the recordings rebuilt from crashed sessions, kept until the UI
/// has made a project of each.
const RECOVERED_DIR: &str = "recovered";
/// Every capture is a 32-bit float WAV (see `capture::wav_spec`).
pub const BYTES_PER_SAMPLE: u64 = 4;
/// A file shorter than this cannot hold a WAV header, so nothing was ever recorded in it.
const WAV_HEADER_LEN: u64 = 44;

/// A recording rebuilt from what a dead session left on disk, waiting to become a project.
#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RecoveredRecording {
    pub path: String,
    pub name: String,
    /// When the recording was last written to, RFC 3339.
    pub recorded_at: String,
}

/// Where recordings are written while they run: app data, because the temp folder is wiped on
/// the next day's launch. Falls back to the temp folder, so recording never fails over this.
pub fn in_progress_dir(app: &AppHandle) -> PathBuf {
    let dir = app
        .path()
        .app_local_data_dir()
        .map(|dir| dir.join(IN_PROGRESS_DIR))
        .map_err(|error| eyre!("{error}"))
        .and_then(|dir| Ok(std::fs::create_dir_all(&dir).map(|()| dir)?));
    dir.unwrap_or_else(|error| {
        tracing::error!("no folder for in-progress recordings, using the temp folder: {error:#}");
        get_vibe_temp_folder()
    })
}

/// The file one device of a session records into.
pub fn capture_path(dir: &Path, session_id: &str, device_index: usize) -> PathBuf {
    dir.join(format!("{session_id}-{device_index}.wav"))
}

/// The session and device a file written by `capture_path` belongs to. Anything else in the
/// folder, a recovered recording included, is not a capture.
fn parse_capture(path: &Path) -> Option<(String, usize)> {
    if path.extension()? != "wav" {
        return None;
    }
    let (session_id, device_index) = path.file_stem()?.to_str()?.rsplit_once('-')?;
    uuid::Uuid::parse_str(session_id).ok()?;
    Some((session_id.to_string(), device_index.parse().ok()?))
}

/// Sets the 4 bytes at `at`, writing only when they differ so a whole file is left untouched.
fn set_u32(file: &mut File, at: u64, value: u32) -> Result<()> {
    let mut current = [0u8; 4];
    file.seek(SeekFrom::Start(at))?;
    file.read_exact(&mut current)?;
    if u32::from_le_bytes(current) != value {
        file.seek(SeekFrom::Start(at))?;
        file.write_all(&value.to_le_bytes())?;
    }
    Ok(())
}

/// Makes the WAV at `path` readable up to its last byte and returns the size of its audio.
///
/// The writer only puts the real lengths in the header at a checkpoint, so after a crash the
/// header is stale but everything after it is audio. The `data` chunk is the last one and runs to
/// the end of the file; a frame cut in half by the crash is trimmed off.
pub fn repair_wav_header(path: &Path) -> Result<u64> {
    let mut file = OpenOptions::new().read(true).write(true).open(path)?;
    let len = file.metadata()?.len();
    let mut riff = [0u8; 12];
    file.read_exact(&mut riff).context("shorter than a WAV header")?;
    if &riff[..4] != b"RIFF" || &riff[8..] != b"WAVE" {
        bail!("not a WAV file");
    }

    // Walk the chunks to `data`: a float or extensible fmt chunk is longer than 16 bytes, so the
    // audio does not start at a fixed offset.
    let (mut block_align, mut chunk_at) = (0u64, 12u64);
    let data_at = loop {
        let mut chunk = [0u8; 8];
        file.seek(SeekFrom::Start(chunk_at))?;
        file.read_exact(&mut chunk).context("no data chunk")?;
        let size = u64::from(u32::from_le_bytes([chunk[4], chunk[5], chunk[6], chunk[7]]));
        match &chunk[..4] {
            b"fmt " if size >= 16 => {
                // Format tag, channels, sample rate and byte rate come first, then the bytes in a frame.
                let mut fmt = [0u8; 14];
                file.read_exact(&mut fmt)?;
                block_align = u64::from(u16::from_le_bytes([fmt[12], fmt[13]]));
            }
            b"data" => break chunk_at + 8,
            _ => {}
        }
        chunk_at += 8 + size + (size & 1);
    };
    if block_align == 0 {
        bail!("the fmt chunk is missing or empty");
    }

    let data = (len - data_at) / block_align * block_align;
    let end = data_at + data;
    // Everything that can fail is checked before the first write: a file that cannot be repaired
    // is left exactly as it was.
    let riff_size = u32::try_from(end - 8).context("larger than a WAV can hold")?;
    let data_size = u32::try_from(data).context("larger than a WAV can hold")?;
    if end != len {
        file.set_len(end)?;
    }
    set_u32(&mut file, 4, riff_size)?;
    set_u32(&mut file, data_at - 4, data_size)?;
    Ok(data)
}

/// Where the recordings rebuilt from crashed sessions wait for the UI.
pub fn recovered_dir(in_progress: &Path) -> PathBuf {
    in_progress.join(RECOVERED_DIR)
}

/// A recording waiting in the recovered folder, described by its file: the modification time is
/// when the audio ended.
fn describe(path: &Path) -> Option<RecoveredRecording> {
    let modified = std::fs::metadata(path).and_then(|metadata| metadata.modified()).ok();
    let recorded_at: DateTime<Local> = modified.unwrap_or_else(SystemTime::now).into();
    Some(RecoveredRecording {
        name: path.file_name()?.to_string_lossy().into_owned(),
        path: path.to_string_lossy().into_owned(),
        recorded_at: recorded_at.to_rfc3339(),
    })
}

/// Every recording in the recovered folder, oldest first by name (which is its timestamp).
fn list_recovered(dir: &Path) -> Vec<RecoveredRecording> {
    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) => {
            if error.kind() != std::io::ErrorKind::NotFound {
                tracing::error!("could not read {}: {error}", dir.display());
            }
            return Vec::new();
        }
    };
    let mut paths: Vec<PathBuf> = entries
        .filter_map(|entry| entry.log_error())
        .map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|extension| extension == "wav") && path.is_file())
        .collect();
    paths.sort();
    paths.iter().filter_map(|path| describe(path)).collect()
}

/// Deletes a recording from the recovered folder once the UI has saved it as a project. This is
/// the only thing the UI can make the app delete, so it refuses everything that does not resolve
/// (links and `..` included) to a WAV directly inside the recovered folder. A path that is already
/// gone is fine.
pub fn forget(recovered_dir: &Path, path: &Path) -> Result<()> {
    let path = match path.canonicalize() {
        Ok(path) => path,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let root = recovered_dir.canonicalize().context("no recovered recordings folder")?;
    if path.parent() != Some(root.as_path()) || !path.extension().is_some_and(|extension| extension == "wav") {
        bail!("{} is not a recovered recording", path.display());
    }
    Ok(std::fs::remove_file(path)?)
}

/// Rebuilds one dead session: repairs each device's file, then merges and normalizes them like a
/// normal stop does, and puts the result in the recovered folder. Returns where it ended up.
fn recover_session(dir: &Path, captures: Vec<(usize, PathBuf)>) -> Option<PathBuf> {
    let mut files = Vec::new();
    let mut last_written: Option<SystemTime> = None;
    for (_, path) in captures {
        // Before the repair touches the file: the modification time is when the audio ended.
        let metadata = std::fs::metadata(&path).ok();
        last_written = last_written.max(metadata.as_ref().and_then(|metadata| metadata.modified().ok()));
        match repair_wav_header(&path) {
            Ok(bytes) => files.push((path, bytes / BYTES_PER_SAMPLE)),
            // Too short to hold a header: nothing was ever recorded, so it is safe to remove.
            Err(_) if metadata.is_some_and(|metadata| metadata.len() < WAV_HEADER_LEN) => files.push((path, 0)),
            // Anything else may be a recording that only lost its header, possibly the only copy:
            // it stays on disk as it is, out of the merge.
            // ponytail: retried and logged on every launch; rename it to `.corrupt` if that gets noisy.
            Err(error) => tracing::error!("leaving {} as it is, it cannot be repaired: {error:#}", path.display()),
        }
    }

    let stem = DateTime::<Local>::from(last_written.unwrap_or_else(SystemTime::now))
        .format("%Y-%m-%d %H-%M-%S")
        .to_string();
    // What went wrong along the way is logged by `finalize_captures`; the audio is what matters here.
    let output = finalize_captures(&files, dir, &stem, &mut Vec::new())?;

    // Published by a rename, so the recovered folder never holds a half-written recording.
    let recovered = recovered_dir(dir);
    let published = std::fs::create_dir_all(&recovered).and_then(|()| {
        let target = crate::cmd::files::available_path(&recovered, &stem, "wav");
        std::fs::rename(&output, &target).map(|()| target)
    });
    let output = published.unwrap_or_else(|error| {
        tracing::error!("could not move {} to the recovered folder: {error}", output.display());
        output
    });
    if let Some(last_written) = last_written {
        // The recording ended when its captures were last written, not when this launch rebuilt it.
        File::options()
            .write(true)
            .open(&output)
            .and_then(|file| file.set_modified(last_written))
            .log_error();
    }
    Some(output)
}

/// Rebuilds every session left in `dir` (except the one `active` belongs to) and returns the
/// recordings waiting for the UI: these, and the ones earlier launches rebuilt that were never
/// forgotten. A session that cannot be rebuilt is logged and skipped, never an error for the launch.
pub fn scan(dir: &Path, active: Option<&Path>) -> Vec<RecoveredRecording> {
    let rebuilt = recover_crashed_sessions(dir, active);
    let recovered = recovered_dir(dir);
    let mut offered = list_recovered(&recovered);
    // A rebuilt recording that could not be moved into the recovered folder is still offered once.
    offered.extend(
        rebuilt
            .iter()
            .filter(|path| path.parent() != Some(recovered.as_path()))
            .filter_map(|path| describe(path)),
    );
    offered
}

/// Turns every capture session left in `dir` into one recording; returns where each ended up.
fn recover_crashed_sessions(dir: &Path, active: Option<&Path>) -> Vec<PathBuf> {
    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) => {
            if error.kind() != std::io::ErrorKind::NotFound {
                tracing::error!("could not read {}: {error}", dir.display());
            }
            return Vec::new();
        }
    };
    // A running session writes one file per device: skip by session, not by file.
    let active_session = active.and_then(parse_capture).map(|(session_id, _)| session_id);
    let mut sessions: BTreeMap<String, Vec<(usize, PathBuf)>> = BTreeMap::new();
    for entry in entries.filter_map(|entry| entry.log_error()) {
        let path = entry.path();
        let Some((session_id, device_index)) = parse_capture(&path) else {
            continue;
        };
        if active_session.as_ref() != Some(&session_id) {
            sessions.entry(session_id).or_default().push((device_index, path));
        }
    }
    sessions
        .into_values()
        .filter_map(|mut captures| {
            captures.sort();
            recover_session(dir, captures)
        })
        .collect()
}

/// The recordings `scan` found at launch. The scan runs in the background, since merging a long
/// recording takes a while, and the UI collects the result once, whenever it is ready.
#[derive(Default)]
pub struct PendingRecovery(Mutex<Option<JoinHandle<Vec<RecoveredRecording>>>>);

impl PendingRecovery {
    fn slot(&self) -> MutexGuard<'_, Option<JoinHandle<Vec<RecoveredRecording>>>> {
        self.0.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub fn start(&self, scan: impl FnOnce() -> Vec<RecoveredRecording> + Send + 'static) {
        *self.slot() = Some(tauri::async_runtime::spawn_blocking(scan));
    }

    /// Waits for the scan and hands over what it found. Later calls get nothing.
    pub async fn take(&self) -> Vec<RecoveredRecording> {
        let Some(scan) = self.slot().take() else {
            return Vec::new();
        };
        scan.await.map_err(|error| eyre!("{error:?}")).log_error().unwrap_or_default()
    }
}

/// Starts the launch-time scan of the in-progress folder (nothing is recording yet).
pub fn start_scan(app: &AppHandle) {
    let dir = in_progress_dir(app);
    app.state::<PendingRecovery>().start(move || scan(&dir, None));
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{DateTime, Local, Utc};
    use std::time::{Duration, SystemTime};

    const SESSION: &str = "11111111-2222-3333-4444-555555555555";
    const OTHER_SESSION: &str = "66666666-7777-8888-9999-000000000000";

    fn spec(channels: u16) -> hound::WavSpec {
        hound::WavSpec {
            channels,
            sample_rate: 48_000,
            bits_per_sample: 32,
            sample_format: hound::SampleFormat::Float,
        }
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("vibe-recovery-{name}-{}", crate::ffmpeg::random_string(8)));
        std::fs::create_dir_all(&dir).expect("create dir");
        dir
    }

    /// What a crash leaves behind: the audio is on disk, the two sizes hound only writes on
    /// flush and finalize still read 0.
    fn crashed_wav(path: &Path, channels: u16, samples: usize) {
        let mut wav = hound::WavWriter::create(path, spec(channels)).expect("create");
        for _ in 0..samples {
            wav.write_sample(0.25f32).expect("write");
        }
        wav.finalize().expect("finalize");
        let mut bytes = std::fs::read(path).expect("read");
        let data = bytes.windows(4).position(|window| window == b"data").expect("data chunk");
        bytes[4..8].fill(0);
        bytes[data + 4..data + 8].fill(0);
        std::fs::write(path, bytes).expect("write");
    }

    fn sample_count(path: &Path) -> u32 {
        hound::WavReader::open(path).expect("open").len()
    }

    #[test]
    fn repair_restores_length_of_unfinalized_file() {
        let dir = temp_dir("repair");
        let path = dir.join("capture.wav");
        crashed_wav(&path, 1, 1_000);
        assert_eq!(sample_count(&path), 0, "the header says the file is empty");

        assert_eq!(repair_wav_header(&path).expect("repair"), 4_000);
        assert_eq!(sample_count(&path), 1_000);
        // A file that is already whole stays as it is.
        assert_eq!(repair_wav_header(&path).expect("repair again"), 4_000);
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn repair_trims_a_trailing_partial_frame() {
        let dir = temp_dir("trim");
        let path = dir.join("capture.wav");
        crashed_wav(&path, 2, 200);
        // The crash hit in the middle of a frame: half of a stereo float frame made it to disk.
        let mut bytes = std::fs::read(&path).expect("read");
        let whole = bytes.len();
        bytes.extend_from_slice(&[0, 0, 0x80, 0x3e]);
        std::fs::write(&path, bytes).expect("write");

        assert_eq!(repair_wav_header(&path).expect("repair"), 800);
        assert_eq!(sample_count(&path), 200);
        assert_eq!(std::fs::metadata(&path).expect("metadata").len() as usize, whole);
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn scan_recovers_unfinalized_file() {
        let dir = temp_dir("recovers");
        let capture = capture_path(&dir, SESSION, 0);
        crashed_wav(&capture, 1, 48_000);
        // The recording ended three days ago; relaunching must not make it look like it ended now.
        let ended = SystemTime::now() - Duration::from_secs(3 * 24 * 3600);
        std::fs::File::options()
            .write(true)
            .open(&capture)
            .and_then(|file| file.set_modified(ended))
            .expect("set mtime");

        let recovered = scan(&dir, None);

        assert_eq!(recovered.len(), 1);
        let path = PathBuf::from(&recovered[0].path);
        assert!(
            path.is_file() && path.parent() == Some(dir.join("recovered").as_path()),
            "kept in the folder that is never wiped, apart from the captures"
        );
        // Normalized (16 kHz mono) when ffmpeg is there, the repaired capture otherwise: audio either way.
        assert!(sample_count(&path) > 0);
        assert!(!capture.exists(), "the capture was turned into the recording");
        assert_eq!(
            path.file_name().map(|name| name.to_string_lossy().into_owned()),
            Some(recovered[0].name.clone())
        );
        let recorded_at = DateTime::parse_from_rfc3339(&recovered[0].recorded_at).expect("RFC 3339");
        assert_eq!(recorded_at.timestamp(), DateTime::<Utc>::from(ended).timestamp());
        let stamp = DateTime::<Local>::from(ended).format("%Y-%m-%d %H-%M-%S");
        assert_eq!(recovered[0].name, format!("{stamp}.wav"));
        assert!(serde_json::to_value(&recovered[0]).expect("json")["recordedAt"].is_string());
        // Offered again by every launch until the UI has saved it and says so: the one that
        // collected it may have quit before it did.
        let again = scan(&dir, None);
        assert_eq!(again.len(), 1);
        assert_eq!(
            (&again[0].path, &again[0].recorded_at),
            (&recovered[0].path, &recovered[0].recorded_at)
        );
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn scan_offers_recovered_files_again_until_they_are_forgotten() {
        let dir = temp_dir("offered");
        let recovered_dir = dir.join("recovered");
        std::fs::create_dir_all(&recovered_dir).expect("create dir");
        let left_over = recovered_dir.join("2026-10-24 09-00-00.wav");
        crashed_wav(&left_over, 1, 4_800);
        let ended = SystemTime::now() - Duration::from_secs(2 * 24 * 3600);
        std::fs::File::options()
            .write(true)
            .open(&left_over)
            .and_then(|file| file.set_modified(ended))
            .expect("set mtime");
        std::fs::write(recovered_dir.join("notes.txt"), "not audio").expect("write");

        let offered = scan(&dir, None);

        assert_eq!(offered.len(), 1, "only the WAV is offered");
        assert_eq!(offered[0].path, left_over.to_string_lossy());
        assert_eq!(offered[0].name, "2026-10-24 09-00-00.wav");
        let recorded_at = DateTime::parse_from_rfc3339(&offered[0].recorded_at).expect("RFC 3339");
        assert_eq!(recorded_at.timestamp(), DateTime::<Utc>::from(ended).timestamp());
        assert!(left_over.exists(), "offering it does not consume it");
        assert_eq!(scan(&dir, None).len(), 1);

        forget(&recovered_dir, &left_over).expect("forget");
        assert!(scan(&dir, None).is_empty());
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn forget_deletes_inside_the_recovered_folder_and_refuses_anything_else() {
        let dir = temp_dir("forget");
        let recovered_dir = dir.join("recovered");
        std::fs::create_dir_all(&recovered_dir).expect("create dir");
        let inside = recovered_dir.join("a.wav");
        let outside = dir.join("outside.wav");
        std::fs::write(&inside, "audio").expect("write");
        std::fs::write(&outside, "audio").expect("write");

        assert!(forget(&recovered_dir, &outside).is_err());
        assert!(forget(&recovered_dir, &recovered_dir.join("..").join("outside.wav")).is_err());
        assert!(forget(&recovered_dir, &recovered_dir).is_err(), "not the folder itself");
        let not_audio = recovered_dir.join("notes.txt");
        std::fs::write(&not_audio, "x").expect("write");
        assert!(forget(&recovered_dir, &not_audio).is_err(), "only recordings");
        #[cfg(unix)]
        {
            let link = recovered_dir.join("link.wav");
            std::os::unix::fs::symlink(&outside, &link).expect("symlink");
            assert!(
                forget(&recovered_dir, &link).is_err(),
                "a link out of the folder is not inside it"
            );
        }
        assert!(outside.exists() && not_audio.exists(), "nothing outside was touched");

        forget(&recovered_dir, &inside).expect("forget");
        assert!(!inside.exists());
        forget(&recovered_dir, &inside).expect("already gone is fine");
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn scan_leaves_an_unreadable_capture_alone() {
        let dir = temp_dir("unreadable");
        // A power cut lost the header of these files but not their audio: unreadable is not empty.
        let damage = |path: &Path| {
            crashed_wav(path, 1, 48_000);
            let mut bytes = std::fs::read(path).expect("read");
            bytes[..64].fill(0);
            std::fs::write(path, &bytes).expect("write");
            bytes
        };
        let lone = capture_path(&dir, OTHER_SESSION, 0);
        let lone_bytes = damage(&lone);
        let sibling = capture_path(&dir, SESSION, 1);
        let sibling_bytes = damage(&sibling);
        // A healthy session recovers around them.
        crashed_wav(&capture_path(&dir, SESSION, 0), 1, 48_000);

        let recovered = scan(&dir, None);

        assert_eq!(recovered.len(), 1, "the readable device is recovered, nothing else");
        assert_eq!(std::fs::read(&lone).expect("still there"), lone_bytes);
        assert_eq!(std::fs::read(&sibling).expect("still there"), sibling_bytes);
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn scan_skips_active_session_file() {
        let dir = temp_dir("active");
        let (mic, system) = (capture_path(&dir, SESSION, 0), capture_path(&dir, SESSION, 1));
        crashed_wav(&mic, 1, 4_800);
        crashed_wav(&system, 2, 4_800);
        crashed_wav(&capture_path(&dir, OTHER_SESSION, 0), 1, 4_800);

        // The running session has one file per device; naming either one protects both.
        let recovered = scan(&dir, Some(&mic));

        assert_eq!(recovered.len(), 1, "only the dead session is recovered");
        assert!(mic.exists() && system.exists());
        assert_eq!(sample_count(&mic), 0, "still being written: left exactly as it was");
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn scan_makes_one_recording_of_a_sessions_devices() {
        let dir = temp_dir("devices");
        crashed_wav(&capture_path(&dir, SESSION, 0), 1, 48_000);
        crashed_wav(&capture_path(&dir, SESSION, 1), 2, 96_000);

        let recovered = scan(&dir, None);

        assert_eq!(recovered.len(), 1);
        let list = |dir: &Path| -> Vec<PathBuf> {
            std::fs::read_dir(dir)
                .expect("read dir")
                .map(|entry| entry.expect("entry").path())
                .collect()
        };
        // Merged when ffmpeg is there, the longer capture otherwise: one file holding audio, no leftovers.
        assert_eq!(list(&dir), vec![dir.join("recovered")]);
        assert_eq!(list(&dir.join("recovered")), vec![PathBuf::from(&recovered[0].path)]);
        assert!(sample_count(&PathBuf::from(&recovered[0].path)) > 0);
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn scan_drops_sessions_without_audio() {
        let dir = temp_dir("silent");
        // A crash before the first checkpoint leaves an empty file, or just the header.
        let empty = capture_path(&dir, SESSION, 0);
        std::fs::write(&empty, []).expect("write");
        let header_only = capture_path(&dir, SESSION, 1);
        crashed_wav(&header_only, 1, 0);

        assert!(scan(&dir, None).is_empty());
        assert!(!empty.exists() && !header_only.exists());
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn recovered_recordings_are_handed_over_once() {
        let pending = PendingRecovery::default();
        assert!(
            tauri::async_runtime::block_on(pending.take()).is_empty(),
            "no scan was started"
        );
        pending.start(|| {
            vec![RecoveredRecording {
                path: "a.wav".into(),
                name: "a.wav".into(),
                recorded_at: "2026-10-25T10:00:00+00:00".into(),
            }]
        });
        assert_eq!(tauri::async_runtime::block_on(pending.take()).len(), 1);
        assert!(tauri::async_runtime::block_on(pending.take()).is_empty());
    }

    #[test]
    fn scan_ignores_a_missing_folder() {
        assert!(scan(&std::env::temp_dir().join("vibe-recovery-never-created"), None).is_empty());
    }
}
