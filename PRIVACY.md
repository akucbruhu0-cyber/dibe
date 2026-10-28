# Privacy

DIBE transcribes on your device. It has no analytics, no telemetry, no accounts and no update checks.

## What stays on your Mac

- Recordings, imported media, transcripts, summaries and settings (`~/Library/Application Support/io.github.akucbruhu0-cyber.dibe` and `~/Documents/DIBE`).
- Transcription itself: the `vibe-server` engine inside the app runs locally and listens only on `127.0.0.1`, behind a token that changes every time it starts.

## When DIBE uses the network — only when you ask

| You choose to… | DIBE contacts | What is sent |
|---|---|---|
| Download a model | Hugging Face or the Vibe project's GitHub releases | A normal file download |
| Transcribe a web link | The website, and GitHub once to download `yt-dlp` | The link you entered |
| Use AI summaries or dictation clean-up | The provider you configured: Anthropic (Claude), Google (Gemini), your OpenAI-compatible server, or Ollama (local by default) | The transcript text being summarized or cleaned, with your API key |
| Record from your phone | The pairing page hosted by the Vibe project, and peer-to-peer relays (iroh) | Audio travels encrypted from your phone to your Mac |
| Report a bug | Opens a pre-filled GitHub issue in your browser | Only what you submit there |

Nothing is sent in the background. With AI turned off and every model already downloaded, DIBE works completely offline.

## Compared to Vibe

DIBE removed Vibe's anonymous usage analytics entirely (the Aptabase plugin and every event), and turned off automatic update checks.
