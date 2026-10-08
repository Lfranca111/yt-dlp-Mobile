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

class InstructionsActivity : AppCompatActivity() {
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
            }
            val body = TextView(this).apply {
                text = section.body
                textSize = 16f
                setTextIsSelectable(true)
                setPadding(dp(12), dp(4), dp(12), dp(20))
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
        Section("Which button should I use?", """
            Download — Start here for an ordinary video, audio track, or playlist link.

            Browser-assisted download — Use when the regular download fails but the video plays on its website.

            Browser-assisted stream recording — Save a playing live stream until you choose Stop & save.

            Download collection — Swipe to Collections to save supported comics, albums, posts, or search results.
        """.trimIndent()),
        Section("Regular downloads", """
            Paste a webpage link, choose a format, and tap Download. MP4 compatibility is a good first choice for common players. Best available quality can produce another container, such as WebM or MKV. MP3 and M4A extract audio. Files go to Downloads/yt-dlp Mobile.

            Enable Download full playlist only when you want every item. Subtitle languages accept yt-dlp patterns such as en.* or en.*,es. Maximum download speed uses parallel fragments; turn it off if a site has trouble with parallel requests. Extra yt-dlp arguments are optional; app-managed output and cookie options are blocked.
        """.trimIndent()),
        Section("Browser-assisted download", """
            Open the page, finish any advertisement, sign-in check, or human verification, and play the actual video. Tap Detect and download. This sends the detected media link to the normal download engine; it is separate from live recording.

            Captured links can expire. If the download fails later, reopen the page and detect again. Some streams are protected or do not provide a downloadable media link.
        """.trimIndent()),
        Section("Record a live stream", """
            Tap Browser-assisted stream recording. Finish any ad or verification, play the real stream, and tap Find stream & record. The browser session is kept out of the way while the recorder follows the stream. Some sites may still interrupt playback or refuse requests when the phone locks or the network changes.

            Tap Stop & save to keep the captured recording, or Cancel recording and discard to remove it. Long recordings can take time to finalize; follow the saving progress. Recordings and their detailed HTTP event logs are saved under Downloads/yt-dlp Mobile/Recordings. A short sample helps check audio before a long recording.
        """.trimIndent()),
        Section("Stalls and 403 recovery", """
            A stall warning appears after about 30 seconds without file or media progress, or 45 seconds if no data ever arrives. The recorder keeps trying automatic stream renewal. If a 403 stall cannot be fixed automatically, Reopen stalled stream becomes available: complete any site check, play the actual stream, then tap Check playing stream. Stop & save is available if the show ended or recovery fails.

            If a stall remains unresolved for 5 minutes without user interaction, the recorder automatically stops and saves what it captured. Touching the app or recovery browser restarts this 5-minute timer. Successful media progress clears it. The recording status shows the remaining time. If no usable media was captured, there may be no video to save.
        """.trimIndent()),
        Section("Audio and video timing", """
            The recorder aligns audio and video from stream timing when possible. If the recording has a gap, audio should remain at its matching point in the video; a missing part may be silent or appear as a cut.

            Audio sync adjustment on the browser recording screen is for a steady offset. Positive values delay audio (for example +2.00 when speech is two seconds early); negative values move it earlier. It cannot repair constantly jumping timing or change a recording already saved.
        """.trimIndent()),
        Section("Collections", """
            Swipe to Collections. Paste a complete post, comic, album, gallery, tag, character, category, or search-results link from a recognized site. Check the detected website and page type, then tap Download collection. Search terms are read from the link; results pages may follow their listed posts. The status shows saved, skipped, and failed files.

            Supported site families include E621, E6AI, E926, Furbooru, Luscious, Multporn, Rule34, Tailspace, and Yiffer. Coverage varies by page type. Enter the credentials shown on the Collections screen when a site requires them. If the site changes its page format or displays verification, it may return no media.
        """.trimIndent()),
        Section("Comic pages to PDF", """
            In Collections, open Convert and choose a folder or individual images. Inspect pages in the larger preview, zoom into one if needed, and change their order before tapping Make PDF. Originals are kept.

            Add companion video links to PDF lists videos from the destination folder, but some Android PDF viewers cannot open those separate files. Embed companion video files in PDF is off by default. Turn it on to copy the videos into the PDF as attachments; this can greatly increase file size and processing time. In a viewer that supports attachments, tap a filename or paperclip, or open its attachment list.
        """.trimIndent()),
        Section("Cookies and verification", """
            Import a Netscape-format cookies.txt only when a site requires your signed-in session. Treat it like a password and remove it from the app when no longer needed. Cookies do not guarantee that a website will skip human verification. The app does not support protected DRM streams.

            How to get cookies.txt — On your phone, use Firefox for Android: sign in to the website, install a Firefox Android cookie-export add-on such as cookies.txt, open the website, and use the add-on to export a Netscape-format cookies.txt file. Then return to this app, tap Import cookies.txt, and select the saved file. Chrome for Android cannot install cookie-export extensions. For Chrome, sign in to the website on a computer, export its cookies in Netscape format, transfer the file privately to your phone, and import it here. Keep the cookie file private; it can contain your signed-in session.

            Imported cookies are supplied to yt-dlp downloads. Browser Assist uses a separate WebView session: importing cookies.txt does not sign you into its browser. A site you sign into inside Browser Assist may remember that login the next time you open it.
        """.trimIndent()),
        Section("Common problems", """
            No media found — Finish ads or verification, play the intended video for several seconds, and detect again.

            White page after Google sign-in in Browser Assist — Some sites offer Continue with Google, but Google sign-in is not supported inside the app's embedded browser. Return to the site and use its email-and-password sign-in instead. This can affect both Browser-assisted download and Browser-assisted stream recording.

            Browser Assist saves only audio or a tiny clip — Some websites, including social media websites like X and Instagram, can expose a separate audio track or a small video fragment instead of the whole video. If the site requires a sign-in, import a Netscape-format cookies.txt from your signed-in browser. Paste the full post or video webpage link on the main screen and tap Download instead of using the detected media URL.

            Post still needs a sign-in or content warning — On some websites you may also need to open the post in Browser Assist, sign in there, and reveal the content if prompted. Then return to the main screen and retry Download with the full webpage link and imported cookies. Browser Assist and yt-dlp have separate sign-in sessions; importing cookies does not sign you into Browser Assist.

            403 / Forbidden — A site rejected the request. Update yt-dlp or try Browser Assist for a download. For a stalled recording, use Reopen stalled stream when offered.

            Expired link or 410 — Open the page again and detect a fresh stream.

            Missing audio — Check a short saved sample. Some sites deliver audio separately or do not expose a usable audio track.

            Collection found no files — Check the detected page type, credentials, and whether the site is showing a verification page.

            PDF video says File not found — Separate video links depend on viewer file access. Make a new PDF with video embedding if you want the videos inside it.
        """.trimIndent()),
        Section("Logs and updates", """
            Tap Copy diagnostic log on the main screen or Copy collection log on Collections when reporting a problem. Recording runs may also save a detailed HTTP event log in the Recordings folder. Include what you pressed, the status message, and whether the file contains video and audio.

            Update yt-dlp refreshes the download engine. It may help when a website changes, but cannot guarantee support for every stream, account restriction, or protected video.
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
