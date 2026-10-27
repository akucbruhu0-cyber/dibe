//! First launch of DIBE: bring over the original Vibe's data, once.
//!
//! DIBE has its own identifier, so it starts with an empty data folder beside Vibe's. Rather
//! than make people download their models again, the first launch copies Vibe's data folder and
//! its projects folder with `cp -c` — an APFS clone, which costs no disk space until a copy is
//! changed — and rewrites the paths inside settings and project records so DIBE only ever points
//! at its own copies. Vibe's files are only read, never changed, so both apps keep working.
//!
//! Left out on purpose: `server.json` (describes Vibe's running server), `recordings-in-progress`
//! (Vibe recovers those itself; copying would recover them twice), `handoff` (the phone-pairing
//! identity — two apps sharing one would fight over it) and log files.

use eyre::{bail, Context, Result};
use std::path::{Path, PathBuf};
use std::process::Command;

/// Written into DIBE's data folder once the import ran, so it never runs again.
const MARKER: &str = ".imported-from-vibe.json";
const SKIPPED: &[&str] = &["server.json", "recordings-in-progress", "handoff"];

pub struct ImportPaths {
    pub vibe_data: PathBuf,
    pub dibe_data: PathBuf,
    pub vibe_projects: PathBuf,
    pub dibe_projects: PathBuf,
}

#[derive(Debug, Default, PartialEq, serde::Serialize)]
pub struct ImportReport {
    pub data_entries: Vec<String>,
    pub projects_copied: bool,
    pub records_rewritten: usize,
}

/// Resolve the folders from the running app and import. Never fails the launch: a problem is
/// logged and DIBE simply starts empty, exactly as a fresh install would.
pub fn run(app: &tauri::App) {
    use tauri::Manager;
    let paths = (|| -> Result<ImportPaths> {
        let dibe_data = app.path().app_local_data_dir()?;
        let parent = dibe_data.parent().map(Path::to_path_buf).unwrap_or_default();
        let documents = app.path().document_dir()?;
        Ok(ImportPaths {
            vibe_data: parent.join(crate::config::VIBE_APP_ID),
            dibe_data,
            vibe_projects: documents.join(crate::config::VIBE_DOCUMENTS_SUBFOLDER),
            dibe_projects: documents.join(crate::config::DOCUMENTS_SUBFOLDER),
        })
    })();
    match paths.and_then(|paths| import(&paths)) {
        Ok(Some(report)) => tracing::info!("imported Vibe data: {report:?}"),
        Ok(None) => {}
        Err(error) => tracing::error!("could not import Vibe data: {error:#}"),
    }
}

/// Copy what DIBE does not have yet. `Ok(None)` when there is nothing to do.
pub fn import(paths: &ImportPaths) -> Result<Option<ImportReport>> {
    let marker = paths.dibe_data.join(MARKER);
    if marker.exists() || !paths.vibe_data.is_dir() || paths.dibe_data.join(crate::config::STORE_FILENAME).exists() {
        return Ok(None);
    }
    std::fs::create_dir_all(&paths.dibe_data)?;
    let mut report = ImportReport::default();

    for entry in std::fs::read_dir(&paths.vibe_data)? {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().into_owned();
        let is_log = name.starts_with(crate::config::LOG_FILENAME_PREFIX) && name.ends_with(crate::config::LOG_FILENAME_SUFFIX);
        let target = paths.dibe_data.join(&name);
        if SKIPPED.contains(&name.as_str()) || is_log || target.exists() {
            continue;
        }
        clone(&entry.path(), &target)?;
        report.data_entries.push(name);
    }

    if paths.vibe_projects.is_dir() && !paths.dibe_projects.exists() {
        clone(&paths.vibe_projects, &paths.dibe_projects)?;
        report.projects_copied = true;
    }

    let prefixes = [
        (
            paths.vibe_data.to_string_lossy().into_owned(),
            paths.dibe_data.to_string_lossy().into_owned(),
        ),
        (
            paths.vibe_projects.to_string_lossy().into_owned(),
            paths.dibe_projects.to_string_lossy().into_owned(),
        ),
    ];
    let config = paths.dibe_data.join(crate::config::STORE_FILENAME);
    if config.is_file() {
        rewrite_json_file(&config, &prefixes)?;
    }
    if report.projects_copied {
        let pattern = format!(
            "{}/**/*.vibe.json",
            glob::Pattern::escape(&paths.dibe_projects.to_string_lossy())
        );
        for record in glob::glob(&pattern)?.flatten() {
            if rewrite_json_file(&record, &prefixes)? {
                report.records_rewritten += 1;
            }
        }
    }

    std::fs::write(&marker, serde_json::to_vec_pretty(&report)?)?;
    Ok(Some(report))
}

/// `cp -c` clones on APFS: the copy shares the original's blocks until one of them is written.
fn clone(source: &Path, target: &Path) -> Result<()> {
    let output = Command::new("/bin/cp").arg("-c").arg("-R").arg(source).arg(target).output()?;
    if !output.status.success() {
        bail!(
            "cp -c {} failed: {}",
            source.display(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(())
}

/// Rewrite every string (and object key) that starts with a Vibe folder to the DIBE one.
/// Returns whether anything changed; an unreadable record is left as it is.
fn rewrite_json_file(path: &Path, prefixes: &[(String, String)]) -> Result<bool> {
    let text = std::fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let Ok(mut value) = serde_json::from_str::<serde_json::Value>(&text) else {
        return Ok(false);
    };
    if !rewrite_value(&mut value, prefixes) {
        return Ok(false);
    }
    // Same folder, then rename: a reader never sees half a file.
    let temp = path.with_extension("json.tmp");
    std::fs::write(&temp, serde_json::to_vec_pretty(&value)?)?;
    std::fs::rename(&temp, path)?;
    Ok(true)
}

fn rewrite_value(value: &mut serde_json::Value, prefixes: &[(String, String)]) -> bool {
    use serde_json::Value;
    match value {
        Value::String(text) => match rewrite_path(text, prefixes) {
            Some(new) => {
                *text = new;
                true
            }
            None => false,
        },
        Value::Array(items) => items
            .iter_mut()
            .fold(false, |changed, item| rewrite_value(item, prefixes) | changed),
        Value::Object(map) => {
            let mut changed = false;
            let old = std::mem::take(map);
            for (key, mut item) in old {
                changed |= rewrite_value(&mut item, prefixes);
                let key = rewrite_path(&key, prefixes).inspect(|_| changed = true).unwrap_or(key);
                map.insert(key, item);
            }
            changed
        }
        _ => false,
    }
}

/// Only whole path components: `.../Vibe/x` and `.../Vibe` match, `.../Vibes` does not.
fn rewrite_path(text: &str, prefixes: &[(String, String)]) -> Option<String> {
    prefixes.iter().find_map(|(from, to)| {
        let rest = text.strip_prefix(from.as_str())?;
        (rest.is_empty() || rest.starts_with('/')).then(|| format!("{to}{rest}"))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A throwaway home folder, removed when the test ends.
    struct TempRoot(PathBuf);
    impl TempRoot {
        fn new() -> Self {
            let dir = std::env::temp_dir().join(format!("dibe-import-{}", crate::ffmpeg::random_string(8)));
            std::fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }
        fn path(&self) -> &Path {
            &self.0
        }
    }
    impl Drop for TempRoot {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn fixture() -> (TempRoot, ImportPaths) {
        let root = TempRoot::new();
        let paths = ImportPaths {
            vibe_data: root.path().join("Application Support/github.com.thewh1teagle.vibe"),
            dibe_data: root.path().join("Application Support/io.github.akucbruhu0-cyber.dibe"),
            vibe_projects: root.path().join("Documents/Vibe"),
            dibe_projects: root.path().join("Documents/DIBE"),
        };
        let vibe = &paths.vibe_data;
        std::fs::create_dir_all(vibe.join("recordings-in-progress")).unwrap();
        std::fs::create_dir_all(vibe.join("handoff")).unwrap();
        std::fs::write(vibe.join("ggml-model.bin"), b"model bytes").unwrap();
        std::fs::write(vibe.join("server.json"), b"{}").unwrap();
        std::fs::write(vibe.join("log_2026-10-28.txt"), b"log").unwrap();
        let model = vibe.join("ggml-model.bin").to_string_lossy().into_owned();
        let projects = paths.vibe_projects.to_string_lossy().into_owned();
        let config = serde_json::json!({
            "model.path": model,
            "model.displayNames": { model.clone(): "Custom model" },
            "transcription.modelOptions": { "lang": "he" },
            "projects.path": projects,
            "other": format!("{projects}s/not-a-child"),
        });
        std::fs::write(vibe.join("app_config.json"), serde_json::to_vec(&config).unwrap()).unwrap();
        let project = paths.vibe_projects.join("Meeting-20261028-101500");
        std::fs::create_dir_all(&project).unwrap();
        std::fs::write(project.join("audio.wav"), b"wav").unwrap();
        let record = serde_json::json!({
            "version": 1, "sourcePath": project.join("audio.wav"), "modelPath": model, "audioFile": "audio.wav", "segments": [],
        });
        std::fs::write(project.join("transcript.vibe.json"), serde_json::to_vec(&record).unwrap()).unwrap();
        (root, paths)
    }

    fn read_json(path: &Path) -> serde_json::Value {
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    #[test]
    fn copies_vibe_data_once_and_points_dibe_at_its_own_copies() {
        let (_root, paths) = fixture();
        let report = import(&paths).unwrap().expect("first launch imports");
        assert_eq!(std::fs::read(paths.dibe_data.join("ggml-model.bin")).unwrap(), b"model bytes");
        assert!(report.projects_copied);
        assert_eq!(report.records_rewritten, 1);

        let config = read_json(&paths.dibe_data.join("app_config.json"));
        let dibe_model = paths.dibe_data.join("ggml-model.bin").to_string_lossy().into_owned();
        assert_eq!(config["model.path"], dibe_model);
        assert_eq!(config["model.displayNames"][&dibe_model], "Custom model");
        assert_eq!(config["projects.path"], paths.dibe_projects.to_string_lossy().as_ref());
        assert!(
            config["other"].as_str().unwrap().contains("/Vibes/not-a-child"),
            "only whole path components are rewritten"
        );

        let record = read_json(&paths.dibe_projects.join("Meeting-20261028-101500/transcript.vibe.json"));
        assert!(record["sourcePath"]
            .as_str()
            .unwrap()
            .starts_with(paths.dibe_projects.to_string_lossy().as_ref()));
        assert_eq!(record["modelPath"], dibe_model);

        assert_eq!(import(&paths).unwrap(), None, "the import runs once");
    }

    #[test]
    fn leaves_vibe_untouched() {
        let (_root, paths) = fixture();
        let before = std::fs::read(paths.vibe_data.join("app_config.json")).unwrap();
        let record = paths.vibe_projects.join("Meeting-20261028-101500/transcript.vibe.json");
        let record_before = std::fs::read(&record).unwrap();
        import(&paths).unwrap();
        assert_eq!(std::fs::read(paths.vibe_data.join("app_config.json")).unwrap(), before);
        assert_eq!(std::fs::read(&record).unwrap(), record_before);
    }

    #[test]
    fn skips_the_running_server_vibes_own_recordings_the_phone_identity_and_logs() {
        let (_root, paths) = fixture();
        let report = import(&paths).unwrap().unwrap();
        for name in ["server.json", "recordings-in-progress", "handoff", "log_2026-10-28.txt"] {
            assert!(!paths.dibe_data.join(name).exists(), "{name} must not be copied");
            assert!(!report.data_entries.contains(&name.to_string()));
        }
    }

    #[test]
    fn never_overwrites_what_dibe_already_has() {
        let (_root, paths) = fixture();
        std::fs::create_dir_all(&paths.dibe_data).unwrap();
        std::fs::write(paths.dibe_data.join("ggml-model.bin"), b"dibe's own").unwrap();
        import(&paths).unwrap();
        assert_eq!(std::fs::read(paths.dibe_data.join("ggml-model.bin")).unwrap(), b"dibe's own");

        let (_root, paths) = fixture();
        std::fs::create_dir_all(&paths.dibe_data).unwrap();
        std::fs::write(paths.dibe_data.join("app_config.json"), b"{}").unwrap();
        assert_eq!(
            import(&paths).unwrap(),
            None,
            "a DIBE that already has settings is never imported over"
        );
    }

    #[test]
    fn does_nothing_without_vibe() {
        let root = TempRoot::new();
        let paths = ImportPaths {
            vibe_data: root.path().join("missing"),
            dibe_data: root.path().join("dibe"),
            vibe_projects: root.path().join("Documents/Vibe"),
            dibe_projects: root.path().join("Documents/DIBE"),
        };
        assert_eq!(import(&paths).unwrap(), None);
        assert!(!paths.dibe_data.exists());
    }
}
