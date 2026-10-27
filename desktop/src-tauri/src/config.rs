pub const LOG_FILENAME_PREFIX: &str = "log";
pub const LOG_FILENAME_SUFFIX: &str = ".txt";
// The tracing target is the crate's name, which stays `vibe` (internal, never shown).
pub const DEFAULT_LOG_DIRECTIVE: &str = "vibe=DEBUG";
pub const STORE_FILENAME: &str = "app_config.json";
pub const DOCUMENTS_SUBFOLDER: &str = "DIBE";

/// The name people see: window title, tray, notifications, keep-awake reason.
pub const APP_NAME: &str = "DIBE";
/// Must equal `identifier` in tauri.conf.json (a test checks it).
pub const APP_ID: &str = "io.github.akucbruhu0-cyber.dibe";
pub const REPO_URL: &str = "https://github.com/akucbruhu0-cyber/dibe";

/// The original app DIBE was forked from; its data is copied once on first launch.
pub const VIBE_APP_ID: &str = "github.com.thewh1teagle.vibe";
pub const VIBE_DOCUMENTS_SUBFOLDER: &str = "Vibe";

#[cfg(test)]
mod tests {
    #[test]
    fn app_id_matches_the_tauri_identifier() {
        let conf: serde_json::Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        assert_eq!(conf["identifier"], super::APP_ID);
        assert_eq!(conf["productName"], super::APP_NAME);
    }
}
