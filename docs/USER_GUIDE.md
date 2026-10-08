# yt-dlp Mobile User Guide

This guide covers version **1.0.0**. Website support depends on the page type, the website's current behavior, and the installed yt-dlp engine.

## Contents

- [Choosing the right screen and button](#choosing-the-right-screen-and-button)
- [Regular video and audio downloads](#regular-video-and-audio-downloads)
- [Cookies and signed-in downloads](#cookies-and-signed-in-downloads)
- [Browser-assisted download](#browser-assisted-download)
- [Live stream recording](#live-stream-recording)
- [Stalls and recovery](#stalls-and-recovery)
- [Audio timing and gaps](#audio-timing-and-gaps)
- [Collections](#collections)
- [Comic pages to PDF](#comic-pages-to-pdf)
- [Troubleshooting](#troubleshooting)
- [Reporting problems](#reporting-problems)

## Choosing the right screen and button

The main screen handles video/audio downloads and browser-assisted recording. Swipe to **Collections** for supported comics, image collections, and PDF conversion. Swipe back to return to the main screen. Page indicators identify the selected screen at the top and bottom; a fresh app opening starts on the main screen.

| Button | Use it for |
| --- | --- |
| Download | A normal video, audio, or playlist webpage link |
| Browser-assisted download | A video that plays on its website but fails through the normal downloader |
| Browser-assisted stream recording | Capturing a supported playing live stream until you stop |
| Download collection | Supported comic, album, gallery, post, or result-page links |
| Convert / Make PDF | Reviewing images and creating an ordered PDF |

**Example:** To save a complete social media video, first use the full post link with **Download**. If access requires your account, import cookies. If you want to capture a live broadcast over time, use the stream recorder instead.

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
| MP3 | Extracts/converts an audio track |
| M4A | Extracts an audio track in an M4A output |

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

Enable **Include automatic subtitles** if you also want machine-generated captions where the source provides them. These options do not guarantee that every video has subtitles or that every player will display them automatically.

### Parallel fragments and speed

**Maximum download speed (parallel fragments)** requests up to eight fragments concurrently for eligible segmented downloads. An ordinary direct MP4 is not automatically divided into eight parallel downloads by this option.

The app can retry once without parallel fragments after certain server/fragment failures. Turn the option off if a website rejects concurrent requests. High or fluctuating speed readings alone do not confirm parallel downloading. ETA is an estimate and can change.

### Extra yt-dlp arguments

Enter options only, without writing `yt-dlp` at the beginning. For example:

```text
--write-thumbnail --embed-metadata
```

The app controls output paths, cookies, configuration, and executable handling. Some conflicting or unsafe options are blocked. Keep the field empty while diagnosing a download problem.

### Updating the engine

Use **Update yt-dlp** when a website changes or extraction starts failing. The app also checks for engine updates on a fresh launch when idle. Updating the engine is separate from installing a newer app APK and does not bypass account restrictions or DRM.

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

Chrome for Android does not support installing normal desktop cookie-export extensions.

1. Sign into the website in Chrome on a computer.
2. Use a trusted cookie-export extension to save Netscape-format cookies for that website.
3. Transfer the file privately to your phone.
4. Use **Import cookies.txt** in yt-dlp Mobile.
5. Retry the full webpage URL with **Download**.

A desktop Firefox export can be used the same way.

### Two separate sign-in sessions

Imported cookies are supplied to **yt-dlp downloads**. **Browser Assist** uses its own embedded WebView session. Importing cookies does not log you into Browser Assist, and signing into Browser Assist does not automatically replace your imported cookie file.

On some websites, including social media sites such as X and Instagram, the following sequence may help:

1. Open the post in Browser Assist.
2. Sign in and reveal the content if prompted.
3. Return to the main screen.
4. Import cookies from your signed-in external browser if needed.
5. Paste the full post URL and tap **Download**.

This is a troubleshooting workflow, not a guarantee that the two browser sessions are shared.

**Keep cookies private.** They can grant access to your account. Never upload cookie files to GitHub, attach them to public issues, or include them in screenshots. Remove imported cookies when no longer needed and export again if the session expires.

## Browser-assisted download

1. Paste the video's webpage link.
2. Tap **Browser-assisted download**.
3. Complete sign-in or human verification if necessary.
4. Let advertisements finish and play the intended video for several seconds.
5. Tap **Detect and download**.

This detects a media address and hands it to the regular download engine. It is separate from continuous stream recording.

### When detection finds the wrong thing

A webpage may load separate audio, preview clips, ads, thumbnails, and small byte-range video fragments. A detected URL may therefore not contain the full video.

**Example:** A download saves a few kilobytes or plays only audio. Retry the original webpage link through **Download**, using imported cookies if access requires a login. Repeatedly downloading the same fragment will not turn it into a complete video.

Captured URLs can expire or be tied to the current network/IP. Reopen the page and detect again after a network or VPN change.

### White page after Google sign-in

Some sites show a blank white page after **Continue with Google** inside Browser Assist. Return to the site's login page and use its email/username and password method, if available. This issue can affect both assisted downloading and assisted recording.

The white page does not establish that your account login failed; it indicates a problem with that embedded sign-in flow. Do not keep submitting credentials to an invisible page.

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

The saved container can be TS or another supported recording container; recording does not promise an MP4 output. Files and recording diagnostics are saved under **Downloads/yt-dlp Mobile/Recordings**.

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

The `12345`, comic titles, artist names, and tag names below are illustrative placeholders, not guaranteed existing posts.

| Website | Example URL shape |
| --- | --- |
| E926 | `https://e926.net/posts/12345` or `https://e926.net/posts?tags=landscape` |
| E621 / E6AI | Individual `/posts/ID` or supported `/posts?tags=...` links on the matching host |
| Furbooru | `https://furbooru.org/images/12345` or supported `/search?q=...` and `/tags/...` links |
| Rule34 | `https://rule34.xxx/index.php?page=post&s=list&tags=YOUR_TAGS` or `page=post&s=view&id=12345` |
| Multporn | Supported `/comics/TITLE`, gallery, `/category/TAG`, `/characters/NAME`, and corresponding hentai listing paths |
| Luscious | `https://www.luscious.net/albums/ALBUM_TITLE_12345/` or that album's reader link |
| Tailspace | `https://tailspace.com/c/COMIC_TITLE` or `/artist/ARTIST/post/12345` |
| Yiffer | Supported legacy comic links; migrated pages may require Tailspace links |

**Not supported in this version:** Luscious `/albums/list/` and Tailspace `/browse?tag=...` listings. Open an individual album/comic/post and paste its link instead. Some stale on-screen hints may say collection downloads will be added later; the download feature exists, but supported page types remain limited.

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
| HTTP 403 | The server rejected access; retry the appropriate authenticated workflow or recording recovery |
| HTTP 429 | Reduce repeated requests and parallelism, then wait before retrying |
| Expired URL / HTTP 410 | Reopen Browser Assist and detect a fresh media URL |
| Playback stops after locking phone | Inspect the stall diagnostics and use recovery; hidden/background playback is site-dependent |
| Saving takes a long time | Follow finalization progress; embedding large videos or processing long recordings can take time |
| Alignment is stuck | During audio finalization, another Stop & save can skip alignment and retain the original; check the reported result |
| Collection finds no files | Verify supported page type, credentials, verification state, and whether the site changed |
| Luscious says Album ID not found for `/albums/list/` | Use an individual album URL; listing support is not implemented |
| Tailspace browse link fails | Use an individual `/c/...` or artist-post link |
| PDF link says File not found | Confirm separate file access, or create an embedded PDF and use a viewer supporting attachments |
| PDF attachment is not visible | Check the viewer's attachment panel or extract/open using a viewer with attachment support |

A validated network or held wake lock does not prove a stream session is accepted. A 403 alone does not prove that the phone locking caused the rejection, and an ended broadcast cannot be recovered by refreshing.

## Reporting problems

Provide:

- App version and Android version.
- The feature used and steps/buttons that led to the issue.
- Whether it works on another network, without a VPN, or with the screen on, if you tested that.
- The error message and **Copy diagnostic log** or **Copy collection log** output.
- For recordings, relevant HTTP event/timing files and whether the saved file has video, audio, and correct timing.
- For PDF problems, the PDF viewer and whether videos were linked or embedded.

Logs can include request/status history, stream renewal attempts, browser visibility, device lock state, gaps, and alignment outcomes. Inspect them before sharing; redaction is not a reason to publish account secrets or private media links.

Do not publish cookie files, API keys, signing keys, passwords, or private recordings in an issue. Describe an issue without uploading the media when it is not appropriate to share.
