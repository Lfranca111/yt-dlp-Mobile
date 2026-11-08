# yt-dlp-Mobile

An Android app for downloading video and audio, recording live streams, saving image and comic collections, and turning ordered comic pages into PDFs.

Media processing runs on the device. The app uses yt-dlp for regular downloads, an embedded browser for assisted capture, and FFmpeg for media processing.

> **Current released version: 1.0.63.** These instructions describe that release. Later work, including the development 1.0.64 YouTube fixes, is listed separately under [Unreleased](CHANGELOG.md#unreleased). A listed route is not a guarantee that every website currently works.

[Get started](#quick-start) · [User Guide](docs/USER_GUIDE.md) · [Website and monitoring support](docs/USER_GUIDE.md#website-support-and-dedicated-handlers) · [Build Guide](docs/BUILDING.md) · [Changelog](CHANGELOG.md)

## Download and install

1. Open [GitHub Releases](../../releases).
2. Download **ytdlpMobile.apk** from the release's **Assets** section.
3. Open the APK on your Android device. If Android asks, allow installation from the browser or file manager you used.
4. Open **yt-dlp Mobile** and allow the permissions needed for the operation you choose.

**Requirements:** Android 10 or newer, an internet connection for downloads, and enough free space for downloaded media and temporary processing files.

The APK is the installable app. GitHub's **Source code (zip)** and **Source code (tar.gz)** downloads are for developers, not APK installers. Updates must use the same application ID and signing key as the installed app. Install over the existing app to retain settings, rules, queue history, and retained recovery files; do not clear app storage. A changed-files source ZIP is a patch for a complete project, not an installable app.

## Features

- Download video or audio from webpage links supported by yt-dlp.
- Choose MP4 compatibility, best available quality, Original/site-selected format, MP3, or M4A.
- Save reusable download presets and use optional resolution preferences, section cuts, selected playlist items, rate limits, thumbnails, metadata, chapters and filename prefixes.
- Download full playlists and available subtitles, including automatic subtitles when offered, or save subtitle files without a video.
- Import Netscape-format cookies for downloads that require your signed-in session.
- Detect media through **Browser-assisted download** when a normal download fails; select one detected source or use **Download all** to queue the listed sources.
- Use a shared media queue with task priorities, adjustable simultaneous downloads/recordings, retry controls, and retained partial-media recovery.
- Monitor supported channels or series for future uploads and live broadcasts, with per-rule schedules, notify-only or automatic actions, saved-session checks, and detailed logs.
- Teach and test a custom monitoring source when a built-in route does not cover a page.
- Monitor a specific supported comic/album for new pages without automatically overwriting or reordering saved pages.
- Use dedicated website playback/public-post routes where verified, with normal yt-dlp fallback when no dedicated route applies.
- Choose Dark, Light, or Follow device, and Follow Android, Portrait, or Landscape. Main-page navigation stays visible; secondary screens and browsers keep their own controls.
- Use **Zoom out**, **Zoom in**, and **Reset zoom** in Browser Assist to reach clipped website controls without reloading the page.
- Record supported live streams with **Stop & save**, diagnostic logs, and audio timeline processing.
- Try automatic recovery when stream URLs or session credentials stop working, with a manual recovery browser for unresolved 403 stalls.
- Automatically stop and save an unresolved stall after five minutes without user interaction.
- Download supported image, comic, album, post, and selected search-result collections.
- Review large page previews, adjust page order, and create a PDF without deleting the original images.
- Add companion video links or optionally embed video files as PDF attachments.
- Use native ARM64 helpers for selected parsing, sorting, and serialization operations, with managed fallbacks on other supported architectures.

## Quick start

### Download a video

1. Paste the video's **webpage link** on the main screen.
2. Choose **MP4 compatibility** as a starting point.
3. Tap **Download**.
4. Find the result in **Downloads/yt-dlp Mobile**.

For example, use a video's YouTube watch-page link, not a thumbnail URL or a browser's temporary media fragment.

### Record a stream

1. Open **Browser-assisted stream recording**.
2. Finish any sign-in, ad, or verification, then play the actual stream.
3. Tap **Find stream & record**.
4. Record a short sample first, then use **Stop & save** and check its audio.

### Watch a channel for new content

1. Open **Monitoring** and tap **Add monitoring link**.
2. Use a channel/profile or series link, choose uploads, live, or both, and set the schedule and action.
3. Use **Check now** and review the result. Upload monitoring establishes a baseline first; it does not automatically download the channel's existing archive.
4. Check **Queue & history** for downloads, recording preparation, retries, and saving.

Detection and recording preparation are separate stages. A live discovery can still need sign-in, fresh stream verification, or Browser Assist before recording starts. See [Monitoring](docs/USER_GUIDE.md#monitoring).

### Save a comic and make a PDF

1. Swipe to **Collections**, paste a supported comic link, and tap **Download collection**.
2. Open **Convert** and choose the saved comic folder.
3. Inspect the pages and correct their order.
4. Tap **Make PDF**.

## Detailed instructions

See the [User Guide](docs/USER_GUIDE.md) for examples, cookies, recording recovery, collection credentials, PDF attachments, and troubleshooting.

Developers can use the [Build Guide](docs/BUILDING.md). Release changes are listed in the [Changelog](CHANGELOG.md).

## Collection coverage

| Website family | Current collection paths |
| --- | --- |
| E621, E6AI, E926 | Individual posts and supported search-result links |
| Furbooru | Individual images and supported search/tag-result links |
| Rule34 | Individual posts and search-result links; API credentials required |
| Multporn | Supported comic/gallery links and selected category/character listings |
| Luscious | Individual albums/readers and supported album listings exposing album links/pagination |
| Tailspace | Individual comics/artist posts and supported browse listings exposing comic links/pagination |
| Yiffer | Supported legacy comic links; migration may affect availability |
| nHentai, Doujins, HentaiFox, Hentai2Read | Supported gallery, comic/chapter, and exposed listing paths; coverage varies by site |
| 8Muses, AllPornComic, ILikeComix, MyHentaiGallery, Honeytoon, ComicPorn.xxx | Supported comic/gallery/chapter/folder and exposed listing paths; locked or unexposed content is not guaranteed |

The earlier Luscious album-list and Tailspace browse limitations have been superseded by listing handlers. They still depend on the links and pagination the website exposes; recognition is not proof that a full result set can be retrieved. See [Collections](docs/USER_GUIDE.md#collections).

Video downloading, live recording, upload monitoring, and live monitoring have separate coverage. See the [support tables](docs/USER_GUIDE.md#website-support-and-dedicated-handlers), especially [Dedicated monitoring support](docs/USER_GUIDE.md#dedicated-monitoring-support). Public-post support on a social website does not imply dedicated monitoring for that website.

## Important limitations

- Website changes, authentication, human verification, network restrictions, and expired URLs can prevent downloads. An HTTP 403 alone does not establish the cause.
- Background monitoring times are approximate and may be delayed by Android, battery restrictions, network conditions, or website limits. A failed check preserves the baseline; it is not an offline result.
- Saved browser sessions can expire. Imported cookies do not log you into the embedded browser, and browser state limited to a particular tab is not copied into background checks.
- DRM-protected streams are unsupported.
- An embedded browser can expose audio-only URLs or small fragments rather than the complete video.
- Some sites stop serving streams when the phone locks or the browser moves into the background. Recovery is not guaranteed.
- Audio alignment depends on available source timestamps. A successful timing pass is not a measurement of perceptual lip sync.
- PDF viewers vary in their support for local video links and attachments. Embedding a video does not guarantee inline playback.
- Parallel fragments help eligible segmented downloads; they do not promise faster downloads from every site.

## Report a problem

Include the app version, the operation and buttons you used, the error message, and a relevant diagnostic log. For monitoring, use **Save full detailed monitoring log** as well as the individual task details; the recent-events panel is only a partial history. For recordings, mention whether the screen was locked, the browser was hidden, and whether the saved file contains both audio and video.

Remove cookies, API keys, account details, and private URLs before posting publicly. See [Reporting problems](docs/USER_GUIDE.md#reporting-problems).

## Credits and licensing

Built with yt-dlp, youtubedl-android, FFmpeg, AndroidX, Material Components, Kotlin, Java, C, XML, and selected ARM64 assembly routines.

See [Third-party notices](THIRD_PARTY_NOTICES.md) and [License status](LICENSE). The application license selection is pending; this documentation does not designate the application as open source or grant new redistribution rights.

Download only media you are authorized to save.
