//! `server.json`: where local tools (the agent skill) find the running server's port and bearer token.
//!
//! The port and token are picked per run and live in memory only, so without this file nothing
//! outside the app can reach the API. It sits in the local app data folder (the same one that holds
//! in-progress recordings), is readable by its owner only, and goes away with the server.

use eyre::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use tauri::Manager;

const FILE_NAME: &str = "server.json";

#[derive(Serialize, Deserialize)]
struct ServerInfo {
    port: u16,
    token: String,
}

pub fn path(app_handle: &tauri::AppHandle) -> Result<PathBuf> {
    Ok(app_handle
        .path()
        .app_local_data_dir()
        .context("resolve the app data directory")?
        .join(FILE_NAME))
}

/// Write the file whole or not at all: a reader never sees half of it, and the token is never
/// readable by anyone but the user, not even for a moment.
pub fn write(path: &Path, port: u16, token: &str) -> Result<()> {
    use std::io::Write;

    let contents = serde_json::to_vec(&ServerInfo {
        port,
        token: token.to_string(),
    })?;
    let tmp_path = path.with_extension("json.tmp");
    // A leftover from a crash would keep its own permissions, so start from nothing.
    let _ = std::fs::remove_file(&tmp_path);
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&tmp_path).context("create the server info file")?;
    file.write_all(&contents).context("write the server info file")?;
    file.sync_all().context("flush the server info file")?;
    drop(file);
    std::fs::rename(&tmp_path, path).context("move the server info file into place")?;
    Ok(())
}

/// Delete the file, whoever wrote it. For app exit, when no server outlives us.
pub fn remove(path: &Path) {
    let _ = std::fs::remove_file(path);
}

/// Delete the file only if it still describes `port`. A replacement server writes its own file as
/// soon as it is ready, and the one it replaces must not take that away when it is dropped later.
pub fn remove_if_port(path: &Path, port: u16) {
    let ours = std::fs::read_to_string(path)
        .ok()
        .and_then(|contents| serde_json::from_str::<ServerInfo>(&contents).ok())
        .is_some_and(|info| info.port == port);
    if ours {
        remove(path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("vibe-server-info-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn writes_port_and_token_for_the_owner_only() {
        let dir = scratch("write");
        let path = dir.join(FILE_NAME);

        write(&path, 52844, "0b8f5c1e-token").unwrap();

        let json: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(json, serde_json::json!({ "port": 52844, "token": "0b8f5c1e-token" }));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_rewrite_replaces_the_file_and_leaves_nothing_behind() {
        let dir = scratch("rewrite");
        let path = dir.join(FILE_NAME);

        write(&path, 1111, "old").unwrap();
        write(&path, 2222, "new").unwrap();

        let info: ServerInfo = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!((info.port, info.token.as_str()), (2222, "new"));
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 1, "a temp file was left behind");
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn remove_if_port_keeps_a_replacements_file() {
        let dir = scratch("remove");
        let path = dir.join(FILE_NAME);

        write(&path, 2222, "replacement").unwrap();
        remove_if_port(&path, 1111);
        assert!(path.exists(), "the replacement's file was deleted by the server it replaced");

        remove_if_port(&path, 2222);
        assert!(!path.exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn remove_deletes_whatever_is_there_and_tolerates_nothing() {
        let dir = scratch("exit");
        let path = dir.join(FILE_NAME);

        remove(&path);
        write(&path, 1, "t").unwrap();
        remove(&path);
        assert!(!path.exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
