package com.linfranca.ytdlpmobile

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.linfranca.ytdlpmobile.databinding.ActivityInstructionsBinding

class InstructionsActivity : DisplayActivity() {
    private data class Section(val title: String, val body: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityInstructionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.backButton.setOnClickListener { finish() }
        sections().forEachIndexed { index, section ->
            val button = MaterialButton(this).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                VisualPolish.guideHeading(this)
            }
            val body = TextView(this).apply {
                text = section.body
                textSize = 16f
                setTextIsSelectable(true)
                VisualPolish.guideBody(this)
                setPadding(dp(14), dp(12), dp(14), dp(16))
                visibility = if (index == 0) View.VISIBLE else View.GONE
            }
            fun label() {
                button.text = (if (body.visibility == View.VISIBLE) "− " else "+ ") + section.title
                button.contentDescription = (if (body.visibility == View.VISIBLE) "Close " else "Open ") + section.title
            }
            label()
            button.setOnClickListener {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                label()
            }
            binding.sectionsContainer.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            binding.sectionsContainer.addView(body)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun sections() = listOf(
        Section("Appearance, themes and orientation", """
            Open Appearance & display on the Main page. Choose Dark, Light, or Follow device. Website colors inside Browser Assist are controlled by the website.

            Choose Follow Android to use the device rotation setting and rotation suggestion, or lock the app to Portrait or Landscape. Landscape uses a side navigation rail on the three main pages and places browser controls beside the website. All existing controls remain available; scroll within a control panel if it does not fit.

            In wide landscape windows, app menus, dialogs and secondary settings pages open on the right with the existing page on the left. Closing them restores the full page. Browser Assist and recording browsers keep their dedicated layouts. Custom monitoring setup stays unchanged until its browser opens; then the browser is on the left and its settings are on the right.

            The main page navigation stays visible while content scrolls. Secondary screens and browser screens keep their own controls. Theme and orientation changes update the current views in place.
        """.trimIndent()),
        Section("App updates and previous APK recovery", """
            Open Settings / App updates & recovery on Main. Check GitHub for a stable app release, read its notes, download its APK, then tap Install downloaded update. Android asks for installation permission and confirmation. Finish active media tasks first. The startup check can be turned off. Update yt-dlp is a separate engine update.

            Keep previous app APK locally is optional. Choose a folder outside Android/data and accept the storage warning. The app verifies and keeps the installed universal APK plus one older snapshot. APK files remain in that folder after uninstalling; they contain the app, not its settings or cookies. If the app cannot open, use Android Files to open a saved APK directly.

            Try Install previous APK first. Android can block downgrades. The separate Uninstall / reinstall fallback shows a data-loss warning before opening Android's uninstall confirmation. Uninstalling removes app-private settings, cookies, rules, queue records and private media/fragments. Afterwards you must manually open the older saved APK from your backup folder. Public downloaded files remain.

            Export recovery settings before uninstalling when possible. It contains display choices, monitoring rules and source templates, including personal channel URLs. It excludes cookies, sign-ins, queue/history, media, comic update rules, download presets and folder permissions. Import creates disabled rule copies; review, retest sources and sign in before enabling them. Older app versions may not support importing this format and may need manual setup.
        """.trimIndent()),
        Section("Start here", """
            Use Download on the main screen for a video, audio track or playlist. Use Browser-assisted download if the site plays the video but Download cannot find or access it. Use Browser-assisted stream recording to save a live broadcast.

            Use the tabs or swipe between the main screen, Collections and Monitoring. Open Queue & history to manage media tasks. Tap a heading in this guide to expand it.
        """.trimIndent()),
        Section("Download a video, audio or playlist", """
            Paste the webpage link and choose a format. MP4 compatibility is a good first choice for common players. Best available quality may save WebM or MKV. MP3 and M4A save audio only. Downloads go to Downloads/yt-dlp Mobile.

            Enable Download full playlist only if you want all items. For one video, paste its own page and leave that option off. If a share link fails, try the full video or post URL.

            Maximum download speed uses parallel fragment requests where supported. Turn it off if a site rejects those requests. Extra yt-dlp arguments are optional; clear them first if the app reports invalid arguments.
        """.trimIndent()),
        Section("Subtitles", """
            Enable subtitles with a video, or choose Subtitles file only to save just the captions. Use the original video page and choose subtitle languages (for example en.* or en.*,es). Enable automatic captions if you want them. A direct media URL may not include captions.
        """.trimIndent()),
        Section("Dedicated website handlers", """
            AniWaves can offer SUB, S-Sub or DUB and a server for the chosen group. h123movies can offer Website default or English audio and a server. Automatic chooses among available options; a specific server can fail. Use the individual episode or movie page for AniWaves, h123movies and WCOStream. If the site needs verification or the intended server does not work, use Browser Assist and select the same playback option there.
        """.trimIndent()),
        Section("Download with Browser Assist", """
            Open Browser-assisted download with the video webpage link. Sign in or complete any verification, let ads finish, and play the intended video for several seconds. Tap Detect and download. If prompted to choose among sources, select the actual video. An audio-only result will not produce a video.

            Tap Zoom out at the top if part of the website or a dialog is cut off. Tap Zoom in to make the page larger, or Reset zoom to return to its original size. These controls resize the page without reloading it.

            Main Download can open Browser Assist on the same page after certain access or detection errors while the main screen is focused. You still need to play and detect the video. If it does not open automatically, use the Browser-assisted download button.

            If a captured link expires, reopen the page and detect again. If only a tiny clip or audio is found, try the original post link with Main Download and cookies if required. DRM-protected streams are unsupported.
        """.trimIndent()),
        Section("Sign in and use cookies", """
            Main Download: import a Netscape-format cookies.txt from your own signed-in browser session using Import cookies.txt. If you need to export one, use a trusted cookie exporter in a compatible browser and transfer the file privately. Keep it private like a password; do not include it in logs or bug reports. Remove it from the app when no longer needed.

            Browser Assist: sign in inside its browser. Importing cookies.txt does not sign you into that browser. If embedded Google sign-in fails, use the site's own sign-in option if it offers one. If a website tries to open its own app, try the full HTTPS link to the video or post. If sign-in is required, you can also try Main Download with cookies from that site.
        """.trimIndent()),
        Section("Record a live stream", """
            Tap Browser-assisted stream recording, play the intended broadcast and tap Find stream & record. For a long recording, save a short sample first and check picture and sound.

            Tap Stop & save to keep the recording, or Cancel recording and discard to delete the capture. Saving can take time. Look in Downloads/yt-dlp Mobile/Recordings.

            If a recording stalls and Reopen stalled stream is offered, open it, sign in or verify if needed, play the same broadcast and tap Check playing stream. If recovery fails, use Stop & save and start a fresh recording if needed. An unresolved stall may automatically stop and save what was captured.

            Use Audio sync adjustment only for a steady offset before recording: positive values delay audio; negative values move it earlier. It cannot fix changing delay or edit a saved file.
        """.trimIndent()),
        Section("Queue, retries and partial videos", """
            Open Queue & history to see progress and manage individual tasks. Simultaneous downloads / recordings controls how many media tasks run at once. The default is one; raise it gradually if needed.

            For a stopped or failed download with retained data, Retry with fresh detection looks for the media again and can reuse matching fragments. Save partial video exports playable material when possible; it may have gaps or missing audio. Recover video from retained fragments attempts local reconstruction. Keep the recovery files until you have checked the saved result.

            Use Remove recovery files when you no longer need those files. To keep the queue and retained media across app updates, install the update over the existing app without clearing app storage.
        """.trimIndent()),
        Section("Monitor new uploads or live channels", """
            On Monitoring, add a channel, profile or series link. Choose New video uploads or live monitoring, set the check interval and allowed times, and choose what should happen when content is found. Use Check now to verify the link before relying on the schedule. For uploads, the first successful check establishes what the app has already seen.

            Use a channel link rather than a share link to one video. For episodic sites use the series or specific season page. If you want an ongoing live stream to stay stopped, pause its rule as well as stopping the recording.

            Allow notifications for alerts and recovery prompts. If checks are late or Android blocks background work, review battery and background settings, then try Check now with the app open. A failed check does not mean a channel is offline.
        """.trimIndent()),
        Section("Set up a custom monitoring source", """
            Use Set up custom monitoring source when the built-in route does not recognize your page. Enter the channel or listing address, open it in the setup browser, and sign in or load more posts if needed. Use Find example posts / videos and select two or more distinct examples from the same list.

            Use Test learned rules, reload & review. Confirm the app finds the intended items again before saving the source and selecting it on a monitoring rule.

            For live monitoring, teach the live destination or visible status from the page, then use Test live destination / playback before saving. Use Advanced settings only if the normal setup does not identify the right list or status. Retest after changing a rule or browser mode.
        """.trimIndent()),
        Section("Monitoring sign-in and recovery", """
            Sign in through the monitoring browser when the rule needs access. Use Browser mode / Saved session settings on built-in rules or the equivalent setting in custom setup. If the mobile page is missing the relevant content, try desktop mode and retest. Use Save login & test session reuse where offered. A page that loads successfully does not always prove you are signed in.

            Automatic monitoring reuses saved browser cookies and local storage where available. Imported cookies can help direct requests but do not sign you into the monitoring browser; storage that belongs only to a particular browser tab is not copied. If a supported built-in live check or playback preparation fails, the app may try a bounded browser recovery using the saved session. This does not guarantee website access.

            An agreement, age confirmation, login or verification prompt needs your attention in the monitoring browser. Handle the prompt yourself, then retest the saved session and use Check now. A blocked or inconclusive check keeps the live state unknown and retains the existing baseline. The app does not accept these prompts for you.

            If automatic live detection fails, use Retry live stream in Browser Assist where offered. Play the intended broadcast and tap Find stream & record. For an existing stalled recording, use its recovery control first to avoid starting a second task.
        """.trimIndent()),
        Section("Download collections", """
            Open Collections and paste a supported post, album, comic, gallery or listing URL. Check the detected site and page type, supply credentials if requested, then tap Download collection. Review saved, skipped and failed files in the status or collection log.

            Supported sites include E621, E6AI, E926, Furbooru, Luscious, Multporn, Rule34, Tailspace, Yiffer, nHentai, Doujins, HentaiFox, Hentai2Read, 8Muses, AllPornComic, ILikeComix, MyHentaiGallery, Honeytoon and ComicPorn.xxx. Supported page types vary by site.

            If no files are found, try the specific album or reader page. Check access or verification in a browser. Locked or inaccessible pages may yield no files.
        """.trimIndent()),
        Section("Convert comic pages to PDF", """
            In Collections, open Convert. Select a folder or individual images, preview and reorder the pages, then tap Make PDF. Your original images are kept.

            Companion video links point to separate files and may not open in every viewer. Enable Embed companion video files in PDF if you want attachments inside the PDF. This increases file size; open the attachment list in a compatible PDF viewer.
        """.trimIndent()),
        Section("Troubleshoot a failed task", """
            No media, empty response or access denied: check that the original page plays. Try Main Download with your own session cookies or open Browser Assist and play the video before detecting. If Reddit's metadata API is blocked but the video plays in Browser Assist, download from there.

            A transfer started but saving failed: read the task's error before trying a different extraction method. Check free space, clear an overly long custom filename template, or use the available recovery action in Queue & history.

            Website changed: Update yt-dlp may help. If the link redirects to an unsupported or unrelated page, open the site and copy the full link to the intended video.
        """.trimIndent()),
        Section("Share a useful diagnostic", """
            For a download, use Copy diagnostic log or the task's log in Queue & history. For Monitoring, use Copy detailed monitoring log or Save detailed monitoring log. For Collections, use Copy collection log. Include what you tried and whether playback, audio or saving failed.

            Monitoring logs include detection, session recovery and recording-start stages, with attempt/rule identifiers, timings and available website or extractor errors. Save the full log after reproducing a problem; the recent-events display contains only part of the retained history.

            If a retained download needs investigation, use Export fragment diagnostics in Queue & history. Export sample fragments includes actual media; review it before sharing. Keep cookies, credentials and private links out of reports.
        """.trimIndent())
    )

    private fun applyInsets(view: android.view.View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }
}
