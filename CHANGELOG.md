# Changelog

## 1.0.0 — Initial public release preparation

This version packages the existing development feature set under the 1.0.0 version name. This entry describes included capabilities; it is not a claim that every feature was added in this single release or tested on every website.

### Downloads and browser assistance

- Video/audio downloads through yt-dlp, with MP4 compatibility and best-quality options.
- MP3/M4A audio output, playlists, subtitles, imported cookies, and optional parallel fragments.
- Browser-assisted media detection with separate WebView login sessions.
- Expanded documentation for cookie workflows and social-media audio-only/fragment downloads.

### Stream recording

- Live recording with stop/save and discard controls.
- Automatic renewal attempts and manual browser recovery for unresolved 403 stalls.
- Five-minute automatic stop/save for unresolved stalls without user interaction.
- Audio timeline finalization, original-recording fallback, and companion timing diagnostics when available.
- Saving/finalization progress and diagnostic logging.

### Collections and PDFs

- Supported post, comic, album, gallery, and selected result-page collection downloads.
- Natural page ordering, enlarged previews, manual reordering, and PDF creation.
- Companion video links and optional embedded video attachments with clickable filename areas.
- Combined Rule34 API credential text parsing.

### Native processing and presentation

- ARM64 assembly helpers and a JNI bridge for selected parsing, sorting, media validation, and PDF/playlist serialization operations.
- Managed fallbacks for other supported architectures.
- Updated launcher artwork and repository documentation for the planned public release.

### Known limitations

- Luscious album listings and Tailspace browse listings remain unsupported.
- Site authentication, layout changes, expiring URLs, and background behavior can still interrupt downloads/recordings.
- Google-based sign-in can produce a white page in the embedded browser.
- Audio timing and PDF video opening depend on source data and viewer support respectively.
- DRM streams are unsupported. Native optimizations do not promise an end-to-end speed increase.
