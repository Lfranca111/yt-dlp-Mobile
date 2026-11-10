# yt-dlp Mobile User Guide

This guide covers the **1.0.74 release target**. See the [changelog](../CHANGELOG.md#1074--release-target) for changes since 1.0.63. Website support depends on page type, current site behavior and the installed yt-dlp engine.

## Contents

- [Choosing the right screen and button](#choosing-the-right-screen-and-button)
- [Appearance and display](#appearance-and-display)
- [App updates and recovery](#app-updates-and-recovery)
- [Regular video and audio downloads](#regular-video-and-audio-downloads)
- [Cookies and signed-in downloads](#cookies-and-signed-in-downloads)
- [Browser-assisted download](#browser-assisted-download)
- [Website support and dedicated handlers](#website-support-and-dedicated-handlers)
- [Queue, retries and partial media](#queue-retries-and-partial-media)
- [Monitoring](#monitoring)
- [Custom monitoring sources](#custom-monitoring-sources)
- [Comic update monitoring](#comic-update-monitoring)
- [Live stream recording](#live-stream-recording)
- [Stalls and recovery](#stalls-and-recovery)
- [Audio timing and gaps](#audio-timing-and-gaps)
- [Collections](#collections)
- [Comic pages to PDF](#comic-pages-to-pdf)
- [Troubleshooting](#troubleshooting)
- [Reporting problems](#reporting-problems)

## Choosing the right screen and button

The three main pages are **Collections**, **Main**, and **Monitoring**. Use their navigation buttons or swipe between pages. Main handles video/audio downloads and browser-assisted recording; Collections handles supported images/comics and PDF conversion; Monitoring checks saved sources for new content. A fresh app opening starts on Main. Navigation stays visible above the scrolling content in portrait and becomes a side rail in landscape. Settings, Queue & history, dialogs, setup pages, and Browser Assist have their own controls rather than the main-page navigation.

| Button | Use it for |
| --- | --- |
| Download | A normal video, audio, or playlist webpage link |
| Browser-assisted download | A video that plays on its website but fails through the normal downloader |
| Browser-assisted stream recording | Capturing a supported playing live stream until you stop |
| Download collection | Supported comic, album, gallery, post, or result-page links |
| Convert / Make PDF | Reviewing images and creating an ordered PDF |
| Add monitoring link | Scheduling supported upload/live checks |
| Add comic update rule | Checking one supported comic/album for new pages |
| Queue & history | Viewing media tasks, retrying, stopping, saving partial material, and opening diagnostics |
| Appearance & display | Choosing the app theme and orientation |

**Example:** To save a complete social media video, first use the full post link with **Download**. If access requires your account, import cookies. If you want to capture a live broadcast over time, use the stream recorder instead.

## Appearance and display

Open **Appearance & display** on Main.

| Setting | Choices |
| --- | --- |
| Theme | Dark, Light, Follow device |
| Screen orientation | Follow Android, Portrait, Landscape |

**Follow Android** respects the device's rotation setting and rotation suggestion. Landscape uses a side rail on the main pages and rearranges browser controls beside the website. Scroll within a control panel if it does not fit. Theme/orientation changes are presentation settings; current forms and browser sessions are intended to stay open. Report lost input or interrupted tasks as a bug.

Website colors inside Browser Assist are controlled by the website. The app's Light theme does not guarantee a light website.

## App updates and recovery

Open **Settings / App updates & recovery** on Main to check published stable app APKs, read release notes, and open Android's installer. This is separate from **Update yt-dlp**, which updates the engine. Android asks you to confirm installation. Finish active media tasks first.

Optional APK backups keep the installed APK and one previous snapshot in your chosen folder. Android may reject downgrades. Uninstalling removes app-private data, including cookies, rules and recovery files; save the older APK and export settings first if you intend to uninstall. Recovery settings cover display settings, monitoring rules and custom-source templates, not cookies, queue history or media. Imported rules start disabled, and custom sources need local testing.

## Regular video and audio downloads

1. Copy the video's full webpage URL.
2. Paste it into the main screen's link field.
3. Choose an output format.
4. Enable playlist or subtitle options only when you need them.
5. Tap **Download** and watch the progress/status.
6. Open **Downloads/yt-dlp Mobile** after saving completes.

### Choosing a format

| Format | What to expect |
| --- | --- |
| MP4 compatibility | A useful starting point for common players; availability still depends on the source |
| Best available quality | Prioritizes available quality and may produce WebM or MKV |
| MP3 audio | Extracts/converts an audio track |
| M4A audio | Extracts an audio track in an M4A output |
| Original/site-selected format | Leaves the explicit format preference unset so the handler/extractor can choose; does not guarantee the original upload's codec or quality |
| Subtitles file only | Saves available captions without downloading the video |

MP4 compatibility is not a universal video transcoder. If the site does not offer suitable formats, the download can still fail or fall back according to the downloader's selection.

### Playlists and subtitles

Enable **Download full playlist** to request all items in a playlist. Leave it off when you want only the selected video. Private, unavailable, or restricted items may fail.

Enable **Download subtitles** and choose language patterns such as:

```text
en.*
```

For English variants plus Spanish:

```text
en.*,es
```

Choose **Subtitles file only** when you want captions without downloading the video. Use the original webpage link because a direct media URL may not expose captions.

Enable **Include automatic subtitles** if you also want machine-generated captions where the source provides them. These options do not guarantee that every video has subtitles or that every player will display them automatically.

### Parallel fragments and speed

**Maximum download speed (parallel fragments)** requests up to eight fragments concurrently for eligible segmented downloads. An ordinary direct MP4 is not automatically divided into eight parallel downloads by this option.

The app can retry once without parallel fragments after certain server/fragment failures. Some dedicated-handler or rate-recovery paths deliberately use one fragment connection even when the general option is enabled. Turn the option off if a website rejects concurrent requests. High or fluctuating speed readings alone do not confirm parallel downloading. ETA is an estimate and can change.

### Extra yt-dlp arguments

Enter options only, without writing `yt-dlp` at the beginning. For example:

```text
--write-thumbnail --embed-metadata
```

The app controls output paths, cookies, configuration, and executable handling. Some conflicting or unsafe options are blocked. Keep the field empty while diagnosing a download problem.

### Extra options and presets

Expand **Extra options & presets** for optional normal-Download controls. The per-download extras default off and do not automatically configure Browser Assist, recording, Collections or Monitoring.

| Extra option | How to use it |
| --- | --- |
| Prefer maximum resolution | Enable and select a resolution preference/ceiling; it does not upscale a source or guarantee that resolution is offered |
| Download a section | Enter start/end times; cuts can begin near a keyframe rather than at an exact frame |
| Only selected playlist items | Enter a selection such as `1-5,8,12` with the appropriate playlist settings |
| Limit download speed | Enable and enter a value; `K` means KiB/s and `M` means MiB/s, not network megabits/s |
| Thumbnail, metadata and chapters | Embed/save the selected information when the source and output container support it |
| Custom filename prefix | Enable and enter a prefix; the video ID is added to reduce filename collisions |

Use **Save current settings as preset** or **Presets: apply / edit / delete / create** to reuse format, playlist, fragment-speed, subtitle, argument and extra-option choices. Applying a preset fills the form; it does **not** start a download. Recheck the populated fields before tapping Download. Presets do not save webpage URLs or account credentials; keep passwords, tokens and cookie headers out of custom commands.

The **Simultaneous downloads / recordings** control opens the shared media limit even when reached through this panel. That queue setting affects eligible media tasks globally; see [Queue, retries and partial media](#queue-retries-and-partial-media).

### Updating the engine

Use **Update yt-dlp** when a website changes or extraction starts failing. The app also checks for engine updates on a fresh launch when idle. Updating the engine is separate from installing a newer app APK and does not bypass account restrictions or DRM.

### Share links and fallback

Supported short/share links are resolved within their website family when possible. If a share link fails, open it in a browser and copy the full video/post URL. Use creator/channel links for monitoring rather than a share link to one post.

Failure to verify a dedicated handler or redirect does not by itself make the original link unsupported: the app can continue through normal yt-dlp using the original submitted URL. This fallback still depends on yt-dlp, access, and the website. After certain access/detection failures while Main is visible, the app can open Browser Assist on that page; you must still complete any prompt and play/detect the video. Use the Browser-assisted download button if it does not open automatically.

## Cookies and signed-in downloads

A cookie file can supply your own signed-in session to yt-dlp. Export **Netscape-format cookies.txt** rather than a JSON cookie list or copied request header.

### Firefox on Android: mobile-first method

1. Open Firefox for Android and sign into the target website yourself.
2. Open Firefox's add-on/extension area and install a compatible cookie-export add-on, such as [cookies.txt](https://addons.mozilla.org/en-US/android/addon/cookies-txt/).
3. Open the target website in the signed-in session.
4. Use the add-on to export cookies for the website in Netscape format. Add-on menus can vary by version.
5. Return to yt-dlp Mobile and tap **Import cookies.txt**.
6. Select the exported file.
7. Paste the full video/post webpage link and tap **Download**.

If the add-on is unavailable in your Firefox version or cannot export, use a desktop browser instead. Confirm the add-on is intended for cookie export before granting access to your session.

### Chrome: desktop export method

Use a desktop browser for the Chrome extension workflow below; do not assume a desktop extension can be installed in your Android browser.

1. Sign into the website in Chrome on a computer.
2. Use a trusted cookie-export extension to save Netscape-format cookies for that website.
3. Transfer the file privately to your phone.
4. Use **Import cookies.txt** in yt-dlp Mobile.
5. Retry the full webpage URL with **Download**.

A desktop Firefox export can be used the same way.

### Two separate sign-in sessions

The external browser that exported `cookies.txt` and the app's embedded WebView profile are separate sessions. Importing a cookie file does not log you into Browser Assist or the monitoring browser. Signing into the embedded browser does not replace your imported file.

The app's browser-assisted and monitoring views use the existing persistent WebView profile, so saved cookies/local storage can be reused where the website permits it. State restricted to a particular tab (`sessionStorage`) is not copied to a new background view. Some direct monitoring requests also use applicable imported/browser cookies; this does not guarantee the same access as an interactive browser.

If a website requires an account, either import a fresh cookie file for the normal download or sign in through the relevant embedded browser and retest. A public page loading successfully does not prove that login was reused. See [Monitoring sign-in and recovery](#monitoring-sign-in-and-recovery).

**Keep cookies private.** They can grant access to your account. Never upload cookie files to GitHub, attach them to public issues, or include them in screenshots. Remove imported cookies when no longer needed and export again if the session expires.

## Browser-assisted download

1. Paste the video's webpage link.
2. Tap **Browser-assisted download**.
3. Complete sign-in or human verification if necessary.
4. Let advertisements finish and play the intended video for several seconds.
5. Tap **Detect and download**.

This detects a media address and hands it to the regular download engine. It is separate from continuous stream recording.

### Multiple sources and Download all

When detection presents several sources, choose one to download it, or use **Download all** to put each listed source into the shared media queue. This is a download feature, not a command to record every source at once. Review the list: entries may be alternate renditions or duplicate-looking sources rather than different videos. If audio-only entries are included, the app asks for confirmation; they still will not become video files.

### Page zoom and app-opening links

Use the separate **Zoom out**, **Zoom in**, and **Reset zoom** buttons in the browser controls. Zoom out if a website dialog or its lower buttons are clipped. These controls change page presentation without reloading or replacing the download/recording controls.

If any website attempts to open its native app, the browser tries to retain web navigation or recover an appropriate web destination when it can. Not every app link has a usable web equivalent. If the page cannot proceed, use the full HTTPS video/post URL; use normal Download with your cookies when appropriate. The app does not need to launch the website's native app to download a web video.

### When detection finds the wrong thing

A webpage may load separate audio, preview clips, ads, thumbnails, and small byte-range video fragments. A detected URL may therefore not contain the full video.

**Example:** A download saves a few kilobytes or plays only audio. Retry the original webpage link through **Download**, using imported cookies if access requires a login. Repeatedly downloading the same fragment will not turn it into a complete video.

Captured URLs can expire or be tied to the current network/IP. Reopen the page and detect again after a network or VPN change.

### White page after Google sign-in

Some sites show a blank white page after **Continue with Google** inside Browser Assist. Return to the site's login page and use its email/username and password method, if available. This issue can affect both assisted downloading and assisted recording.

The white page does not establish that your account login failed; it indicates a problem with that embedded sign-in flow. Do not keep submitting credentials to an invisible page.

## Website support and dedicated handlers

Support has several layers: an app-specific route, the installed yt-dlp extractor, and interactive Browser Assist. **Recognized** does not mean that video download, live recording, upload monitoring, and live monitoring are all implemented for that site. The following describes routes in the app, not a live availability test of every service.

### Dedicated download and playback support

This table also includes named extractor and monitoring routes. Many sites and workflows were tested manually on an Android phone, including some that had no automated end-to-end check. The phone testing note refers to the workflow tested for that site. Other workflows, including automatic monitoring or live recording, may have separate results or caveats.

**Fixture** means a saved or synthetic page, link, or response was checked. **Host reachable** means the site responded to a desktop request. Those checks do not exercise the full Android workflow. A public page may still require verification or a session for playback.

| Website | App support | Testing and caveats | How to use it / credentials |
| --- | --- | --- | --- |
| AniWaves | Episode playback and series monitoring | Handler fixtures passed; moved-domain redirect observed. Audio and server availability vary. Testing was done successfully on a local mobile device. | Use an episode or supported series link. Choose SUB, S-Sub or DUB and the intended server; use Browser Assist if needed. |
| h123movies | Movie/episode playback and compatible listings | Handler fixtures passed; moved-domain redirect observed. Inconclusive English metadata can warn without blocking a valid export. Testing was done successfully on a local mobile device. | Use the individual title and select the audio/server. Use Browser Assist if the player needs interaction. |
| WCOStream | Episode playback and supported series | Handler fixtures passed. Testing was done successfully on a local mobile device. | Use the episode. In Browser Assist, wait for the countdown, tap **Close** if offered and play the episode. |
| Bluesky | Exact public video post | Post and route checks only in the automated results. No dedicated profile monitoring. Testing was done successfully on a local mobile device. | Use the public post permalink containing video. No credentials established. |
| Instagram | Public post/Reel video and extractor fallback | Post route checked. Automated access can fail even on a visible post. Testing was done successfully on a local mobile device. | Use the specific post or Reel. If sign-in is required, use an authorized browser session or import cookies for direct extraction. |
| Threads | Exact public post video | Post route checks only in the automated results. No dedicated profile monitoring. Testing was done successfully on a local mobile device. | Use an individual post on threads.com or threads.net. Try Browser Assist if playback works there. |
| Facebook | Public Reel/video and share redirects; extractor-based monitoring | Route checked. Private/group access varies. Testing was done successfully on a local mobile device. | Use the individual Reel or video; sign in with an account that can view it if required. |
| Reddit | Public video post and share redirects | Route checked. Its metadata API can reject access. Testing was done successfully on a local mobile device. | Use the post permalink; try Browser Assist if it plays there. |
| Snapchat | Public Spotlight and supported share redirects | Route checked. Stories/private media are not implied. Testing was done successfully on a local mobile device. | Use the Spotlight link or copy the final web address from the share link. |
| Tumblr | Exact public video post | Route checked. Some posts embed another provider. Testing was done successfully on a local mobile device. | Use the post permalink or the external video's page. |
| TikTok | Individual video and share redirects; extractor-based monitoring | The tested link worked in manual phone testing. Earlier link failure was not established as site-wide. Testing was done successfully on a local mobile device. | Use a current video permalink or supported share link. No mandatory credentials established for the working sample. |
| Pinterest | Public video Pin and short links | Route checked. Image-only Pins have no video. Testing was done successfully on a local mobile device. | Open the video Pin; try Browser Assist if prompted. |
| Mastodon (mastodon.social) | Exact public status video | Route checked. Dedicated route is instance-specific. Testing was done successfully on a local mobile device. | Use the status permalink; try yt-dlp or a tested custom source on other instances. |
| Internet Archive | Item details and recorded-media preparation | Host reachable; route evidence. Restricted items are not guaranteed. Testing was done successfully on a local mobile device. | Use an individual item details page. |
| Dailymotion | Videos and creator-scoped public uploads | Host reachable; route evidence. No dedicated live result. Testing was done successfully on a local mobile device. | Use a video on Main or the creator profile in Monitoring. |
| Newgrounds | Movie preparation and supported user listings | The desktop probe returned HTTP 403. Manual phone testing confirmed that sign-in or a cookie file was needed. Testing was done successfully on a local mobile device. | Sign in to Newgrounds in Browser Assist or import cookies from your signed-in session. Use a movie link for Main or the creator’s Movies page for Monitoring. |
| PeerTube (peer.tube) | Public videos and video-channel monitoring | Host reachable; route evidence. No dedicated live result. Testing was done successfully on a local mobile device. | Use the individual video or video-channel link. Instance access rules vary. |
| PeerTube (lostpod.space) | Public videos and video-channel monitoring | Host reachable; route evidence. No dedicated live result. Testing was done successfully on a local mobile device. | Use the individual video or video-channel link on this instance. Other instances are not implied. |
| RuTube | Recorded videos and supported season monitoring | Host reachable; route evidence. Generic feeds and live recording are not established. Testing was done successfully on a local mobile device. | Use an individual video or the supported season anchored by its seed video. |
| Vimeo | Video download and paired playlist recovery in Browser Assist | Manual phone testing saved video with audio using Browser Assist recording. A separate Browser Assist download attempt returned an unplayable file. A paired fixture decoded with both tracks. Testing was done successfully on a local mobile device. | Play the video in Browser Assist and detect it if Main fails. No imported cookie file was needed in the observed assisted run. |
| Bilibili International (bilibili.tv) | Video and creator-space routes | Desktop reachability and route checks. Testing was done successfully on a local mobile device. | Use the International video or creator-space link. |
| Bilibili (bilibili.com) | Named extractor-based monitoring route | No automated end-to-end check for this host. The International route is separate. Testing was done successfully on a local mobile device. | Test the creator profile before enabling automation. |
| Kick | Video/channel routes and verified public recordings | Host reachable; route evidence. The automated checks did not cover Android live capture. Testing was done successfully on a local mobile device. | Use a recording or channel link; live depends on verified current playback. |
| Niconico | Watch links and ownership-checked user uploads | Route logic checked. Testing was done successfully on a local mobile device. | Use a watch link or user video listing. Sign in if the requested page requires it. |
| Odysee | Public videos, creator uploads and public live preparation | Host reachable; route evidence. The automated checks did not cover recording through to a saved file. Testing was done successfully on a local mobile device. | Use the video or creator claim. Protected streams are excluded. |
| SOOP | VOD and live station routes | Route checks; no automated end-to-end live check. Testing was done successfully on a local mobile device. | Use a specific VOD or station root. Sign in if the station requires it. |
| Rumble | yt-dlp for recorded videos; creator uploads and public live checks | In manual phone testing, signed-in cookies fixed the direct download. Browser Assist transferred fragments before stopping; completion was not verified. Focused tests covered upload verification. No end-to-end result is recorded here for automatic downloads or live recording. Testing was done successfully on a local mobile device. | For the tested embed 403, import cookies from a signed-in Rumble session or play it in Browser Assist. Unverified creator listings stay preview-only. |
| VK (vk.com) | Named extractor-based monitoring route | An earlier desktop probe returned HTTP 418. Testing was done successfully on a local mobile device. | Try the exact video/channel with yt-dlp or Browser Assist. The status alone does not establish a cookie requirement. |
| VK Video (vkvideo.ru) | Individual videos and recognized creator video listings | Route checked separately from VK Video Live. Testing was done successfully on a local mobile device. | Use the video or supported creator videos link. Separate from VK Video Live. |
| VK Video Live (live.vkvideo.ru) | Live discovery and finished replay monitoring | Phone detected live tracks on the root; both link forms found the same 40 replays in captures. A host replay decoded with both tracks. The desktop live capture attempt returned HTTP 403. The phone result confirms live detection; it does not establish that automatic recording saved a file. Testing was done successfully on a local mobile device. | Use the root or /records; Live uses the root, Uploads can try /records. Use a replay permalink on Main. No login requirement confirmed for the host replay. |
| YouTube | Video downloads and upload/live monitoring | Manual phone testing saved a Browser Assist live recording with audio. A monitored startup attempt for the same stream returned HTTP 403. Desktop metadata and live recovery fixture checks passed. Testing was done successfully on a local mobile device. | Use a watch page or channel. If the site requires a session, import signed-in cookies for Main or sign in through Browser Assist. |
| Twitch | Videos, archives/clips and channel live monitoring | The desktop homepage request succeeded. No automated end-to-end app recording check. Testing was done successfully on a local mobile device. | Use the clip/VOD or channel root. Subscriber-only streams require authorized access. |
| X | Extractor downloads and named profile monitoring | Manual phone testing found that direct download needed cookies from a signed-in session. No automatic monitoring download result is recorded here. Testing was done successfully on a local mobile device. | Use the post link or creator profile as appropriate. Try cookies from a signed-in X session for direct download or sign in through Browser Assist. |
| BitChute | Named extractor-based monitoring route | No automated end-to-end check recorded. Testing was done successfully on a local mobile device. | Use the exact video or test the creator link before enabling automation. |
| CHZZK | Named extractor-based monitoring route | No automated end-to-end check recorded. Testing was done successfully on a local mobile device. | Use the channel and test playback before enabling recording. |
| Huya | Named extractor-based monitoring route | No automated end-to-end check recorded. Testing was done successfully on a local mobile device. | Use the live room; stream access varies. |
| Douyu | Named extractor-based monitoring route | No automated end-to-end check recorded. Testing was done successfully on a local mobile device. | Use the live room; complete any sign-in or verification yourself. |
| Chaturbate | Live-status probe and eligible saved-session recovery | Prior live playback evidence does not verify automatic recording for every room. Testing was done successfully on a local mobile device. | Use the room link and complete any agreement or verification in the in-app browser. |
| Stripchat | Live-status recognition and Browser Assist handoff | Recognition alone does not establish automatic capture; fresh browser observations are needed. Testing was done successfully on a local mobile device. | Open the room in Browser Assist and play the intended stream after any prompts. |
| XHamsterLive | Live-status recognition and Browser Assist handoff | Recognition alone does not establish automatic capture. Testing was done successfully on a local mobile device. | Open the exact room in Browser Assist and play it after any prompts. |
| Picarto | Live-status probe and compatible extractor playback | Route checked; no automated end-to-end recording check. Testing was done successfully on a local mobile device. | Use the creator's live page. Try Browser Assist if preparation fails. |

For AniWaves, h123movies and WCOStream, an explicit selection does not authorize substituting a different server or audio track. A moved domain must provide matching page and player evidence.

### Dedicated monitoring support

Main download, upload detection, live detection and live recording are separate capabilities. Verify each rule with **Check now** before enabling automation. Preview-only results do not establish a baseline or queue downloads. Failed checks keep the existing baseline, and an unknown live state is not treated as offline. Existing custom rules are not automatically replaced.

For VK Video Live, either link form is accepted. Live checks the root, Uploads tries the saved link with a Records-tab fallback, and **Both** runs the checks separately.

## Queue, retries and partial media

Open **Queue & history** from Main or the shared queue control on Monitoring.

- **Simultaneous downloads / recordings** sets the shared media limit. The default is one. Preparing, recording/downloading, and final saving can occupy a slot.
- Live tasks have priority, followed by retries, then other downloads. **Move up** and **Move down** reorder eligible queued tasks. **Record now…** can prioritize a live task; read the confirmation because an active task may be stopped/saved first.
- **Pause after current** pauses queue dispatch after active work. It does not pause all monitoring rules. Resume the queue when ready.
- Queued settings are snapshots; changing Main's current fields is not a guarantee that an existing queued request changes. Use the task's fresh retry/detection action when appropriate.
- Collections have separate transfer coordination. Clearing completed history is not the same as deleting saved media or recovery data.

### A held task with nothing else running

A monitoring task can be **held** while waiting to retry its own preparation. It is not necessarily waiting for another download. Deferred preparations show the reason and a retry countdown; a due retry can still wait for Android or an available slot. Check the task details and full monitoring log rather than inferring an occupied queue from the word “held.”

### Retained downloads and recovery

| Action, when offered | Purpose |
| --- | --- |
| Retry with fresh detection | Resolve the media again and reuse compatible retained fragments where possible |
| Save partial video | Export playable retained material; it may be incomplete or have gaps/missing audio |
| Recover retained video locally / Recover video from retained fragments | Attempt local reconstruction from retained data |
| Export fragment diagnostics | Produce inspection information for a retained download |
| Export sample fragments | Export actual sample media for investigation; inspect before sharing |
| Remove recovery files | Delete retained recovery material after you no longer need it |

Retained data is not proof that a full video was downloaded. Keep it until the saved result is checked. A fresh signed URL can differ from the old one; compatible pieces may be reused, but the app must not combine unrelated media merely because filenames resemble each other.

After a process interruption, review tasks marked **interrupted**. Recording cannot recover time that was never captured. Install updates over the existing app if you want to preserve queued settings/history and recovery files.

## Monitoring

Monitoring checks channels/profiles/series for **future uploads**, **live streams**, or both. It is separate from downloading one existing post.

1. Open Monitoring and tap **Add monitoring link**.
2. Enter a name and the channel/profile or series/season URL. Use a specific video URL on Main for a one-off download instead.
3. Choose the uploads/live mode, action, interval, allowed days, time window, and time zone shown by the rule editor.
4. Choose a compatible built-in route or a tested custom source. Review [Dedicated monitoring support](#dedicated-monitoring-support).
5. Save, use **Check now**, and inspect the source status and discovered items.
6. Test a short automatic task through final saving before relying on a long unattended session.

### Baseline and schedules

The first successful upload check establishes a baseline of the items the source exposes; existing uploads are not automatically downloaded just because they were present at setup. New unseen items found later can notify or enter the queue according to the rule. Many channel checks inspect a bounded recent list, not a full lifetime archive. A long interval can miss short broadcasts or items that leave that recent list.

Each rule has its own schedule. The editor supports allowed days, time windows and a time zone, including overnight windows. Checks are approximate: Android background limits, battery restrictions, network availability, website limits and cooldowns can delay them. An enabled schedule is not an exact recording-start guarantee.

Allow notifications and review **Background / battery settings**. Use **Check now** with the app visible when troubleshooting. A validated network does not establish that the website accepted the request.

A failed/blocked check keeps the prior baseline. Unknown is not offline. Pausing a rule stops future checks but does not stop a recording already running; stopping a recording does not by itself disable its rule. Pause the live rule as well if you want that ongoing broadcast to remain stopped.

### Live discovery versus preparation

A channel can be correctly detected as live while fresh recording preparation still fails. Preparation needs the intended broadcast's usable media, access and audio evidence. Android accepting foreground preparation does not mean recording has begun.

YouTube preparation prefers a complete audio/video format and can recover an HLS address advertised by the exact live video's player data even when the browser has not requested it. It checks for advancing tracks. If an eligible startup failure occurs before capture, it can try a fresh HLS route and then a yt-dlp watch-page fallback for the same still-live video.

If preparation fails, inspect its reason and retry status. If Browser Assist works, use **Retry live stream in Browser Assist** where offered. For an already running but stalled recording, use its recovery control first to avoid starting a second task.

### Monitoring sign-in and recovery

Use **Browser mode / Saved session settings**, or the equivalent custom-source controls. Open the correct page, sign in yourself, and complete any agreement, age confirmation or verification. Try desktop mode if the mobile page omits the relevant content, then retest the source after changing modes.

Where available, use **Save login & test session reuse**. Unique signed-in account text can help verify reuse; a public page loading alone is insufficient. Saved cookies/local storage can be shared with separate monitoring views, but per-tab storage is not copied and servers can expire sessions. Imported cookie files do not log you into those views. Monitored downloads use a fresh copy of an imported cookie file when available, otherwise they can use the saved in-app browser session. Failed access leaves the discovered item available for retry.

The app detects known visible prompts and reports interaction required instead of accepting them for you or marking the channel offline. After handling a prompt, retest reuse and use **Check now**. Automatic browser recovery is bounded; it is not a way around access controls or every website restriction.

### Monitoring diagnostics

Use **Copy detailed monitoring log** or **Save full detailed monitoring log**. The recent-events display is only part of the retained history. Individual queue task details are useful but can omit the underlying extraction/browser evidence.

Logs can distinguish detection, exact source identity, saved-session reuse, player startup, observed/advertised stream evidence, audio verification, HTTP responses, retry scheduling, foreground service handoff, recording, finalization and saving. Attempt/rule identifiers help connect the stages. A status saying “live” is not a substitute for the recording-start and saved-file result.

## Custom monitoring sources

Use **Set up custom monitoring source** when a built-in route does not recognize the intended page. A custom recipe describes discovery rules; it does not add universal support to every website.

1. Enter the channel/listing address and open it in the setup browser.
2. Sign in, handle prompts, and load more entries as needed.
3. Use **Find example posts / videos** and select two or more distinct examples from the same intended list. Avoid recommendations and unrelated creators.
4. Use **Test learned rules, reload & review**. Confirm that the intended entries are found again after reload.
5. For live mode, teach the visible status/live destination and use **Test live destination / playback**. Upload testing does not validate live recording, or vice versa.
6. Save and choose the source on a monitoring rule. Supply that rule's actual channel URL; do not assume the teaching example is the monitored channel automatically.

Use Advanced settings only when needed, and retest after changing the source, channel, mode or browser setting. Import a custom source only from a source you trust and validate it locally. A shared recipe does not supply a working login session or a permanent signed stream URL. Keep existing working custom rules until a replacement has been tested.

## Comic update monitoring

On Monitoring, use **Add comic update rule** for one specific supported comic, chapter or album, rather than a search/tag/browse listing.

Choose the destination folder, credentials where required, interval/days/window and whether to notify or download new pages. **Download existing pages on first successful check** is a separate option; leave it off for future pages only. Comic check intervals start at 15 minutes in the editor and remain approximate.

The app can append newly discovered pages without overwriting saved files or changing your manual order. Failed checks retain the baseline. Replaced, inserted or reordered pages need review; they are not silently treated as simple appended pages. Use **Download pending pages / retry** when offered and keep the chosen folder permission available.

PDFs are **not rebuilt automatically** after comic updates. Review the new order in Convert and make a new PDF when ready. Removing a monitoring rule keeps saved files.

## Live stream recording

1. Open **Browser-assisted stream recording** with the stream's webpage link.
2. Complete any ad, verification, or sign-in.
3. Play the actual stream.
4. Leave **Audio sync adjustment** at `0.00` for an initial test.
5. Tap **Find stream & record**.
6. Check that media time and output size advance.
7. Tap **Stop & save** when finished.

The browser session stays out of the way during recording and browser audio output is muted. The recorder follows detected streams rather than recording sound from the phone microphone. Sites may still interrupt a hidden player or reject requests after the device locks.

Record a short sample before a long session. Confirm both the saved video and audio are usable. If the app warns that audio is uncertain, a continued recording may still lack a usable track.

### Stop, save, and discard

- **Stop & save** ends capture and keeps usable captured media.
- **Cancel recording and discard** removes the recording rather than keeping it.
- Long recordings can take time to align audio and save. Follow the displayed stage and progress; capture and saving are different stages.
- If audio alignment gets stuck, another **Stop & save** request during that stage can skip alignment and preserve the original recording. Check the final status and timing report.

The saved container can be TS or another supported recording container; recording does not promise an MP4 output. Browser-assisted recordings and their diagnostics are saved under **Downloads/yt-dlp Mobile/Recordings**. Automatic monitoring recordings use **Downloads/yt-dlp Mobile/Monitoring/Recordings**; use the task's final status for the actual destination.

## Stalls and recovery

A warning normally appears after about **30 seconds** without output or media progress, or about **45 seconds** if capture has never produced data.

Automatic renewal continues. For unresolved stalls associated with **HTTP 403**, **Reopen stalled stream** becomes available:

1. Tap **Reopen stalled stream**.
2. Complete any website verification or sign-in.
3. Check whether the broadcast is still running.
4. Play the real stream and tap **Check playing stream**.
5. If it ended or recovery fails, use **Stop & save** from the recovery browser or main screen.

The manual recovery button supplements automatic handling. It does not require page reloads during every healthy recording and cannot force a server to accept a rejected session.

### Five-minute unattended timeout

If a stall remains unresolved for **five minutes without user interaction**, the app stops and saves what it captured. The status shows the remaining time.

- Touching the app or recovery browser restarts this timer.
- Successful media progress clears the stall timer.
- The timer is for a stalled recording, not a five-minute limit on healthy recording.
- If no usable media was captured, there may be no video to save.

**Example:** The stream stalls, automatic renewal cannot restore it, and you leave the app alone. After five minutes without interaction, it saves the usable portion instead of waiting indefinitely.

## Audio timing and gaps

The recorder uses available source timing to align separate video and audio. Receiving the audio playlist before the video playlist does not mean their content belongs at different playback times.

When timing permits, finalization adjusts the start and keeps audio at its matching video time. Missing material may produce silence or a cut. It cannot reconstruct lost frames or sound, and it does not guarantee a visual buffering freeze at every gap.

### Correcting a constant offset

The recording browser's **Audio sync adjustment** applies to a new recording:

| Observation | Example setting |
| --- | --- |
| Speech is heard two seconds before the mouth moves | `+2.00` to delay audio |
| Speech is heard two seconds after the mouth moves | `-2.00` to move audio earlier |
| No known constant offset | `0.00` |

A constant adjustment cannot fix timing that keeps jumping. It does not modify recordings already saved.

### Timing reports and fallback

A recording can have a companion `-timing.json.txt` report describing source starts, audio/video gaps, manual adjustment, alignment result, and any failure reason.

If alignment fails or is skipped, the app keeps the original recording and logs the outcome. Keep the timing report with the recording if you want to investigate or repair it later. Missing source timestamps limit what the report can establish.

An `aligned` result means the processing pass completed. It does **not** prove perceptual lip sync or mean every fetched segment appears in the final saved file. Check playback near the start, middle, end, and any recorded gaps.

## Collections

Paste the full page link on **Collections**, check the recognized website and page type, then tap **Download collection**.

Search terms and filters come from supported input URLs; there is no need to type them again in a separate search form. Site recognition alone does not guarantee support for the page.

### Supported link examples

These examples describe checked route shapes. The available reports do not verify a current end-to-end download for every collection handler. Start with an individual accessible post, album or chapter if a listing fails.

| Website | Supported link / operation | Testing and caveats | How to use it / credentials |
| --- | --- | --- | --- |
| E926 | e926.net/posts/12345 or supported tag search | Route checks passed; later testing flagged E926 separately. A current reproducer or end-to-end pass is not available here. | Try an individual public post and capture the exact API error if it fails. Optional E621-family username and API key fields are available. |
| E621 | Individual posts and tag searches | Route checks; API limits and filters apply. | Use the post/search link; enter your E621 username and API key if needed. |
| E6AI | Individual posts and tag searches | Route checks; no current end-to-end result. | Use the matching host's link and username/API key if its API requires them. |
| Furbooru | Individual images and search/tag results | Route checks; filters can affect results. | Try the image link; enter a Furbooru API key where needed. |
| Rule34 | Individual posts and tag listings | Route/credential handling checked. Account settings may still reject valid-looking fields. | **Required:** numeric user ID and API key. See [Credentials](#credentials). |
| Multporn | Comic/gallery and supported category/character listings | Route checks; dedicated video pages are separate. | Use an individual comic or gallery if listings omit links. |
| Luscious | Individual albums, readers and album listings | Route/listing fixtures; unexposed pagination can limit completeness. | Start with an album or reader if the list fails. |
| Tailspace | Comic, artist post and browse listings | Route/listing fixtures; dynamically loaded pages may be incomplete. | Use the exact comic or artist post. |
| Yiffer | Legacy comic links | Route checks; migrated links may fail. | Use the corresponding Tailspace comic if the page moved. |
| nHentai | Gallery and supported tag/category search | Route checks; current access/verification not confirmed. | Use an individual accessible gallery. A 403 alone does not prove sign-in is required. |
| Doujins | Gallery and exposed listings | Route checks; no current end-to-end result. | Use the gallery if the listing does not expose links. |
| HentaiFox | Gallery and exposed listings | Route checks; no current end-to-end result. | Use the gallery permalink. |
| Hentai2Read | Comic, chapter and reader links | Route and reader fixture checks; website retrieval varies by comic. | Use the specific chapter/reader if pages are absent on the title page. |
| 8Muses | Album/comic/folder and exposed listings | Route checks; locked items are not guaranteed. | Use the individual album/folder. |
| AllPornComic | Comic, chapter and exposed listings | Route checks; no current end-to-end result. | Use the individual chapter if the listing fails. |
| ILikeComix | Comic/chapter and exposed listings | Route checks; no current end-to-end result. | Use the individual comic/chapter and check page order. |
| MyHentaiGallery | Gallery and exposed listings | Route checks; account-only pages are not guaranteed. | Use an individual accessible gallery. |
| Honeytoon | Comic/chapter and exposed listings | Route checks; public titles may link to locked chapters. | Use an accessible chapter; paid/locked content is not guaranteed. |
| ComicPorn.xxx | Gallery/chapter and exposed listings | Route checks; no current end-to-end result. | Use the specific accessible gallery, not the home or account page. |

### Credentials

Use the fields displayed for the selected website. Credentials for one service do not authenticate another service.

Rule34 requires a numeric **user ID** and **API key** from your own account. You can enter them separately or paste the combined text into the API key field:

```text
&api_key=YOUR_API_KEY&user_id=YOUR_NUMERIC_USER_ID
```

Use the entire key, not only its last visible portion. Do not include example placeholders literally. If valid-looking credentials are rejected, check the account's API access settings and current service requirements.

E621-family username/key and Furbooru key fields are used when provided. API limits are controlled by the website; the app does not provide unlimited access. Respect rate-limit errors and avoid repeated restart attempts that flood the service.

### Where collections go and how pages are ordered

Collections are saved under:

```text
Downloads/yt-dlp Mobile/Collections/SITE/COLLECTION/
```

Listing downloads can create comic subfolders where the handler supplies them. Ordered comic files use numeric prefixes when the source provides order. Existing matching files may be skipped. Check saved/skipped/failed counts before assuming the entire collection succeeded.

Do not mix unrelated comics into one conversion folder. The PDF editor can infer order from filenames but cannot identify the intended story sequence by image contents.

## Comic pages to PDF

1. Open **Convert** at the bottom of Collections.
2. Choose **Choose folder** or **Choose images**.
3. For a folder, navigate to the comic subfolder and select **Use this folder**.
4. For individually selected images, choose a destination folder when prompted.
5. Inspect page previews.
6. Correct order and choose companion-video options.
7. Tap **Make PDF** and wait for completion.

Original images and videos are kept. This section creates PDFs from images; it is not a standalone general-purpose video conversion screen.

### Checking page order

Natural filename sorting places `page2.jpg` before `page10.jpg`. Numeric prefixes such as `00001-...` help retain source order.

- Tap an image to inspect and zoom.
- Tap its filename to move it to a numbered position.
- Long press and drag a row to reorder it.
- Use **Reverse page order** if the collection is reversed.
- Read duplicate/missing page-number warnings and review the actual pages.

**Example:** You have a cover followed by eight pages. Move the cover to position 1, confirm the eight story pages follow it, then create the PDF. Files with arbitrary names still need manual review.

### Companion video links versus attachments

| Choice | What the PDF contains | Main limitation |
| --- | --- | --- |
| No companion videos | Image pages only | No video entries |
| Add companion video links | Entries pointing to separate files from the selected destination folder | Viewer must resolve the files and have permission to access them |
| Embed companion video files | Copies of the video files stored as PDF attachments | Viewer must support extracting/opening attachments; larger PDF and longer processing |

**Embed companion video files in PDF** is disabled by default. Enable companion videos first, then embedding when videos are available.

Use **View video files to link** to review which videos will be included. When selecting images individually, companion videos are taken from the chosen destination folder.

Filenames on the companion-video page are intended to be clickable. A viewer may also offer paperclip icons or an attachment panel. Some viewers cannot open either type directly. If attachments are supported, save/extract the attached video and open it in a video player when needed.

**Example:** A comic folder contains nine images and two MP4s. A linked PDF depends on those MP4s remaining accessible separately. An embedded PDF carries copies of both MP4s, increasing its size by approximately their combined size plus PDF overhead. Neither mode promises playback inside every PDF viewer.

## Troubleshooting

| Symptom | What to try |
| --- | --- |
| Normal download fails | Update yt-dlp, clear extra arguments, retry the full webpage URL, and try MP4 compatibility |
| Site requires sign-in | Import a fresh Netscape cookie file from your own signed-in session |
| White page after Google login | Return and use the site's email/username-password login if offered |
| Only audio or a tiny file saved | Use the full webpage URL with Download; import cookies if needed |
| No media detected | Finish ads/checks and play the actual video for several seconds before detecting again |
| Website prompt is cut off | Use Zoom out in the browser controls, complete the prompt yourself, then reset zoom if desired |
| Website tries to open its native app | Use the full HTTPS page URL; automatic web-link recovery is possible only when a usable web destination exists |
| Held task but queue otherwise empty | Read the preparation reason/countdown; it can be waiting to retry itself |
| Live detected but recording never starts | Save the full monitoring log; detection, player/stream/audio verification and service startup are separate stages |
| Monitoring is late | Review allowed windows/time zone, notifications, background/battery settings and network; test Check now with the app visible |
| Monitoring says interaction required | Open its browser, complete the prompt/sign-in, retest saved-session reuse, then Check now |
| Candidates visible but uploads are not queued | A preview can lack creator-ownership verification; test a supported route or custom source instead of assuming a successful baseline |
| English language is unverified after processing | Read the warning and check the saved audio; missing language metadata alone does not prove another language |
| HTTP 403 | Any website or media host can refuse a request. Check that the original page plays. If it requires sign-in, use Browser Assist or import cookies for a supported direct download. Verification, an expired media URL or network restrictions can also cause 403. For a stalled recording, use the offered recovery action. |
| HTTP 429 | Reduce repeated requests and parallelism, then wait before retrying |
| Expired URL / HTTP 410 | Reopen Browser Assist and detect a fresh media URL |
| Playback stops after locking phone | Inspect the stall diagnostics and use recovery; hidden/background playback is site-dependent |
| Saving takes a long time | Follow finalization progress; embedding large videos or processing long recordings can take time |
| Alignment is stuck | During audio finalization, another Stop & save can skip alignment and retain the original; check the reported result |
| Collection finds no files | Verify supported page type, credentials, verification state, and whether the site changed |
| Luscious listing fails | Listing support exists; verify exposed album links/access, or try the individual album URL |
| Tailspace browse link fails | Check exposed comic links/pagination, or try an individual `/c/...` or artist-post link |
| PDF link says File not found | Confirm separate file access, or create an embedded PDF and use a viewer supporting attachments |
| PDF attachment is not visible | Check the viewer's attachment panel or extract/open using a viewer with attachment support |

A validated network or held wake lock does not prove a stream session is accepted. A 403 alone does not prove that credentials are required or that locking the phone caused the rejection, and an ended broadcast cannot be recovered by refreshing.

## Reporting problems

Provide:

- App version and Android version.
- The feature used and steps/buttons that led to the issue.
- Whether it works on another network, without a VPN, or with the screen on, if you tested that.
- The error message and **Copy diagnostic log** or **Copy collection log** output.
- For monitoring, the mode (uploads/live/both), built-in versus custom source, interval/window/time zone, individual task details, and **Save full detailed monitoring log** output. Include whether the same broadcast works in Browser Assist.
- For display issues, the theme/orientation, screen, and whether you were rotating, typing, scrolling or opening a dialog.
- For dedicated website handlers, the selected server, audio/language group, and whether Automatic or an explicit choice was used.
- For recordings, relevant HTTP event/timing files and whether the saved file has video, audio, and correct timing.
- For PDF problems, the PDF viewer and whether videos were linked or embedded.

Logs can include request/status history, stream renewal attempts, browser visibility, device lock state, gaps, and alignment outcomes. Inspect them before sharing; redaction is not a reason to publish account secrets or private media links.

Do not publish cookie files, API keys, signing keys, passwords, or private recordings in an issue. Describe an issue without uploading the media when it is not appropriate to share.
