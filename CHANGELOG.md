# Changelog

## 1.0.87 — Release target

Changes from **1.0.75 through 1.0.87**, consolidated for the update from **1.0.74**. The reviewed 1.0.87 source declares version code **146**. Earlier entries and their original validation records remain below.

### Saving posts and reviewing text

- Added Main's **Save post as text** workflow, initially for title/body TXT export and expanded to **Plain text (.txt)** and **HTML copy (.html)**. Exports go to **Downloads/yt-dlp Mobile**. HTML preserves supported basic formatting and links while excluding page scripts and embedded media.
- Added persistent saved-post history and opening saved exports. Saved posts now share the completed-history timeline with media, sorted by saved time. Older post records can recover their save date from Android's media database.
- Added exact-post matching for X, including supported status routes, body/author identity checks and handling for clickable post bodies or layouts missing an article wrapper.
- Added a review path for a single exposed, semantically scoped X body when exact ID matching cannot be established. Ambiguous or unverified candidates require review rather than automatic saving.
- Added manual text selection on the browser page and **Review selected text**. The editor supports checking/editing the draft, normal text-field Paste, **Back to page**, **Retry auto**, and explicit Save.
- Added capture diagnostics with failure reasons and article/body/match/candidate counts. Captured text and HTML bodies are omitted from diagnostic summaries.
- Added scoped Facebook detail-card matching to distinguish the intended visible post from suggestions. Expanded text can include separate spans, inline mentions and paragraph breaks. Expanding the post clears stale excerpt selections so review can read the current body again.
- Added reviewable authored captions for supported Facebook Reel detail routes when the caption can be associated with the exposed player. A truncated, missing or unowned caption is not prefilled. Video, subtitles and neighboring Reels are excluded.
- Reddit capture excludes comments and unrelated controls. The later 1.0.84–1.0.87 patches refine Save post, version metadata and associated diagnostics/fixtures.

### Browser, downloads and recording

- Blocked website requests to create new windows/tabs in Browser Assist, monitoring browsers, custom-source teaching and background monitoring rendering. Current-window navigation continues. Popup-block events have diagnostic entries.
- Added AstalaVR's eligible verified-MP4 ranged fallback after a 403 response, complete-byte checks and sanitized mobile range/status/header diagnostics.
- Improved **Stop & save** handling for shutdown races, a short FFmpeg shutdown grace period followed by forced stop when required, stability checks before export, retry after output changes and retained recovery when saving cannot safely finish.

### Collections, monitoring and PDFs

- Improved collection image selection to prefer larger alternatives belonging to the same page, retain identifiers/filenames/order and use the prior URL as a fallback.
- Improved Tailspace duplicate-preview handling. The recorded 1.0.76 check retained the expected order for a 69-page collection. Broader comic-monitoring gaps discussed at that time were left for separate work.
- Made routine no-new-page comic-monitoring notifications quiet while retaining new-page/download alerts and recorded check/error information.
- Added **Add images to existing PDF**, selection and reordering of new images, and **Save updated PDF** to write a separate copy. Existing pages are imported from the source document rather than rasterized again.
- Added PdfBox-Android **2.0.27.0** for PDF appending and a corresponding dependency-notice entry in the source patch. Encrypted/password-protected, digitally signed and empty source PDFs are rejected.

### Included development versions

| Version | Addition or refinement |
| --- | --- |
| 1.0.75 | AstalaVR MP4/range recovery diagnostics and Stop & save reliability |
| 1.0.76 | Larger same-page collection images and Tailspace preview handling |
| 1.0.77 | Main Save post as text and initial post-body extraction |
| 1.0.78 | TXT/HTML format choice and quiet no-new-page comic checks |
| 1.0.79 | Persistent saved-post records and opening saved exports |
| 1.0.80 | Saved posts integrated into dated Queue & history entries |
| 1.0.81 | X post identity matching and broader identifiable article/post extraction |
| 1.0.82 | Appending image pages to existing PDFs |
| 1.0.83 | Website new-window/tab blocking across embedded-browser routes |
| 1.0.84 | Manual selection/review fallback for Save post |
| 1.0.85 | Clickable/unwrapped post bodies, selection flow and capture diagnostics |
| 1.0.86 | Rendered X fallback, Facebook detail matching and review refinements |
| 1.0.87 | Expanded Facebook bodies, Reel-caption review, stale-excerpt clearing and scoped X review fallback |

### Validation and remaining limits

- Historical focused checks recorded 13 passing tests for 1.0.75, 11 for 1.0.76, six extraction checks for 1.0.77, source/XML/JavaScript checks for 1.0.78 and 17 simulated checks for 1.0.85. These counts describe individual development revisions.
- Later patches include JVM extraction fixtures and a Python browser fixture runner that exercises production capture/selection JavaScript in Chromium using intercepted pages. Their presence does not establish a passing full Android build or phone test for the final 1.0.87 APK.
- User reports continued to show Save post failures on real mobile layouts during these revisions. Automatic capture remains dependent on a readable, identifiable page. Check the review draft and use diagnostics for a failed case.
- The earlier AniWaves report is not established as fixed by these patches. Earlier access, HTTP 403 and validation caveats still apply where a documented result has not resolved them.
- Final signed-APK and device tests for export, popup handling, recording, collections and PDF updates require results for that exact build.

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
