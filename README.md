<p align="center">
  <img width="96px" alt="DIBE logo" src="./design/logo.png" />
</p>

<h1 align="center">DIBE — private transcription, live while you record</h1>

<p align="center">
  <strong>Transcribe audio and video right on your Mac. Nothing leaves your device.</strong>
</p>

<hr />

DIBE is a fork of [Vibe](https://github.com/thewh1teagle/vibe) by [thewh1teagle](https://github.com/thewh1teagle), released under the same MIT license. It keeps everything Vibe does and adds live transcription, a more reliable recorder, Google Gemini for AI summaries, and no analytics at all. It installs beside Vibe (its own name, icon, data folder and `dibe://` links), and on first launch it copies Vibe's models, settings and projects so you can start where you left off — without using extra disk space.

## What DIBE adds

- 🔴 **Live transcription while recording** — finished sentences appear within about a second or two of when they are said, with a grey line for the sentence still being spoken. Keep the live transcript when you stop, or transcribe the recording again for the best accuracy (automatic if the live text was incomplete).
- 💾 **Recordings that survive crashes** — audio is written off the real-time thread, checkpointed every 5 seconds, and an unfinished recording is recovered as a project on the next launch.
- 🎧 **Two devices, one recording** — if one device disconnects (for example AirPods switching to a call), the others keep recording.
- ✨ **Google Gemini** for summaries and dictation clean-up, beside Claude, any OpenAI-compatible API and Ollama.
- 🔒 **No analytics** — the telemetry plugin and every event are removed; DIBE sends nothing to anyone.
- 🛡️ **A locked local API** — the transcription server requires a per-launch token and refuses browser requests.
- 🧹 **Model storage** — see how much space models take, find byte-identical duplicates and leftovers, delete them with a confirmation.
- 🪟 Fixes: the window can be dragged by its title bar on macOS; cancelling a transcription is immediate; uploads are streamed to disk instead of held in memory twice; and more (see the [changelog](CHANGELOG.md)).

## Everything from Vibe

- 🌍 Transcribe almost every language, fully offline
- 🎙️ Transcribe audio, video, the microphone and system audio
- 🎶 Transcribe audio from popular websites (YouTube, Vimeo, Facebook, X and more)
- 📂 Batch transcription
- 📝 Export to `SRT`, `VTT`, `TXT`, `HTML`, `PDF`, `JSON`, `DOCX`
- 🤖 Whisper, Nemotron and Parakeet TDT v3 models, GPU-accelerated
- 👥 Speaker diarization and a stable-timestamps mode for subtitles
- 🌐 Translate to English from any language
- ⌨️ Global dictation hotkey
- 📱 Record from your phone and let the computer transcribe
- 📥 Add custom models from your own site with `dibe://download/?url=<model url>`
- ⚡ A local HTTP API with Swagger docs and an agent skill for Claude Code / Codex
- 🖥️ Command line: `/Applications/DIBE.app/Contents/MacOS/dibe --help`

## Platforms

DIBE is developed and tested on **macOS (Apple Silicon)**. The code still builds for Windows and Linux like Vibe's, but those builds are untested in this fork.

## Download

1. Download `DIBE-<version>-macos-arm64.zip` from the [latest release](https://github.com/akucbruhu0-cyber/dibe/releases/latest) (Apple Silicon Macs).
2. Unzip it and drag `DIBE.app` into `/Applications`.
3. DIBE is not notarized by Apple, so macOS blocks the first launch. Allow it once in **System Settings → Privacy & Security → Open Anyway**, or run:

   ```console
   xattr -dr com.apple.quarantine /Applications/DIBE.app
   ```

4. Open DIBE, pick a model to download, and allow the microphone when asked.

Already using Vibe? DIBE copies your Vibe models, settings and projects on its first launch; Vibe keeps working unchanged.

## Build from source

See [docs/BUILDING.md](docs/BUILDING.md); the short version for macOS:

```console
pnpm install
chore setup            # ffmpeg (and Vibe's released server, replaced on the next line)
chore server-build     # build the transcription server from this repository — required for live transcription
cd desktop && pnpm exec tauri build --bundles app
```

The app lands in `target/release/bundle/macos/DIBE.app`. It is unsigned: sign it locally before moving it to `/Applications` so macOS remembers the microphone permission:

```console
codesign --force --deep --sign - --entitlements desktop/src-tauri/entitlements.plist target/release/bundle/macos/DIBE.app
```

DIBE never updates itself: download the new release, or pull and rebuild.

## Privacy

Transcription runs on your device. DIBE contacts the internet only when you ask it to — downloading a model, transcribing a web link, using an AI provider you configured, or pairing a phone. Details in [PRIVACY.md](PRIVACY.md).

## Contribute

Issues and pull requests are welcome: [CONTRIBUTE.md](CONTRIBUTE.md). Translations: [docs/TRANSLATIONS.md](docs/TRANSLATIONS.md). Security reports: [SECURITY.md](SECURITY.md).

## Credits

DIBE exists because of [Vibe](https://github.com/thewh1teagle/vibe) and its author [thewh1teagle](https://github.com/thewh1teagle) — consider [supporting the original project](https://thewh1teagle.github.io/vibe/?action=support-vibe). Models, model documentation and the phone page are still served by the Vibe project.

Built with [Tauri](https://tauri.app/), [whisper.cpp](https://github.com/ggml-org/whisper.cpp) / [ggml](https://github.com/ggml-org/ggml), OpenAI's [Whisper](https://openai.com/research/whisper), NVIDIA's Parakeet and Nemotron models, and many other open-source libraries.

## License

[MIT](LICENSE) — © thewh1teagle (Vibe) and © akucbruhu0-cyber (DIBE changes).
