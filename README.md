# yt-dlp-Mobile

An Android app for downloading video and audio, recording live streams, saving image and comic collections, and turning ordered comic pages into PDFs.

Media processing runs on the device. The app uses yt-dlp for regular downloads, an embedded browser for assisted capture, and FFmpeg for media processing.

## Download and install

1. Open [GitHub Releases](../../releases).
2. Download **ytdlpMobile.apk** from the release's **Assets** section.
3. Open the APK on your Android device. If Android asks, allow installation from the browser or file manager you used.
4. Open **yt-dlp Mobile** and allow the permissions needed for the operation you choose.

**Requirements:** Android 10 or newer, an internet connection for downloads, and enough free space for downloaded media and temporary processing files.

The APK is the installable app. GitHub's **Source code (zip)** and **Source code (tar.gz)** downloads are for developers, not APK installers. Updates must use the same signing key as the installed app.

## Features

- Download video or audio from webpage links supported by yt-dlp.
- Choose MP4 compatibility, best available quality, MP3, or M4A.
- Download full playlists and available subtitles, including automatic subtitles when offered.
- Import Netscape-format cookies for downloads that require your signed-in session.
- Detect media through **Browser-assisted download** when a normal download fails.
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
| Luscious | Individual album and picture/reader links |
| Tailspace | Individual comic and artist-post links |
| Yiffer | Supported legacy comic links; site migration may affect availability |

Recognizing a website does not mean every page type is supported. **Luscious `/albums/list/` listings and Tailspace `/browse` listings are not supported in this version.** Use an individual album, comic, or post link instead.

## Important limitations

- Website changes, authentication, human verification, network restrictions, and expired URLs can prevent downloads.
- DRM-protected streams are unsupported.
- An embedded browser can expose audio-only URLs or small fragments rather than the complete video.
- Some sites stop serving streams when the phone locks or the browser moves into the background. Recovery is not guaranteed.
- Audio alignment depends on available source timestamps. A successful timing pass is not a measurement of perceptual lip sync.
- PDF viewers vary in their support for local video links and attachments. Embedding a video does not guarantee inline playback.
- Parallel fragments help eligible segmented downloads; they do not promise faster downloads from every site.

## Report a problem

Include the app version, the operation and buttons you used, the error message, and a relevant diagnostic log. For recordings, mention whether the screen was locked, the browser was hidden, and whether the saved file contains both audio and video.

Remove cookies, API keys, account details, and private URLs before posting publicly. See [Reporting problems](docs/USER_GUIDE.md#reporting-problems).

## Credits and licensing

Built with yt-dlp, youtubedl-android, FFmpeg, AndroidX, Material Components, Kotlin, Java, C, XML, and selected ARM64 assembly routines.

See [Third-party notices](THIRD_PARTY_NOTICES.md) and [License status](LICENSE). The application license selection is pending; this documentation does not designate the application as open source or grant new redistribution rights.

Download only media you are authorized to save.
