🇬🇧 **English** | [🇷🇺 Русский](README.ru.md)

[![Latest release](https://img.shields.io/github/v/release/HaiksonLab/ai-voice-board)](https://github.com/HaiksonLab/ai-voice-board/releases) [![Changelog](https://img.shields.io/badge/changelog-view-blue)](CHANGELOG.md)

# 🎙 AI Voice Board

Android voice-to-text keyboard powered by OpenAI Whisper API.
Tap the mic — speak — text gets typed into any input field.

A companion to [ai-voice-input](https://github.com/HaiksonLab/ai-voice-input) (the Windows version) — same idea, native on Android as a custom keyboard.

## Features

- Custom keyboard (InputMethodService) — works in any app with a text field
- Speech recognition via OpenAI Whisper API (excellent quality for any language)
- Text typed at the cursor position via `InputConnection`
- **Stop + Send** — transcribe and submit the message in one tap (ChatGPT, Telegram, etc.)
- **Cancel** while recording or transcribing — abort without inserting text
- **Retry** — resend the last recording if the API call failed (no internet, expired key)
- **Paste last** — re-insert the last recognized text without re-recording
- Backspace with hold-to-repeat and a swipe-up **Clear** overlay to wipe the field
- One-tap switch back to your previous keyboard (Yandex, Gboard, etc.)
- Live spectrum equalizer visualization while recording
- Recording auto-cancels when the keyboard is hidden; transcription keeps running and inserts the text only if the keyboard is still open (otherwise available via **Paste last**)
- In-app update check & one-tap install from GitHub Releases (badge + menu action)
- Punctuation palette on the New-line key (long-press) and a recognition history of the last 10 texts (from the mic long-press menu)
- Optional line break after each sentence
- All settings in the app — no code editing needed

## Requirements

- Android 8.0+ (API 26)
- OpenAI API key with Audio API access: [platform.openai.com/api-keys](https://platform.openai.com/api-keys)
- Internet connection for transcription

## Installation

**Option A — APK (recommended):**
1. Download `AiVoiceBoard-vX.X.X.apk` from the [latest release](https://github.com/HaiksonLab/ai-voice-board/releases/latest)
2. Copy it to your phone and install (allow *Install from unknown sources* if prompted)
3. Open the **AI Voice Board** app
4. Tap **Grant microphone permission**
5. Tap **Enable keyboard** → enable it in system settings
6. Tap **Select AI Voice Board** → choose it as input method
7. Enter your OpenAI API key and tap **Save**

**Option B — Build from source (requires Android Studio / JDK 17):**
1. Download or clone the repository
2. Open in Android Studio, or build from CLI:
   ```bash
   ./gradlew assembleRelease
   # APK → app/build/outputs/apk/release/app-release.apk
   ```

## Configuration

All settings are in the app's settings screen:

| Parameter | Description | Default |
|---|---|---|
| API Key | OpenAI API key | — |
| Model | Transcription model | `gpt-4o-transcribe` |
| Prompt | Hint for the model (optional) | technical terms |
| Proxy | SOCKS5 proxy for OpenAI API requests (optional) | empty |
| Line break after sentence | Insert `\n` after `.!?` | on |

**Proxy** example:
```
socks5h://127.0.0.1:1080
```

**Models:** for the current list of supported Whisper models see [platform.openai.com/docs/models](https://platform.openai.com/docs/models).

**Prompt:** optional parameter. Helps the model more accurately recognize professional terms, names, and abbreviations. Add keywords from your domain.

## Keyboard

The keyboard is a single row with three states — idle, recording, and transcribing.

| Button | Tap | Long press |
|---|---|---|
| ⌨ | Switch to previous keyboard | Menu: Settings / Check for update — a red dot marks an available update |
| 🎤 | Start recording | Menu: Paste last / Retry last / History (last 10 texts) |
| ⌫ | Delete one character | Repeat; swipe up → **Clear** the whole field |
| ↵ | Insert a newline | Palette: space, comma, dot, question, Send, Enter |
| ✕ | Cancel recording / transcription | — |
| ⏹ | Stop → transcribe → paste | — |
| ↑ | Stop → transcribe → paste → send (Enter) | — |

## Known Limitations

### Hallucinations on silence

If nothing was said during recording and you press stop — the API may return arbitrary text instead of an empty string: a fragment from the `Prompt` or a thematically similar phrase.

This is **expected behavior** of Whisper and `gpt-4o-transcribe`: models always try to decode something into text and have no "return empty when no speech" mode. Having a `Prompt` amplifies the effect — the model completes text from its context.

**Simple solution:** if you started recording and said nothing — press **✕** (cancel) instead of stop. Cancel aborts recording without calling the API.

### Sending in some apps

The **↑** (Stop + Send) button uses the editor's declared action (`IME_ACTION_SEND`). Most chat apps respect it. In **Telegram**, sending via the keyboard action only works when *Settings → Chat Settings → Send by Enter* is enabled.

## Cost

Whisper API is billed by audio duration.
At time of writing the price is approximately **$0.006 per minute** — about **$1 per 3 hours** of speech.

Current pricing: [platform.openai.com/docs/pricing](https://platform.openai.com/docs/pricing)

## License

MIT
