//! A transcript as a note in Apple Notes, on this Mac. Notes syncs it through the user's own
//! iCloud if they use it; DIBE talks only to the local Notes app.

use eyre::{bail, Result};

/// Title and body arrive as arguments, never spliced into the script, so no text can break it.
#[cfg(target_os = "macos")]
const SCRIPT: [&str; 3] = [
    "on run argv",
    "tell application \"Notes\" to make new note with properties {name:item 1 of argv, body:item 2 of argv}",
    "end run",
];

/// `html` is the note body: Notes stores notes as HTML and shows the first line as the title.
#[tauri::command]
pub async fn send_to_apple_notes(title: String, html: String) -> Result<(), super::CommandError> {
    tauri::async_runtime::spawn_blocking(move || make_note(&title, &html))
        .await
        .map_err(|error| eyre::eyre!("{error}"))??;
    Ok(())
}

#[cfg(target_os = "macos")]
fn make_note(title: &str, html: &str) -> Result<()> {
    let mut command = std::process::Command::new("osascript");
    for line in SCRIPT {
        command.args(["-e", line]);
    }
    let output = command.arg(title).arg(html).output()?;
    if !output.status.success() {
        // -1743: the user declined (or has not yet allowed) DIBE to control Notes.
        bail!("{}", String::from_utf8_lossy(&output.stderr).trim());
    }
    Ok(())
}

#[cfg(not(target_os = "macos"))]
fn make_note(_title: &str, _html: &str) -> Result<()> {
    bail!("Apple Notes is only on macOS")
}
