# Changelog

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
