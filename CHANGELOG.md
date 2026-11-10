# Changelog

## 1.0.74 — Release target

Changes since public release **1.0.63**, consolidated into one release entry. This entry describes the source for the next public build; it does not certify an APK or indicate that a GitHub release has been published.

### Downloads, monitoring and recording

- VK Video Live monitoring accepts the channel root or its `/records` tab. Live checks use the root. Upload checks try the saved link first and try `/records` when the root has no verified finished replays. Both mode runs the checks separately. The first verified listing establishes a baseline without downloading older replays.
- VK live preparation checks the channel API and page state, validates the same broadcast's public DASH source, carries its request headers into FFmpeg and waits for actual media output. Live recording remains unverified; the latest host attempt received HTTP 403 before capture.
- Rumble verifies creator-owned finished uploads where it can, and keeps ambiguous listings preview-only. The embed metadata 403 guidance points to signed-in cookies and Browser Assist. Vimeo Browser Assist can pair verified video and audio playlists from the same playback.
- Monitored downloads use a fresh private copy of imported cookies when available; otherwise they can use the saved in-app browser session. Access failures leave discovered items available for retry.
- YouTube live preparation prefers complete audio/video, can recover an HLS manifest advertised by the exact video's player data, and verifies advancing tracks. A retryable startup failure can trigger one fresh HLS attempt, then one yt-dlp watch-page fallback for the same still-live video if no media was captured.
- Stop & save can repair certain truncated MPEG-TS tails and warns when it cannot safely repair the end. Android 10/11 worker interruption and exact-alarm compatibility checks were corrected.
- The [User Guide](docs/USER_GUIDE.md#troubleshooting) explains that any website or media host can return HTTP 403 and lists possible causes and troubleshooting steps.

### Layout, updates and recovery

- Wide landscape screens use a side panel for supported settings and secondary screens. Dropdown placement and conversion scrolling were corrected; conversion settings, page rows and actions scroll together in both orientations.
- Main displays separate progress and controls for concurrent manual tasks, including per-recording Stop & save, Discard and recovery where available.
- App updates check for stable APK releases and verify compatibility and signing before opening the Android installer. Optional APK backups and recovery settings export/import are separate from media, cookies and queue data. See the [User Guide](docs/USER_GUIDE.md#app-updates-and-recovery).

### Validation and remaining limits

- The full Android debug build and canonical unit suite passed on 1.0.71 (651 passed, two skipped). The last recorded full lint run reported three errors, addressed in source by a later patch but not rechecked with full lint. Two resume/cache integration failures still need fixture diagnosis.
- The 1.0.74 focused run passed 22 tests. Both VK link forms returned the same 40 verified replays from the captured creator pages, and a host replay download decoded with audio and video. These are not final signed-APK or end-to-end Android monitoring results.
- See the [per-site coverage tables](docs/USER_GUIDE.md#website-support-and-dedicated-handlers) for routes, test evidence, caveats and credential requirements.

## 1.0.63 — Released

Previous public release and baseline for this changelog. This entry describes the accumulated feature set, not a claim that every feature was introduced in this one version or works on every website.

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

**Historical planning snapshot:** the version below was an earlier release-preparation draft, not an assertion of a published release. Its limitations are superseded where noted by released 1.0.63 and the later release entry above.

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
