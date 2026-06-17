# Changelog

All notable changes to this project will be documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.2.0] - 2026-06-02

### Added
- Punctuation palette on the New-line key (long-press ↵): space, comma, dot, question — these keep the palette open so you can insert several in a row — plus a divider and two send actions: smart **Send** (`performEditorAction`) and **Enter** (`KEYCODE_ENTER` keyevent)
- Mic long-press menu with **Paste last** / **Retry last**
- Recognition **history**: the mic menu's History action lists the last 10 recognized texts; tap one to insert it, with **Clear history**

### Changed
- **Paste last** / **Retry last** moved from the left switch-keyboard menu to the mic long-press menu
- Menu popups are non-focusable so they no longer hide the keyboard (notably in Chrome); any open dropdown closes when the keyboard is hidden or another opens

## [1.1.0] - 2026-06-02

### Added
- Live spectrum equalizer (FFT) shown in the keyboard row while recording
- In-app update check via GitHub Releases: a badge on the switch-keyboard button and an **Update app** menu action appear when a newer version is found; auto-check on keyboard open (at most once per hour, silent on error) plus a manual **Check for update** with an inline spinner
- **Update app** downloads the APK via the system DownloadManager and launches the installer
- **Info** button (opens the release changelog) and **GitHub** button (opens the repository) in the overflow menu
- Toast on transcription error, in addition to the status line

### Changed
- Transcription is no longer cancelled when the keyboard is hidden — it finishes; the recognized text is inserted only if the keyboard is still open, otherwise it stays available via **Paste last**
- Increased the delay before the auto-Send action to 250 ms
- Overflow menu reimplemented as a custom popup (to support the inline update spinner and action buttons)

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
