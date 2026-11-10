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

**Cumulative changes since the 1.0.0 release-preparation snapshot.** This entry covers additions and changes made after that snapshot through **1.0.63**, including work delivered in intervening development builds. The baseline features are listed under [1.0.0](#100--initial-public-release-preparation). Changes after 1.0.63 remain under [Unreleased](#unreleased).

### Downloads, presets, and recovery

- Added **Subtitles file only** to save captions from the original video page without downloading the video.
- Added **Extra options & presets** for resolution preference, section cuts, selected playlist entries, speed limits, thumbnails, metadata, chapters, and custom filename prefixes. Presets can be created, applied, edited, and deleted without automatically starting a download.
- Improved segmented downloads with compatible fragment reuse, fresh-source retries, partial video export, and local reconstruction from retained media where available. Added fragment diagnostics, optional sample export, and recovery-file cleanup.
- Added bounded rate-limit recovery and serial retries for eligible fragment failures. A first-fragment HTTP 429 can offer Browser Assist after retries when there is no recoverable media.
- Improved filename handling and failure messages. Default titles are length-limited; errors distinguish filename, session, and unusual extension issues and retain the underlying yt-dlp error.
- An unverified redirect, failed page inspection, or unmatched dedicated route can fall through to yt-dlp with the **original submitted link**. Unverified destinations do not become trusted aliases, and a recognized hostname alone does not force a dedicated handler.

### Website playback and video handlers

- Added dedicated episode/movie playback and supported series discovery for **AniWaves, h123movies, and WCOStream**. Compatible moved domains can be recognized from page and player evidence.
- **AniWaves:** Load audio groups and server choices from the episode itself, including SUB, S-Sub, DUB, and other compatible options it exposes. Automatic selection can try eligible servers in the chosen audio group; explicit choices remain strict. Monitoring checks availability again for the actual episode. Added compatible player-response handling and stage-by-stage failure logs.
- **h123movies:** Choose Website default or English audio and a website server for normal and monitoring downloads. Use verified HLS language metadata and local track inspection for English selection. A valid processed file can still export with an English-unverified warning when metadata is inconclusive. Explicitly conflicting or missing audio remains a failure. Added server preparation and playback diagnostics.
- **WCOStream:** Added individual episode playback, compatible quality selection and fallback, and supported series listings. A series index is not itself an episode.
- Added exact-post public video resolution for supported **Bluesky, Mastodon (mastodon.social), Instagram, Threads, Facebook, Reddit, Snapchat, Tumblr, TikTok, and Pinterest** links. Normal yt-dlp remains available when a verified video cannot be obtained. X continues to use the installed extractor.
- Added bounded in-family redirects for supported TikTok, Reddit, Facebook, Pinterest, and Snapchat short/share links. Snapchat supports named and username-free Spotlight links; a verified MP4 response can be handed to yt-dlp without weakening its extension safety checks.
- Added direct recorded-video preparation for eligible **Internet Archive, Dailymotion, Newgrounds, PeerTube (peer.tube and lostpod.space), RuTube, and Vimeo** pages. Recognized **Bilibili International, Kick, Niconico, Odysee, SOOP, and VK Video** routes can still depend on yt-dlp. Eligible SOOP VOD requests can join multipart recordings. Rumble's dedicated live route does not cover recorded videos.

### Browser Assist and live preparation

- Added an interactive Browser Assist handoff after eligible normal-download access or detection failures while Main is in focus. The original page remains available for sign-in, playback, and manual detection.
- Added **Download all** for verified detected sources. It queues one entry per distinct source, not one per quality. Individual selection remains available; audio-only sources are labeled and require confirmation when included in a batch.
- Added shared web handling for app-opening links across assisted downloading, recording, monitoring, and custom setup. The browser can use a matching HTTPS destination or safely recover the original page instead of launching another app. It rejects unrelated targets and redirect loops and does not reload ongoing captures for an app link.
- Added separate **Zoom in**, **Zoom out**, and **Reset zoom** buttons beside Back. Zoom changes the open page without reloading it or changing capture controls.
- Added bounded browser playback recovery for eligible **YouTube, Twitch, and Chaturbate** sources when fresh extraction cannot be verified. Confirmed offline, cancellation, rate limits, and unrelated network or background errors do not trigger an immediate browser retry.
- Automatic live detection now considers intercepted requests, resource history, and player source elements. It preserves observation age, clears old observations when the page changes, and still verifies advancing live media and matching audio.
- Added diagnostics for player readiness, controlled playback errors, missing or stale streams, and verification failures. Playback retries are bounded and do not simulate user gestures or accept site prompts.

### Shared queue, monitoring, and custom sources

- Added a shared media queue for manual and monitored work, with configurable simultaneous downloads/recordings, live-task priority, pause/resume, eligible reordering, per-task actions, history, and saved request settings. Collection transfers are coordinated separately.
- Added independent upload, live, and combined monitoring rules with notify-only and automatic actions, allowed days and time windows, time zones, and restart restoration. An initial successful upload check establishes a baseline rather than bulk-queueing existing videos; a failed check does not replace that baseline.
- Added built-in channel and series routes, including YouTube uploads/Shorts/streams, Twitch archives/clips/live, and supported AniWaves, h123movies, and WCOStream episode listings.
- Added creator- or season-scoped upload routes for supported Dailymotion, Kick, Newgrounds, Niconico, Odysee, PeerTube, and RuTube listings. RuTube season checks can start from an eligible episode. Candidates without verified ownership can remain preview-only and do not auto-queue or establish a new baseline.
- Added live routing for eligible Kick, Odysee, Rumble, SOOP, and VK Video Live sources. Playback still needs fresh evidence of the intended broadcast. VK Video Live adds verified dynamic-DASH capture for eligible monitored jobs. Existing rules are not silently migrated to newer routes.
- Improved Chaturbate live-status checks with scoped session access and eligible browser recovery. Stripchat and XHamsterLive can require Browser Assist for fresh playback observations; live-status recognition does not establish automatic recording support.
- Added custom-source setup with separate upload and live learning/testing, reload and review, and import/export of source rules. A shared source recipe does not include a login session.
- Added monitoring browser modes, saved-session testing, and shared WebView cookie/local-storage reuse where available. Imported cookies do not sign the browser in. Visible login, age, agreement, or verification prompts require user interaction; a blocked check remains unknown rather than falsely offline.
- Added held-preparation reasons and retry countdowns, retained full monitoring logs, and diagnostics for Android background restrictions, detection, verification, playback, service handoff, and saving. Reduced layout and styling flicker while monitoring and comic card statuses refresh.

### Collections, comic updates, and PDF workflow

- Expanded collection support to **nHentai, Doujins, HentaiFox, Hentai2Read, 8Muses, AllPornComic, ILikeComix, MyHentaiGallery, Honeytoon, and ComicPorn.xxx**, subject to supported page types and access requirements.
- Added exposed **Luscious album lists** and **Tailspace browse results**, replacing those limitations in the 1.0.0 snapshot.
- Added comic update rules for specific supported comics, chapters, or albums. Rules can notify or download new pages, optionally save existing pages on the first successful check, and append verified new pages without overwriting files or rearranging manual order.
- Replaced, inserted, or reordered pages require review. Failed checks retain the baseline, pending pages can be retried, and removing a rule keeps saved files. PDFs are not rebuilt automatically.
- Updated the collection and PDF editor layout for reviewing and ordering pages in landscape. Companion video links and optional embedded attachments remain subject to PDF viewer support.

### Appearance, layout, and documentation

- Added purple styling, rounded controls, clearer focus states, selected-page indicators, slim fading scrollbars, and clearer download, recording, queue, and collection progress. Queue styling follows the task's actual state even when two tasks have the same title.
- Added **Appearance & display** with Dark, Light, and Follow device themes, plus Follow Android, Portrait, and Landscape orientation settings. Browser pages keep their own website colors.
- Pinned navigation across Main, Collections, and Monitoring, with a side rail in landscape. Added adaptive layouts for browser controls, monitoring and setup, collection browsing, and the PDF editor. Secondary screens keep their own controls.
- Fixed a browser launch crash related to button styling, corrected palette and Monitoring footer colors, and addressed landscape measurement and scrolling.
- Reorganized the in-app instructions and updated the GitHub README, User Guide, and Build Guide. The guide distinguishes dedicated downloading, monitoring, preview-only routes, normal yt-dlp fallback, and interactive Browser Assist.

### Validation and remaining limits

- Release 1.0.63 source: debug/release compilation, 625 executed JVM tests with four skipped, and 26 browser-script assertions passed in the documented validation setup.
- These checks do not certify a signed APK, native packaging, or end-to-end phone and website behavior. Verify the exact signed APK.
- Authentication, human verification, website changes, expiring media, unverified ownership, Android background restrictions, and PDF viewer differences can still limit features. Recognition is not universal support, and DRM playback is unsupported.
- In 1.0.63, YouTube live preparation can still fail to find usable audio or a continuous stream even when Browser Assist plays the broadcast. The advertised-manifest recovery described under [Unreleased](#unreleased) is separate.

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
