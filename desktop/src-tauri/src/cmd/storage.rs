//! What the models folder costs on disk: which files are byte-identical copies, which are known
//! leftovers, and the one command that may delete them.
//!
//! The scan is the single source of truth. `delete_model_file` runs it again and only removes what
//! it flags as a duplicate or a leftover, so a stale list in the UI (the original deleted in Finder
//! in the meantime) can never take the last copy of a model with it.

use super::download::{is_model_path, sha256_file};
use super::CommandError;
use crate::cmd::app::get_models_folder;
use eyre::{eyre, Context, Result};
use serde::Serialize;
use std::{
    collections::{HashMap, HashSet},
    io::ErrorKind,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};
use tauri::AppHandle;

/// Files an older version installed that nothing reads any more. The ONNX diarization model gave
/// way to the GGUF one (`diarizeModelFilename` in desktop/src/lib/config.ts).
const LEGACY_FILES: &[&str] = &["diar_streaming_sortformer_4spk-v2.1.onnx"];

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind")]
pub enum ModelFileKind {
    /// Read by the app right now (the caller says which files those are). Never deletable.
    InUse,
    /// Byte-identical to `of`, the copy that stays.
    Duplicate {
        of: String,
    },
    Legacy,
    Other,
}

#[derive(Debug, Clone, Serialize)]
pub struct ModelFile {
    pub path: String,
    pub name: String,
    pub size: u64,
    pub kind: ModelFileKind,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelsStorage {
    pub files: Vec<ModelFile>,
    pub total_bytes: u64,
    /// What deleting every duplicate and legacy file would free.
    pub reclaimable_bytes: u64,
}

struct Entry {
    name: String,
    path: PathBuf,
    size: u64,
    modified: SystemTime,
}

/// Regular files directly in `root` (already canonical). Folders, links, dotfiles and partial
/// downloads are not ours to count: a link would have its target counted and hashed, and a
/// `.part` is a download in progress.
// ponytail: no recursion; the models folder is flat. Walk it if models ever get subfolders.
fn list_files(root: &Path) -> Result<Vec<Entry>> {
    let mut files = Vec::new();
    for entry in std::fs::read_dir(root)
        .with_context(|| format!("Failed to read the models folder {}", root.display()))?
        .flatten()
    {
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if name.starts_with('.') || name.ends_with(".part") {
            continue;
        }
        // `DirEntry::metadata` does not follow links, so a link is not a file here.
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if !metadata.is_file() {
            continue;
        }
        files.push(Entry {
            path: root.join(&name),
            name,
            size: metadata.len(),
            // An unreadable time sorts as the oldest, so that file is the one that stays.
            modified: metadata.modified().unwrap_or(UNIX_EPOCH),
        });
    }
    // Directory order is arbitrary; sorted, the tie-break below is deterministic.
    files.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(files)
}

/// Groups of indexes into `files` whose content is byte-identical. Only same-size files are read.
// ponytail: every scan re-hashes same-size files (a couple of seconds for 2x1.6 GB). Cache the
// digest by (path, size, mtime) if scans ever become frequent.
fn identical_groups(files: &[Entry], kinds: &[ModelFileKind]) -> Vec<Vec<usize>> {
    let mut by_size: HashMap<u64, Vec<usize>> = HashMap::new();
    for (index, file) in files.iter().enumerate() {
        // Legacy files are already deletable, so they must not be "the copy that stays". Empty
        // files are all identical and free nothing.
        if file.size > 0 && is_model_path(&file.path) && matches!(kinds[index], ModelFileKind::Other | ModelFileKind::InUse) {
            by_size.entry(file.size).or_default().push(index);
        }
    }

    let mut groups = Vec::new();
    for same_size in by_size.into_values().filter(|group| group.len() > 1) {
        let mut by_digest: HashMap<String, Vec<usize>> = HashMap::new();
        for index in same_size {
            match sha256_file(&files[index].path) {
                Ok(digest) => by_digest.entry(digest).or_default().push(index),
                // A file that cannot be read is never called a copy of anything.
                Err(error) => tracing::warn!("skipping {} in the duplicate check: {error:#}", files[index].name),
            }
        }
        groups.extend(by_digest.into_values().filter(|group| group.len() > 1));
    }
    groups
}

/// `keep` are the files the app reads right now; they come out as `InUse`.
fn scan(dir: &Path, keep: &[String]) -> Result<ModelsStorage> {
    let root = match dir.canonicalize() {
        Ok(root) => root,
        // A folder nothing was ever downloaded to.
        Err(error) if error.kind() == ErrorKind::NotFound => {
            return Ok(ModelsStorage {
                files: Vec::new(),
                total_bytes: 0,
                reclaimable_bytes: 0,
            })
        }
        Err(error) => return Err(error).context(format!("Failed to resolve the models folder {}", dir.display())),
    };
    // A file that does not exist cannot be in the folder, so there is nothing to protect.
    let in_use: HashSet<PathBuf> = keep.iter().filter_map(|path| Path::new(path).canonicalize().ok()).collect();

    let files = list_files(&root)?;
    let mut kinds: Vec<ModelFileKind> = files
        .iter()
        .map(|file| {
            if in_use.contains(&file.path) {
                ModelFileKind::InUse
            } else if LEGACY_FILES.contains(&file.name.as_str()) {
                ModelFileKind::Legacy
            } else {
                ModelFileKind::Other
            }
        })
        .collect();

    for group in identical_groups(&files, &kinds) {
        // The copy that stays: one in use, else the oldest, else the shortest name (a download's
        // random suffix makes the copy's name the longer one). `min_by_key` keeps the first of
        // equals and the files are sorted by name.
        let Some(original) = group.iter().copied().min_by_key(|&index| {
            (
                kinds[index] != ModelFileKind::InUse,
                files[index].modified,
                files[index].name.len(),
            )
        }) else {
            continue;
        };
        for &index in &group {
            // Several identical files can be in use at once; those all stay.
            if index != original && kinds[index] == ModelFileKind::Other {
                kinds[index] = ModelFileKind::Duplicate {
                    of: files[original].name.clone(),
                };
            }
        }
    }

    let total_bytes = files.iter().map(|file| file.size).sum();
    let reclaimable_bytes = files
        .iter()
        .zip(&kinds)
        .filter(|(_, kind)| matches!(kind, ModelFileKind::Duplicate { .. } | ModelFileKind::Legacy))
        .map(|(file, _)| file.size)
        .sum();
    let files = files
        .into_iter()
        .zip(kinds)
        .map(|(file, kind)| ModelFile {
            path: file.path.to_string_lossy().to_string(),
            name: file.name,
            size: file.size,
            kind,
        })
        .collect();
    Ok(ModelsStorage {
        files,
        total_bytes,
        reclaimable_bytes,
    })
}

fn forbidden(message: String) -> CommandError {
    CommandError {
        code: "forbidden".to_string(),
        message,
    }
}

/// Removes one file the scan flags as a duplicate or a leftover. Everything else is refused with
/// `forbidden`: anything outside the models folder (links and `..` included), anything that is not
/// a regular file, and any file the scan does not flag, which covers the files in `keep`. A path
/// that is already gone is fine.
// ponytail: `remove_file`, not move-to-Trash. Move to the Trash if an undo is ever requested.
fn delete(dir: &Path, path: &Path, keep: &[String]) -> Result<(), CommandError> {
    // Looked at as given: resolving a link first would delete its target instead of the link.
    let metadata = match std::fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(eyre::Report::from(error).into()),
    };
    if !metadata.is_file() {
        return Err(forbidden(format!("{} is not a regular file", path.display())));
    }
    let target = path.canonicalize().context("Failed to resolve the file")?;
    let root = dir.canonicalize().context("Failed to resolve the models folder")?;
    if target.parent() != Some(root.as_path()) {
        return Err(forbidden(format!("{} is not in the models folder", path.display())));
    }

    let storage = scan(dir, keep)?;
    let Some(file) = storage.files.iter().find(|file| Path::new(&file.path) == target) else {
        return Err(forbidden(format!("{} is not in the models folder", path.display())));
    };
    match file.kind {
        ModelFileKind::Duplicate { .. } | ModelFileKind::Legacy => {
            Ok(std::fs::remove_file(&target).with_context(|| format!("Failed to delete {}", target.display()))?)
        }
        ModelFileKind::InUse => Err(forbidden(format!("{} is in use", file.name))),
        ModelFileKind::Other => Err(forbidden(format!("{} is not a duplicate or an old file", file.name))),
    }
}

/// Hashing gigabytes is blocking work; keep it off the async runtime.
async fn blocking<T: Send + 'static>(job: impl FnOnce() -> Result<T, CommandError> + Send + 'static) -> Result<T, CommandError> {
    tauri::async_runtime::spawn_blocking(job)
        .await
        .map_err(|error| CommandError::from(eyre!("The storage task failed: {error}")))?
}

/// Size of the models folder and which of its files are copies or leftovers. `keep` are the files
/// the app reads right now: the selected model, the VAD model and the diarization model.
#[tauri::command]
pub async fn scan_models_storage(app: AppHandle, keep: Vec<String>) -> Result<ModelsStorage, CommandError> {
    let dir = get_models_folder(app)?;
    blocking(move || Ok(scan(&dir, &keep)?)).await
}

#[tauri::command]
pub async fn delete_model_file(app: AppHandle, path: String, keep: Vec<String>) -> Result<(), CommandError> {
    let dir = get_models_folder(app)?;
    blocking(move || delete(&dir, Path::new(&path), &keep)).await
}

#[cfg(test)]
mod tests {
    use super::{delete, scan, ModelFileKind, ModelsStorage};
    use std::{
        fs,
        path::{Path, PathBuf},
        time::{Duration, SystemTime, UNIX_EPOCH},
    };

    fn test_dir(name: &str) -> PathBuf {
        let unique = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let dir = std::env::temp_dir().join(format!("vibe-storage-{name}-{}-{unique}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// `age` is seconds after the epoch, so a smaller number is an older file.
    fn write(dir: &Path, name: &str, bytes: &[u8], age: u64) -> PathBuf {
        let path = dir.join(name);
        fs::write(&path, bytes).unwrap();
        fs::File::options()
            .write(true)
            .open(&path)
            .unwrap()
            .set_modified(UNIX_EPOCH + Duration::from_secs(age))
            .unwrap();
        path
    }

    fn keep(dir: &Path, names: &[&str]) -> Vec<String> {
        names
            .iter()
            .map(|name| dir.join(name).to_string_lossy().to_string())
            .collect()
    }

    fn kind(storage: &ModelsStorage, name: &str) -> ModelFileKind {
        storage.files.iter().find(|file| file.name == name).unwrap().kind.clone()
    }

    fn duplicate_of(name: &str) -> ModelFileKind {
        ModelFileKind::Duplicate { of: name.to_string() }
    }

    fn forbidden(result: Result<(), super::CommandError>) {
        assert_eq!(result.unwrap_err().code, "forbidden");
    }

    #[test]
    fn identical_files_marked_duplicate_of_in_use() {
        let dir = test_dir("in-use-original");
        // The in-use copy is the newer one: being in use beats being oldest.
        write(&dir, "in-use.bin", &[7; 2048], 200);
        write(&dir, "copy.bin", &[7; 2048], 100);

        let storage = scan(&dir, &keep(&dir, &["in-use.bin"])).unwrap();

        assert_eq!(kind(&storage, "in-use.bin"), ModelFileKind::InUse);
        assert_eq!(kind(&storage, "copy.bin"), duplicate_of("in-use.bin"));
        assert_eq!(storage.total_bytes, 4096);
        assert_eq!(storage.reclaimable_bytes, 2048);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn in_use_file_cannot_be_deleted() {
        let dir = test_dir("in-use-delete");
        let in_use = write(&dir, "in-use.bin", &[7; 2048], 100);
        write(&dir, "copy.bin", &[7; 2048], 200);

        forbidden(delete(&dir, &in_use, &keep(&dir, &["in-use.bin"])));

        assert!(in_use.exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn path_outside_models_dir_is_rejected() {
        let root = test_dir("outside");
        let dir = root.join("models");
        fs::create_dir_all(&dir).unwrap();
        // Weights of the same shape as the ones a scan would flag, so only the location is wrong.
        let outside = write(&root, "outside.bin", &[1; 2048], 100);
        write(&root, "twin.bin", &[1; 2048], 200);
        write(&dir, "inside.bin", &[1; 2048], 300);

        forbidden(delete(&dir, &outside, &[]));
        forbidden(delete(&dir, &dir.join("../outside.bin"), &[]));
        #[cfg(unix)]
        {
            let link = dir.join("link.bin");
            std::os::unix::fs::symlink(&outside, &link).unwrap();
            forbidden(delete(&dir, &link, &[]));
        }

        assert!(outside.exists());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn legacy_onnx_is_flagged() {
        let dir = test_dir("legacy");
        write(&dir, "diar_streaming_sortformer_4spk-v2.1.onnx", &[3; 4096], 100);
        write(&dir, "unrelated.onnx", &[4; 1024], 100);

        let storage = scan(&dir, &[]).unwrap();

        assert_eq!(
            kind(&storage, "diar_streaming_sortformer_4spk-v2.1.onnx"),
            ModelFileKind::Legacy
        );
        assert_eq!(kind(&storage, "unrelated.onnx"), ModelFileKind::Other);
        assert_eq!(storage.reclaimable_bytes, 4096);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn oldest_identical_file_is_the_original_when_none_in_use() {
        let dir = test_dir("oldest");
        write(&dir, "a-new.bin", &[5; 1024], 300);
        write(&dir, "m-old.bin", &[5; 1024], 100);
        write(&dir, "z-mid.bin", &[5; 1024], 200);

        let storage = scan(&dir, &[]).unwrap();

        assert_eq!(kind(&storage, "m-old.bin"), ModelFileKind::Other);
        assert_eq!(kind(&storage, "a-new.bin"), duplicate_of("m-old.bin"));
        assert_eq!(kind(&storage, "z-mid.bin"), duplicate_of("m-old.bin"));
        assert_eq!(storage.reclaimable_bytes, 2048);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn same_age_prefers_the_shorter_name() {
        let dir = test_dir("tie");
        write(&dir, "ggml-model_72k5tlmu.bin", &[5; 1024], 100);
        write(&dir, "ggml-model.bin", &[5; 1024], 100);

        let storage = scan(&dir, &[]).unwrap();

        assert_eq!(kind(&storage, "ggml-model.bin"), ModelFileKind::Other);
        assert_eq!(kind(&storage, "ggml-model_72k5tlmu.bin"), duplicate_of("ggml-model.bin"));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn different_content_same_size_is_not_duplicate() {
        let dir = test_dir("same-size");
        let mut other = vec![5u8; 1024];
        other[1000] = 6;
        write(&dir, "a.bin", &[5; 1024], 100);
        write(&dir, "b.bin", &other, 200);

        let storage = scan(&dir, &[]).unwrap();

        assert_eq!(kind(&storage, "a.bin"), ModelFileKind::Other);
        assert_eq!(kind(&storage, "b.bin"), ModelFileKind::Other);
        assert_eq!(storage.reclaimable_bytes, 0);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_legacy_file_is_never_the_copy_that_stays() {
        let dir = test_dir("legacy-original");
        write(&dir, "diar_streaming_sortformer_4spk-v2.1.onnx", &[3; 1024], 100);
        write(&dir, "model.bin", &[3; 1024], 200);

        let storage = scan(&dir, &[]).unwrap();

        assert_eq!(kind(&storage, "model.bin"), ModelFileKind::Other);
        assert_eq!(storage.reclaimable_bytes, 1024);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn only_weights_are_compared() {
        // The settings store and logs share the folder by default; a backup copy of one is not
        // something to offer for deletion.
        let dir = test_dir("non-weights");
        write(&dir, "app_config.json", b"{\"a\":1}", 100);
        write(&dir, "app_config copy.json", b"{\"a\":1}", 200);
        write(&dir, "empty-a.bin", b"", 100);
        write(&dir, "empty-b.bin", b"", 200);

        let storage = scan(&dir, &[]).unwrap();

        assert!(storage.files.iter().all(|file| file.kind == ModelFileKind::Other));
        assert_eq!(storage.reclaimable_bytes, 0);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn partial_downloads_dotfiles_and_folders_are_left_out() {
        let dir = test_dir("listing");
        write(&dir, "model.bin", &[1; 64], 100);
        write(&dir, "model.bin.part", &[1; 64], 100);
        write(&dir, ".DS_Store", &[1; 64], 100);
        fs::create_dir_all(dir.join("handoff")).unwrap();
        write(&dir.join("handoff"), "nested.bin", &[1; 64], 100);

        let storage = scan(&dir, &[]).unwrap();

        assert_eq!(storage.files.len(), 1);
        assert_eq!(storage.total_bytes, 64);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_folder_that_does_not_exist_is_empty() {
        let dir = test_dir("missing");
        fs::remove_dir_all(&dir).unwrap();

        let storage = scan(&dir, &[]).unwrap();

        assert!(storage.files.is_empty());
        assert_eq!(storage.total_bytes, 0);
    }

    #[test]
    fn delete_removes_a_duplicate_and_a_legacy_file_but_not_the_original() {
        let dir = test_dir("delete");
        let original = write(&dir, "ggml-model.bin", &[9; 1024], 100);
        let copy = write(&dir, "ggml-model_copy.bin", &[9; 1024], 200);
        let legacy = write(&dir, "diar_streaming_sortformer_4spk-v2.1.onnx", &[2; 512], 100);

        delete(&dir, &copy, &[]).unwrap();
        delete(&dir, &legacy, &[]).unwrap();

        assert!(original.exists());
        assert!(!copy.exists());
        assert!(!legacy.exists());
        // The copy is gone, so the original no longer has a twin and is just a model: not deletable.
        forbidden(delete(&dir, &original, &[]));
        assert!(original.exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn delete_refuses_files_the_scan_does_not_flag() {
        let dir = test_dir("unflagged");
        let model = write(&dir, "only-copy.bin", &[9; 1024], 100);
        let config = write(&dir, "app_config.json", b"{}", 100);

        forbidden(delete(&dir, &model, &[]));
        forbidden(delete(&dir, &config, &[]));
        forbidden(delete(&dir, &dir, &[]));

        assert!(model.exists() && config.exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn deleting_a_file_that_is_already_gone_is_fine() {
        let dir = test_dir("gone");

        delete(&dir, &dir.join("gone.bin"), &[]).unwrap();

        fs::remove_dir_all(dir).unwrap();
    }
}
