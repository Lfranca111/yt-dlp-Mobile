# Changelog

## Unreleased

Work after released **1.0.63**. Development **1.0.64** contains the changes below; it is not classified as a public release here. Future release updates are expected to focus on bug fixes and verification.

### YouTube live preparation

- Prefer a complete HLS audio/video format over a higher-resolution video-only selection without matching audio.
- Recover an advertised HLS manifest from exact-video live player data when browser playback uses SABR/UMP without requesting an HLS playlist.
- Validate manifest scope/expiry, audio-group pairing and advancing live tracks in the added master-verification path; reject VOD, protection, mismatched video evidence and cancelled preparation.
- Preserve existing recording engines and Browser Assist controls. Source/fixture checks passed; final device recording, sound and saving remain release checks.

### Development validation

- Development 1.0.64: debug/release source compilation, 633 executed JVM tests with four skipped, and 52 browser-script assertions passed in the documented validation setup.
- These later source/fixture results do not certify a signed APK or end-to-end phone recording and do not change what was included in release 1.0.63.

## 1.0.63 — Released

Current public release. This entry describes the accumulated feature set, not a claim that every feature was introduced in this one version or works on every website. The remaining development work is bug fixing; later YouTube fixes remain under Unreleased above.

### Downloads, handlers and browser assistance

- Dedicated episode/movie routes and website playback choices for AniWaves, h123movies and WCOStream, plus public-post video resolution and capture-based page routes with normal yt-dlp fallback.
- Supported short/share-link handling without making failed dedicated recognition a failure of the original link's normal fallback.
- Browser Assist recovery after eligible normal-download failures, with interactive playback/sign-in still required where needed.
- Download all for a detected source list, queueing listed candidates with confirmation when audio-only entries are present.
- Universal web-navigation handling for app-opening links when an appropriate web destination can be recovered.
- Separate Zoom out, Zoom in and Reset zoom browser controls.
- Subtitle-only output, reusable download presets, optional resolution/section/playlist-item/rate/metadata/filename controls, and retained segmented-download recovery/partial export.
- Inconclusive h123movies saved-audio language metadata can warn while allowing otherwise valid processed media to export; explicit conflicting language remains a separate failure.

### Shared queue, monitoring and recovery

- Shared media queue with adjustable concurrency, live-task priority, pause/resume, per-task actions, settings snapshots and retained-media diagnostics.
- Upload/live monitoring with independent rule schedules, baseline handling, notify-only/automatic actions, allowed windows/time zones, restart restoration and Android/background diagnostics.
- Tested custom-source discovery rules and separate uploads/live validation, saved-session checks and eligible browser recovery.
- Comic update rules for specific supported comics/albums, optional initial download, future-page append handling and manual review of reordered/replaced pages. PDFs are not rebuilt automatically.
- Clearer held-preparation reason/countdown, player startup/stream diagnostics and retained full monitoring logs.
- Shared-profile monitoring cookie/local-storage reuse and visible agreement/login/verification handling without automatic acceptance or false offline classification.
- Reduced monitoring-card reconstruction and styling flicker while retaining status/progress refreshes.

### Collections, presentation and documentation

- Expanded collection families: nHentai, Doujins, HentaiFox, Hentai2Read, 8Muses, AllPornComic, ILikeComix, MyHentaiGallery, Honeytoon and ComicPorn.xxx, subject to supported page/access structures.
- Listing handlers for exposed Luscious album lists and Tailspace browse results, superseding the older draft's listing limitations.
- Purple/dark visual polish, rounded controls, progress presentation, Light/Follow device themes, orientation preferences and adaptive landscape layouts.
- Pinned navigation on the three main pages; secondary screens and browsers retain their own controls. Palette-state and landscape measurement/scroll corrections are included.
- Reorganized in-app instructions and updated GitHub README/User Guide/Build Guide, including separate dedicated download and monitoring coverage.

### Validation and remaining limits

- Release 1.0.63 source: debug/release compilation, 625 executed JVM tests with four skipped, and 26 browser-script assertions passed in the documented validation setup.
- These are not a signed-APK/native-packaging or end-to-end website/phone certification. Verify the exact signed APK and repeat checks for subsequent releases.
- Authentication, human verification, website changes, expiring media, preview-only/unverified ownership, Android background restrictions and PDF viewer differences can still limit features. Recognition is not universal support, and DRM playback is unsupported.

## 1.0.0 — Initial public release preparation

**Historical planning snapshot:** the version below was an earlier release-preparation draft, not an assertion of a published release. Its limitations are superseded where noted by released 1.0.63 and subsequent Unreleased entries above.

This draft packaged the existing development feature set under the planned 1.0.0 version name. This entry describes included capabilities; it is not a claim that every feature was added in this single release or tested on every website.

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
