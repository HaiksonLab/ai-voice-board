# Changelog

All notable changes to this project will be documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.0.0] - 2026-05-31

### Added
- Initial release
- Custom keyboard (InputMethodService) with voice-to-text via OpenAI Whisper API
- Voice recording via Android `AudioRecord` (16 kHz, 16-bit mono WAV)
- Text insertion at cursor position via `InputConnection`
- Single-row keyboard UI with idle / recording / transcribing states
- **Stop + Send** — transcribe and submit in one tap
- **Cancel** while recording or transcribing
- **Retry** — resend the last recording on API failure (cached WAV)
- **Paste last** — re-insert the last recognized text
- Backspace with hold-to-repeat and swipe-up **Clear** overlay
- One-tap switch to previous keyboard, overflow menu via long press
- Auto-cancel recording/transcription when the keyboard is hidden
- Optional line break after sentence-ending punctuation (`.!?`)
- Settings screen: API key, model, prompt, proxy, formatting, mic permission
- Configurable `Model`, `Prompt`, and SOCKS5 `Proxy`
