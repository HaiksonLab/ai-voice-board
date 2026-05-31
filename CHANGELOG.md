# Changelog

## [1.0.0] - 2026-05-31

Initial release.

### Features
- Voice recording via Android `AudioRecord` (16 kHz, 16-bit mono WAV)
- Transcription via OpenAI Whisper API (`whisper-1`, `gpt-4o-transcribe`, `gpt-4o-mini-transcribe`)
- Single-row keyboard UI with idle / recording / transcribing states
- Stop + Send button — transcribe and submit (150 ms delay + `IME_ACTION_SEND`)
- WAV cache — retry last recording on API failure
- Paste last recognised text
- Backspace with hold-to-repeat and swipe-up Clear overlay
- IME switcher with overflow menu (long press)
- Auto-cancel on keyboard hide (`onWindowHidden`)
- Text formatting — newline after sentence-ending punctuation
- Settings screen with mic permission request, API key, model, prompt, proxy
- Dark theme, circular vector-icon buttons, `CenteredImageSpan` mic icon in status
- `onEvaluateFullscreenMode() = false` — prevents keyboard from covering input in some apps
