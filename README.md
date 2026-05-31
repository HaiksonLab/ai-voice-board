# AI Voice Board

An Android custom keyboard (InputMethodService) that records your voice, transcribes it via the OpenAI Whisper API, and types the result wherever your cursor is.

Inspired by and ported from [ai-voice-input](https://github.com/HaiksonLab/ai-voice-input) — a Windows AutoHotkey script with the same idea.

---

## Features

- **Single-row keyboard UI** — minimal, stays out of the way
- **Voice recording** → Whisper API → text inserted at cursor
- **Stop + Send** — transcribe and submit in one tap (works in ChatGPT, Telegram, etc.)
- **Retry** — resend last recording if API failed (no internet, expired key, etc.)
- **Paste last** — reinsert last recognised text without re-recording
- **Backspace** — tap to delete one char, hold to repeat; swipe up on the **Clear** pill to wipe the whole field
- **IME switcher** — one tap returns to your previous keyboard (Yandex, Gboard, etc.)
- **Auto-cancel** — recording/transcription is cancelled when the keyboard is hidden
- **Text formatting** — optional newline after sentence-ending punctuation (`.!?`)
- **Dark theme**, circular vector-icon buttons

---

## Requirements

- Android 8.0+ (API 26)
- OpenAI API key with access to Whisper / GPT-4o transcription models

---

## Build

```bash
# Clone and open in Android Studio, or build from CLI:
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Requires **Android Studio Hedgehog+** or **JDK 17+**.

---

## Install

1. Copy the APK to your phone (USB / Telegram / any method)
2. Enable **Install from unknown sources** for your file manager
3. Install the APK
4. Open **AI Voice Board** app
5. Tap **Grant microphone permission**
6. Tap **Enable keyboard** → enable in system settings
7. Tap **Select AI Voice Board** → choose as input method
8. Enter your **OpenAI API key** and tap **Save**

---

## Keyboard layout

```
[⌨]  status text  [⌫] [🎤] [↵]     ← idle
[⌨]  ● 0:05       [✕] [⏹] [↑]     ← recording
[⌨]  Transcribing… [✕] [⠋] [↑·]   ← transcribing
```

| Button | Tap | Long press |
|--------|-----|------------|
| ⌨ | Switch to previous keyboard | Menu: Retry / Paste last / Settings |
| 🎤 | Start recording | — |
| ⌫ | Delete char | Repeat; swipe up → **Clear** pill |
| ↵ | Insert newline | — |
| ✕ | Cancel recording / transcription | — |
| ⏹ | Stop → transcribe → paste | — |
| ↑ | Stop → transcribe → paste → send | — |

---

## Configuration

All settings are in the **AI Voice Board** app:

| Setting | Default | Description |
|---------|---------|-------------|
| API Key | *(empty)* | Your OpenAI API key (`sk-…`) |
| Model | `gpt-4o-transcribe` | Transcription model |
| Prompt | technical terms | Hint for the model (terminology, language style) |
| Proxy | *(empty)* | SOCKS5 proxy, e.g. `socks5h://127.0.0.1:1080` |
| Line break after sentence | on | Insert `\n` after `.!?` |

---

## Reference project

**Windows version:** [ai-voice-input](https://github.com/HaiksonLab/ai-voice-input) — AutoHotkey v2 script with the same Whisper-based voice input, configurable hotkeys, wake-word detection, and more.

---

## License

MIT
