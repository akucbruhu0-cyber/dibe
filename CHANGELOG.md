# Changelog

## DIBE 1.1.0 — 2026-10-28

A live transcript to follow a lecture or a meeting with, and copy from, for hours.

### New

- **A recording screen made for reading.** The live transcript fills the window and grows with it, at a text size of your choosing, justified to both edges, as timed lines or as one block of prose without times.
- **Edit while it is being written.** The live transcript is one editable document: Cmd+A, the arrow keys, selection and Backspace cross from sentence to sentence, and new sentences are added at the end without touching what you are editing. Your edits are kept in downloads and in the recording's project.
- **Copy without times.** Times are drawn beside the text, so a copy takes only the words.
- **Download the transcript so far** in the middle of a recording (`.txt`, `.srt`, `.vtt`), while it goes on.
- **Pause and resume**, besides stop. A paused recording saves and transcribes nothing; one second of silence marks the pause so the last sentence before it is transcribed in full.
- **Back to the live transcription** — a button over any other screen while a recording runs; switching screens never stops it.
- **Transcript and Summary tabs** in the toolbar once a transcript has an AI summary.
- **Delete the recording only** — in a project's menu: frees the disk the audio takes and keeps the transcript and summary. An imported original file is never touched.

### Battery

- Live transcription uses far less battery. Whisper always processes a full 30-second window, so asking it four times a second cost about 25–30 times the work of transcribing the same file. Audio now goes to the engine every 3 seconds while words are shown as they are spoken, and every second otherwise.
- New setting: Settings → Recording → Live transcription → **Show words while they are spoken**. Off saves the most battery; each sentence then appears about a second after it ends.

### Fixes

- **Exports show the summary's formatting, not its Markdown.** Word documents get real headings, bold and italic, lists and a rule instead of `###`, `**` and `---`; PDF and HTML likewise; plain text and subtitles get the words with bullets. Automatic exports included.
- In PDF exports, a right-to-left sentence with bold words kept its final period at the wrong end of the line.
- After a long pause, live transcription reconnects quietly instead of showing "Live transcription paused".

## DIBE 1.0.0 — 2026-10-28

The first release of DIBE, forked from [Vibe 3.2.2](https://github.com/thewh1teagle/vibe/releases/tag/v3.2.2).

### New

- **Live transcription while recording.** The engine transcribes each sentence as soon as you pause, with a grey preview of the sentence still being spoken. At the end, keep the live transcript or transcribe the recording again; an incomplete live transcript (a gap, a server problem) triggers a full transcription automatically.
- **Google Gemini** as an AI provider for summaries and dictation clean-up (default model `gemini-3.8-flash`).
- **Model storage** in Settings → Models: total size, byte-identical duplicates and leftover files, deletion with a confirmation.
- **Its own identity beside Vibe:** DIBE name and green icon, `dibe://` links, its own data and projects folders. The first launch copies Vibe's models, settings and projects with an APFS clone (no extra disk space) and rewrites the paths inside them; Vibe itself is never modified.

### Reliability

- Disk writes moved off the real-time audio thread (lock-free ring buffer); the WAV is checkpointed every 5 seconds.
- In-progress recordings live in the app data folder and are recovered as projects after a crash or force-quit; an unreadable capture is never deleted.
- Only one recording runs at a time — the dictation hotkey can no longer stop a meeting recording.
- A device that disconnects ends only its own track; the recording continues on the others.
- A failed merge keeps both devices' audio.
- Stopping a live recording keeps the audio recoverable until the project is saved.
- Audio devices are identified by stable ids, and a Mac without a default microphone can still record system audio.
- Cancelling a transcription takes effect immediately; the next queued file waits for the server instead of failing.
- Speaker-diarization failures are reported instead of silently dropping speakers.
- Uploads are streamed to disk instead of being held in memory twice.

### Security and privacy

- The local API requires a per-launch token and refuses browser-origin requests; `server.json` tells local tools the port and token.
- All analytics removed.
- Automatic updates disabled (DIBE is updated by rebuilding from source).

### Fixes

- The window can be dragged by its title bar on macOS (the overlay title bar lacked the `start-dragging` permission).
