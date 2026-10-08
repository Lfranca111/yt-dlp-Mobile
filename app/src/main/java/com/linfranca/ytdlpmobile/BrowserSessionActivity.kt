package com.linfranca.ytdlpmobile

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.linfranca.ytdlpmobile.databinding.ActivityBrowserSessionBinding
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserSessionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserSessionBinding
    private val mediaCandidates = ConcurrentHashMap<String, MediaCandidate>()
    private lateinit var pageUrl: String
    private lateinit var mode: String
    private val pollHandler = Handler(Looper.getMainLooper())
    private var captureActive = false
    private var startPending = false
    private var stopRequested = false
    private var polling = false
    private var capturedBytes = 0L
    private var receiverRegistered = false
    private var stateReceiverRegistered = false
    private var refreshReceiverRegistered = false
    private var nativeRecordingActive = false
    private var nativeRecordingCompleted = false
    private var renewalVideoUrl = ""
    private var renewalAudioUrl = ""
    private val lastRenewalOffer = mutableMapOf<String, String>()
    private var waitingForActualStream = false
    private var lastPageReloadAt = 0L
    private val renewalPoll = object : Runnable {
        override fun run() {
            if (!nativeRecordingActive || isFinishing || isDestroyed) return
            recordBrowserEvent("browser_renewal_poll")
            if (waitingForActualStream) {
                checkPlayerAfterReload()
            } else {
                offerRenewal("video", renewalVideoUrl)
                offerRenewal("audio", renewalAudioUrl)
            }
            pollHandler.postDelayed(this, 20_000L)
        }
    }
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecordingService.ACTION_BROWSER_REFRESH_REQUEST || !nativeRecordingActive) return
            recordBrowserEvent("browser_refresh_request_received")
            if (binding.streamRecoveryControls.visibility == View.VISIBLE && hasWindowFocus()) {
                recordBrowserEvent("browser_refresh_deferred_during_manual_recovery")
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastPageReloadAt < 60_000L) {
                recordBrowserEvent("browser_reload_skipped_cooldown")
                return
            }
            lastPageReloadAt = now
            waitingForActualStream = true
            mediaCandidates.clear()
            recordBrowserEvent("browser_page_reload_attempt")
            binding.webView.reload()
        }
    }
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingService.ACTION_BROWSER_STOP_REQUEST -> stopBrowserRecording()
                RecordingService.ACTION_BROWSER_FINALIZING -> if (nativeRecordingActive) {
                    pollHandler.removeCallbacks(renewalPoll)
                    waitingForActualStream = false
                    binding.webView.stopLoading()
                    binding.webView.loadUrl("about:blank")
                    binding.browserStatus.text = "Recording stopped. Saving the captured media…"
                }
            }
        }
    }
    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val update = intent ?: return
            if (!nativeRecordingActive || update.action != DownloadService.ACTION_STATE) return
            if (update.getBooleanExtra(DownloadService.EXTRA_RUNNING, true)) {
                if (!update.getBooleanExtra(RecordingService.EXTRA_403_RECOVERY_AVAILABLE, false)) {
                    binding.streamRecoveryControls.visibility = View.GONE
                }
                return
            }
            nativeRecordingActive = false
            nativeRecordingCompleted = true
            pollHandler.removeCallbacks(renewalPoll)
            // Close the player only once the capture service has stopped.
            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank")
            binding.browserStatus.text = update.getStringExtra(DownloadService.EXTRA_STATUS)
                ?: "Recording finished. Check the main screen for details."
            binding.cancelButton.isEnabled = true
            binding.cancelButton.text = "Close"
            binding.streamRecoveryControls.visibility = View.GONE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        pageUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_DOWNLOAD
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) {
            finish()
            return
        }
        if (mode == MODE_RECORD) {
            ContextCompat.registerReceiver(this, refreshReceiver,
                IntentFilter(RecordingService.ACTION_BROWSER_REFRESH_REQUEST), ContextCompat.RECEIVER_NOT_EXPORTED)
            refreshReceiverRegistered = true
            ContextCompat.registerReceiver(this, stopReceiver,
                IntentFilter().apply {
                    addAction(RecordingService.ACTION_BROWSER_STOP_REQUEST)
                    addAction(RecordingService.ACTION_BROWSER_FINALIZING)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            ContextCompat.registerReceiver(this, recordingStateReceiver,
                IntentFilter(DownloadService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
            stateReceiverRegistered = true
            binding.audioSyncControls.visibility = View.VISIBLE
            val savedMs = getSharedPreferences("recording_audio_sync", MODE_PRIVATE)
                .getInt(audioSyncPreferenceKey(), 0)
            binding.audioDelayInput.setText(String.format(Locale.US, "%.2f", savedMs / 1000.0))
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            databaseEnabled = true
            loadsImagesAutomatically = true
        }
        // Capture the cookies WebView actually sends to each media URL, including
        // partitioned cookies on supported WebView versions.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)) {
            WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest(
                binding.webView.settings, true)
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.url?.toString()?.let { candidate ->
                    if (MediaUrlClassifier.isDirectMediaUrl(candidate)) {
                        val now = System.currentTimeMillis()
                        val headers = request.requestHeaders
                            .filterKeys { it.lowercase() in SAFE_CAPTURED_HEADERS }
                        mediaCandidates.compute(candidate) { _, existing ->
                            MediaCandidate(candidate, now, headers.ifEmpty { existing?.headers.orEmpty() })
                        }
                        if (MediaUrlClassifier.isContinuousManifest(candidate)) {
                            binding.webView.post {
                                if (nativeRecordingActive && !waitingForActualStream) {
                                    if (sameRendition(renewalVideoUrl, candidate)) offerRenewal("video", renewalVideoUrl)
                                    if (sameRendition(renewalAudioUrl, candidate)) offerRenewal("audio", renewalAudioUrl)
                                }
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (nativeRecordingActive) {
                    recordBrowserEvent("browser_page_finished")
                    checkPlayerAfterReload()
                    return
                }
                if (captureActive || nativeRecordingActive || nativeRecordingCompleted) return
                binding.browserStatus.text = if (mode == MODE_RECORD) {
                    "Play and unmute the actual video, then tap Find stream & record."
                } else {
                    "Play the video briefly, then tap Detect and download."
                }
            }
        }

        binding.cancelButton.setOnClickListener {
            when {
                captureActive -> discardBrowserRecording()
                nativeRecordingActive -> {
                    binding.cancelButton.isEnabled = false
                    binding.browserStatus.text = "Stopping and saving the recording…"
                    startService(Intent(this, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_STOP_SAVE))
                }
                else -> finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nativeRecordingActive) {
                    AlertDialog.Builder(this@BrowserSessionActivity)
                        .setTitle("Leave the playing stream?")
                        .setMessage("This site may stop supplying new live segments if its player closes. Keep this browser page open until you stop and save the recording.")
                        .setPositiveButton("Leave anyway") { _, _ -> finish() }
                        .setNegativeButton("Keep page open", null)
                        .show()
                } else finish()
            }
        })
        binding.useMediaButton.setOnClickListener {
            if (mode == MODE_RECORD) {
                if (nativeRecordingActive) {
                    binding.useMediaButton.isEnabled = false
                    binding.cancelButton.isEnabled = false
                    binding.browserStatus.text = "Stopping and saving the recording…"
                    startService(Intent(this, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_STOP_SAVE))
                } else findContinuousRecordingStream()
            } else detectMedia()
        }
        binding.checkPlayingStreamButton.setOnClickListener {
            if (nativeRecordingActive) {
                recordBrowserEvent("manual_403_stream_check_requested")
                waitingForActualStream = true
                binding.browserStatus.text = "Checking the playing video for renewed stream URLs…"
                checkPlayerAfterReload()
            }
        }
        binding.recoveryStopSaveButton.setOnClickListener {
            if (nativeRecordingActive) {
                recordBrowserEvent("manual_403_stop_and_save_requested")
                binding.recoveryStopSaveButton.isEnabled = false
                binding.checkPlayingStreamButton.isEnabled = false
                binding.useMediaButton.isEnabled = false
                binding.cancelButton.isEnabled = false
                binding.browserStatus.text = "Stopping and saving the captured recording…"
                startService(Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP_SAVE))
            }
        }
        binding.useMediaButton.text = if (mode == MODE_RECORD) "Find stream & record" else "Detect and download"
        if (intent.getBooleanExtra(EXTRA_RECOVERY, false)) enterRecovery()
        binding.webView.loadUrl(pageUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RECOVERY, false)) enterRecovery()
    }

    private fun enterRecovery() {
        if (mode != MODE_RECORD || !DownloadState.running ||
            DownloadState.operation != DownloadState.OPERATION_RECORD ||
            !DownloadState.recording403RecoveryAvailable) return
        if (!nativeRecordingActive) {
            renewalVideoUrl = DownloadState.recordingVideoUrl
            renewalAudioUrl = DownloadState.recordingAudioUrl
            if (renewalVideoUrl.isBlank()) return
            nativeRecordingActive = true
            binding.useMediaButton.text = "Stop & save"
            pollHandler.removeCallbacks(renewalPoll)
            pollHandler.postDelayed(renewalPoll, 20_000L)
        }
        waitingForActualStream = true
        binding.streamRecoveryControls.visibility = View.VISIBLE
        binding.recoveryStopSaveButton.isEnabled = true
        binding.checkPlayingStreamButton.isEnabled = true
        binding.browserStatus.text = "The recorder is still trying automatically. Complete any site check, play the actual stream, then tap Check playing stream. If it ended, tap Stop & save."
        recordBrowserEvent("manual_403_browser_opened")
    }

    private fun findContinuousRecordingStream() {
        if (DownloadState.running) {
            binding.browserStatus.text = "Finish the current process before recording."
            return
        }
        binding.browserStatus.text = "Finding the playing video's continuous stream…"
        binding.webView.evaluateJavascript("""
            (function() {
              const video = Array.from(document.querySelectorAll('video'))
                .filter(v => { const r = v.getBoundingClientRect();
                  return r.width > 80 && r.height > 45 && !v.paused && !v.ended; })[0];
              const ad = !!document.querySelector('.ad-showing, .ad-playing, .video-ads, .ima-ad-container, [data-ad-state="playing"]');
              const urls = video ? [video.currentSrc, video.src].concat(
                Array.from(video.querySelectorAll('source')).map(s => s.src)).filter(Boolean) : [];
              return JSON.stringify({playing: !!video, ad: ad, urls: urls,
                resources: performance.getEntriesByType('resource').slice(-500).map(r => r.name)});
            })();
        """.trimIndent()) { raw ->
            val payload = parseBrowserResponse(raw)
            if (payload?.optBoolean("playing") != true) {
                binding.browserStatus.text = "Play the actual video first, then try again."
                return@evaluateJavascript
            }
            if (payload.optBoolean("ad")) {
                mediaCandidates.clear()
                binding.browserStatus.text = "Wait for the in-video ad to finish, then try again."
                return@evaluateJavascript
            }
            val now = System.currentTimeMillis()
            val activeUrls = payload.optJSONArray("urls").toStringList().toSet()
            payload.optJSONArray("resources").toStringList()
                .filter(MediaUrlClassifier::isContinuousManifest)
                .forEach { url -> mediaCandidates.putIfAbsent(url, MediaCandidate(url, now, emptyMap())) }
            val manifests = mediaCandidates.values.filter {
                now - it.capturedAtMs < 30_000L && MediaUrlClassifier.isContinuousManifest(it.url)
            }
            val primary = manifests.filter { MediaUrlClassifier.streamKind(it.url) != MediaUrlClassifier.StreamKind.AUDIO }
                .maxByOrNull { MediaUrlClassifier.score(it.url, it.url in activeUrls, it.capturedAtMs) }
            val currentPage = binding.webView.url ?: pageUrl
            val youtubePage = runCatching { java.net.URI(currentPage).host.orEmpty().lowercase() }
                .getOrDefault("").let { it == "youtube.com" || it.endsWith(".youtube.com") || it == "youtu.be" }
            if (primary == null && !youtubePage) {
                binding.browserStatus.text = "No continuous HLS/DASH playlist was found. This player may use short segments or a protected stream; it cannot be safely recorded in the background."
                return@evaluateJavascript
            }
            binding.useMediaButton.isEnabled = false
            binding.browserStatus.text = "Checking the selected playlist for audio…"
            val userAgent = binding.webView.settings.userAgentString
            val playlistCookie = primary?.let { CookieManager.getInstance().getCookie(it.url).orEmpty() }.orEmpty()
            lifecycleScope.launch {
                val inspection = if (primary != null && primary.url.substringBefore('?').endsWith(".m3u8", true)) {
                    withContext(Dispatchers.IO) { inspectRecordingPlaylist(primary, currentPage, userAgent, playlistCookie) }
                } else StreamManifestAudio.Inspection(StreamManifestAudio.Status.UNKNOWN)
                if (isFinishing || isDestroyed) return@launch
                binding.useMediaButton.isEnabled = true
                val pairedVideo = inspection.videoUrl?.takeIf(MediaUrlClassifier::isContinuousManifest)
                val pairedAudio = inspection.audioUrl?.takeIf(MediaUrlClassifier::isContinuousManifest)
                val video = pairedVideo ?: primary?.url ?: currentPage
                val audioFromMaster = if (pairedVideo != null && pairedAudio != null) {
                    mediaCandidates[pairedAudio]?.takeIf { now - it.capturedAtMs < 30_000L }
                        ?: MediaCandidate(pairedAudio, now, emptyMap())
                } else null
                // A sibling audio playlist may be loaded separately by a site's player.
                val observedAudio = if (audioFromMaster == null && primary != null &&
                    inspection.status != StreamManifestAudio.Status.INCLUDED && video.substringBefore('?').endsWith(".m3u8", true)) {
                    manifests.filter { MediaUrlClassifier.streamKind(it.url) == MediaUrlClassifier.StreamKind.AUDIO &&
                        it.url != video && MediaUrlClassifier.sameStreamFamily(video, it.url) }
                        .maxByOrNull(MediaCandidate::capturedAtMs)
                } else null
                val audio = audioFromMaster ?: observedAudio
                val audioConfirmed = inspection.status == StreamManifestAudio.Status.INCLUDED || audioFromMaster != null
                val launch = { launchNativeRecording(video, audio, primary, currentPage) }
                if (!audioConfirmed) {
                    AlertDialog.Builder(this@BrowserSessionActivity)
                        .setTitle("Audio is not confirmed")
                        .setMessage(if (audio == null) {
                            "No separate audio playlist was confirmed. This recording may be silent. Continue anyway, or cancel and unmute the player before trying again."
                        } else {
                            "A possible separate audio playlist was found, but its match could not be confirmed. Continue and check a short recording for sound, or cancel."
                        })
                        .setPositiveButton("Continue") { _, _ -> launch() }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else launch()
            }
        }
    }

    private fun inspectRecordingPlaylist(candidate: MediaCandidate, page: String, userAgent: String, cookie: String): StreamManifestAudio.Inspection =
        runCatching {
            val connection = URL(candidate.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", candidate.header("user-agent").ifBlank { userAgent })
            connection.setRequestProperty("Referer", candidate.header("referer").ifBlank { page })
            val requestCookie = candidate.header("cookie").ifBlank { cookie }
            if (requestCookie.isNotBlank()) connection.setRequestProperty("Cookie", requestCookie)
            try {
                val content = InputStreamReader(connection.inputStream, Charsets.UTF_8).use { reader ->
                    val text = StringBuilder()
                    val chars = CharArray(4096)
                    while (text.length < 256_000) {
                        val count = reader.read(chars, 0, minOf(chars.size, 256_000 - text.length))
                        if (count <= 0) break
                        text.append(chars, 0, count)
                    }
                    text.toString()
                }
                StreamManifestAudio.inspect(candidate.url, content)
            } finally { connection.disconnect() }
        }.getOrDefault(StreamManifestAudio.Inspection(StreamManifestAudio.Status.UNKNOWN))

    private fun launchNativeRecording(video: String, audio: MediaCandidate?, primary: MediaCandidate?, page: String) {
        if (DownloadState.running) return
        val delaySeconds = binding.audioDelayInput.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (delaySeconds == null || !delaySeconds.isFinite() || delaySeconds !in -10.0..10.0) {
            binding.browserStatus.text = "Enter an audio sync adjustment between -10 and +10 seconds. Use 0 if unsure."
            return
        }
        val delayMs = (delaySeconds * 1000).toInt()
        // A master playlist and its video/audio playlists can live on different
        // hosts or use different partitioned cookies. Keep each session scoped
        // to the URL that the recorder will actually request.
        fun cookies(url: String, candidate: MediaCandidate?): String =
            candidate?.takeIf { it.url == url }?.header("cookie").orEmpty().ifBlank {
                CookieManager.getInstance().getCookie(url).orEmpty()
            }
        val videoCandidate = mediaCandidates[video]?.takeIf {
            System.currentTimeMillis() - it.capturedAtMs < 30_000L
        } ?: primary?.takeIf { it.url == video }
        val videoHeaders = (videoCandidate?.headers ?: primary?.headers.orEmpty())
            .filterKeys { !it.equals("cookie", ignoreCase = true) }
        val request = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START).apply {
            putExtra(RecordingService.EXTRA_URL, video)
            putExtra(RecordingService.EXTRA_PAGE_URL, pageUrl)
            putExtra(RecordingService.EXTRA_AUDIO_URL, audio?.url.orEmpty())
            putExtra(RecordingService.EXTRA_AUDIO_DELAY_MS, delayMs)
            putExtra(RecordingService.EXTRA_REFERER, primary?.header("referer").orEmpty().ifBlank { page })
            putExtra(RecordingService.EXTRA_COOKIES, cookies(video, videoCandidate))
            putExtra(RecordingService.EXTRA_AUDIO_COOKIES,
                audio?.let { cookies(it.url, it) }.orEmpty())
            putExtra(RecordingService.EXTRA_USER_AGENT, primary?.header("user-agent").orEmpty()
                .ifBlank { binding.webView.settings.userAgentString })
            putExtra(RecordingService.EXTRA_HEADERS_JSON, JSONObject(videoHeaders).toString())
            putExtra(RecordingService.EXTRA_AUDIO_HEADERS_JSON, JSONObject(audio?.headers.orEmpty()).toString())
        }
        try {
            ContextCompat.startForegroundService(this, request)
            getSharedPreferences("recording_audio_sync", MODE_PRIVATE).edit()
                .putInt(audioSyncPreferenceKey(), delayMs).apply()
            // The site may issue fresh short-lived media URLs while its player
            // runs. Keep this browser open so the relay can adopt them.
            nativeRecordingActive = true
            renewalVideoUrl = video
            renewalAudioUrl = audio?.url.orEmpty()
            lastRenewalOffer.clear()
            binding.useMediaButton.text = "Stop & save"
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                WebViewCompat.setAudioMuted(binding.webView, true)
                recordBrowserEvent("browser_audio_output_muted")
            } else {
                binding.webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(m => { m.muted = true; });", null)
                recordBrowserEvent("browser_audio_element_mute_fallback")
            }
            binding.browserStatus.text = "Recording in the background. The browser session will supply fresh stream URLs when available."
            pollHandler.postDelayed(renewalPoll, 20_000L)
            // Keep this activity and its WebView in the task stack while the
            // main screen moves to the front. Do not destroy the browser.
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (error: Exception) {
            binding.browserStatus.text = "Could not start background recording: ${error.message}"
        }
    }

    private fun audioSyncPreferenceKey(): String =
        "audio_delay_ms:" + runCatching { java.net.URI(pageUrl).host.orEmpty().lowercase() }.getOrDefault("")

    private fun sameRendition(original: String, fresh: String): Boolean =
        original.isNotBlank() && MediaUrlClassifier.isContinuousManifest(fresh) &&
            runCatching { java.net.URI(original).rawPath == java.net.URI(fresh).rawPath &&
                MediaUrlClassifier.sameStreamFamily(original, fresh) }.getOrDefault(false)

    private fun recordBrowserEvent(code: String) {
        if (!nativeRecordingActive) return
        runCatching {
            startService(Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_BROWSER_EVENT)
                .putExtra(RecordingService.EXTRA_EVENT_CODE, code))
        }
    }

    private fun checkPlayerAfterReload() {
        if (!waitingForActualStream || !nativeRecordingActive) return
        binding.webView.evaluateJavascript("""
            (function() {
              const ad = !!document.querySelector('.ad-showing, .ad-playing, .video-ads, .ima-ad-container, [data-ad-state="playing"]');
              const playing = Array.from(document.querySelectorAll('video')).some(v => !v.paused && !v.ended && v.readyState >= 2);
              return JSON.stringify({ad:ad, playing:playing});
            })();
        """.trimIndent()) { raw ->
            if (!nativeRecordingActive || !waitingForActualStream) return@evaluateJavascript
            val result = parseBrowserResponse(raw)
            when {
                result?.optBoolean("ad") == true -> recordBrowserEvent("browser_reload_waiting_for_ad")
                result?.optBoolean("playing") == true -> {
                    waitingForActualStream = false
                    recordBrowserEvent("browser_reload_actual_stream_playing")
                    val recent = mediaCandidates.values.any {
                        System.currentTimeMillis() - it.capturedAtMs in 0L..45_000L &&
                            (sameRendition(renewalVideoUrl, it.url) || sameRendition(renewalAudioUrl, it.url))
                    }
                    offerRenewal("video", renewalVideoUrl)
                    offerRenewal("audio", renewalAudioUrl)
                    if (binding.streamRecoveryControls.visibility == View.VISIBLE) {
                        binding.browserStatus.text = if (recent)
                            "Fresh media found. The recorder is verifying it; return to the main screen to watch progress."
                        else "The player is running, but no matching fresh media URL was detected yet. Keep it playing and check again, or Stop & save."
                    }
                }
                else -> {
                    recordBrowserEvent("browser_reload_player_not_playing")
                    if (binding.streamRecoveryControls.visibility == View.VISIBLE) {
                        binding.browserStatus.text = "The actual stream is not playing yet. Complete any check or ad, start the video, then tap Check playing stream. If it ended, tap Stop & save."
                    }
                }
            }
        }
    }

    private fun offerRenewal(kind: String, original: String) {
        if (original.isBlank() || !nativeRecordingActive) return
        val now = System.currentTimeMillis()
        val candidate = mediaCandidates.values.filter { media ->
            now - media.capturedAtMs in 0L..45_000L && sameRendition(original, media.url)
        }.maxByOrNull(MediaCandidate::capturedAtMs) ?: run {
            recordBrowserEvent(if (kind == "audio") "audio_renewal_no_fresh_candidate" else "video_renewal_no_fresh_candidate")
            return
        }
        val cookie = candidate.header("cookie").ifBlank {
            CookieManager.getInstance().getCookie(candidate.url).orEmpty()
        }
        val headers = candidate.headers.filterKeys { !it.equals("cookie", true) }
        // LL-HLS sequence/part directives change every poll; they are not a
        // renewed session. Ignore them when deciding whether to send an update.
        val stableQuery = candidate.url.substringAfter('?', "").split('&').filter { parameter ->
                parameter.substringBefore('=').lowercase() !in
                    setOf("_hls_msn", "_hls_part", "_hls_skip")
            }.filter(String::isNotEmpty).joinToString("&")
        val stableUrl = candidate.url.substringBefore('?') +
            (if (stableQuery.isNotEmpty()) "?$stableQuery" else "")
        val signature = stableUrl + "\n" + cookie + "\n" + headers.toSortedMap().toString()
        if (lastRenewalOffer[kind] == signature) {
            recordBrowserEvent(if (kind == "audio") "audio_renewal_credentials_unchanged" else "video_renewal_credentials_unchanged")
            return
        }
        recordBrowserEvent(if (kind == "audio") "audio_renewal_offer_attempt" else "video_renewal_offer_attempt")
        val update = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_RENEW_STREAM).apply {
            putExtra(RecordingService.EXTRA_RENEW_KIND, kind)
            putExtra(RecordingService.EXTRA_URL, candidate.url)
            putExtra(RecordingService.EXTRA_COOKIES, cookie)
            putExtra(RecordingService.EXTRA_REFERER, candidate.header("referer").ifBlank { pageUrl })
            putExtra(RecordingService.EXTRA_USER_AGENT, candidate.header("user-agent")
                .ifBlank { binding.webView.settings.userAgentString })
            putExtra(RecordingService.EXTRA_HEADERS_JSON, JSONObject(headers).toString())
        }
        runCatching { startService(update) }
            .onSuccess {
                lastRenewalOffer[kind] = signature
                recordBrowserEvent(if (kind == "audio") "audio_renewal_offer_sent" else "video_renewal_offer_sent")
            }
            .onFailure {
                recordBrowserEvent(if (kind == "audio") "audio_renewal_offer_failed" else "video_renewal_offer_failed")
            }
    }

    private fun startBrowserRecording() {
        if (DownloadState.running) {
            binding.browserStatus.text = "Finish the current process before recording."
            return
        }
        binding.useMediaButton.isEnabled = false
        binding.cancelButton.isEnabled = false
        startPending = true
        val script = assets.open("media-element-recorder.js").bufferedReader().use { it.readText() }
        binding.webView.evaluateJavascript(script + "\nwindow.__ytdlpCaptureStartResult = ''; window.__ytdlpElementCapture.start().then(result => { window.__ytdlpCaptureStartResult = result; }); 'starting';") {
            pollCaptureStart(0)
        }
    }

    private fun pollCaptureStart(attempt: Int) {
        if (!startPending || isFinishing) return
        binding.webView.evaluateJavascript("window.__ytdlpCaptureStartResult || ''") { raw ->
            if (!startPending || isFinishing) return@evaluateJavascript
            val result = parseBrowserResponse(raw)
            if (result == null && attempt < 30) {
                pollHandler.postDelayed({ pollCaptureStart(attempt + 1) }, 200L)
                return@evaluateJavascript
            }
            startPending = false
            binding.cancelButton.isEnabled = true
            if (result?.optBoolean("ok") != true) {
                val reason = (if (result == null || result.isNull("error")) "" else result.optString("error"))
                    .ifBlank { "Android WebView timed out while preparing the video element." }
                binding.browserStatus.text = reason
                DownloadState.log = (DownloadState.log + "\nBrowser recording setup: " + reason).takeLast(16_000)
                binding.useMediaButton.isEnabled = true
                return@evaluateJavascript
            }
            try {
                BrowserCaptureStore.begin(applicationContext)
                ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_BROWSER_START))
            } catch (error: Exception) {
                binding.webView.evaluateJavascript("window.__ytdlpElementCapture.discard()", null)
                BrowserCaptureStore.discard()
                binding.browserStatus.text = "Could not start recorder: ${error.message}"
                binding.useMediaButton.isEnabled = true
                return@evaluateJavascript
            }
            captureActive = true
            stopRequested = false
            binding.useMediaButton.text = "Stop & save"
            binding.useMediaButton.isEnabled = true
            binding.cancelButton.text = "Discard"
            val audio = result.optInt("audioTracks")
            binding.browserStatus.text = "Recording ${result.optInt("width")}×${result.optInt("height")} • 1 video + $audio audio track(s)"
            pollCapture()
            if (audio == 0) {
                AlertDialog.Builder(this)
                    .setTitle("No audio track detected")
                    .setMessage("The player exposed video but no audio. This recording may be silent. You can continue or discard it and try again after unmuting.")
                    .setPositiveButton("Continue", null)
                    .setNegativeButton("Discard") { _, _ -> discardBrowserRecording() }
                    .show()
            }
        }
    }

    private fun parseBrowserResponse(raw: String?): JSONObject? = runCatching {
        val encoded = JSONTokener(raw ?: "null").nextValue() as? String ?: return@runCatching null
        JSONObject(encoded)
    }.getOrNull()

    private fun pollCapture() {
        if (!captureActive || polling) return
        polling = true
        binding.webView.evaluateJavascript("window.__ytdlpElementCapture.poll()") { raw ->
            polling = false
            if (!captureActive) return@evaluateJavascript
            val response = parseBrowserResponse(raw)
            val error = if (response == null || response.isNull("error")) "" else response.optString("error")
            if (response == null || error.isNotBlank()) {
                abortBrowserRecording(error.ifBlank { "The player stopped responding." })
                return@evaluateJavascript
            }
            try {
                val chunk = response.optString("chunk")
                if (chunk.isNotEmpty()) capturedBytes = BrowserCaptureStore.append(chunk)
            } catch (error: Exception) {
                abortBrowserRecording(error.message ?: "Android could not save a recording chunk.")
                return@evaluateJavascript
            }
            if (response.optBoolean("done")) {
                captureActive = false
                binding.browserStatus.text = "Saving ${capturedBytes / 1024} KiB of captured video…"
                startService(Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_BROWSER_FINISH))
                finish()
            } else {
                if (capturedBytes > 0L) binding.browserStatus.text = "Recording video + available audio • ${capturedBytes / 1024} KiB"
                pollHandler.postDelayed({ pollCapture() }, 100L)
            }
        }
    }

    private fun stopBrowserRecording() {
        if (!captureActive || stopRequested) return
        stopRequested = true
        binding.useMediaButton.isEnabled = false
        binding.browserStatus.text = "Finishing recording; saving the final audio and video chunks…"
        binding.webView.evaluateJavascript("window.__ytdlpElementCapture.stop()", null)
    }

    private fun discardBrowserRecording() {
        if (!captureActive) return
        captureActive = false
        binding.webView.evaluateJavascript("window.__ytdlpElementCapture.discard()", null)
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_CANCEL))
        finish()
    }

    private fun abortBrowserRecording(message: String) {
        DownloadState.log = (DownloadState.log + "\nBrowser recording stopped unexpectedly: " + message).takeLast(16_000)
        captureActive = false
        stopRequested = false
        capturedBytes = 0L
        binding.webView.evaluateJavascript("window.__ytdlpElementCapture.discard()", null)
        BrowserCaptureStore.discard()
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_CANCEL))
        binding.browserStatus.text = "Recording failed: $message"
        binding.useMediaButton.text = "Try recording again"
        binding.useMediaButton.isEnabled = true
        binding.cancelButton.text = "Close"
    }

    @Deprecated("Back navigation is handled here so an active recording is never discarded silently.")
    override fun onBackPressed() {
        if (!captureActive) { super.onBackPressed(); return }
        AlertDialog.Builder(this)
            .setTitle("Recording is still running")
            .setMessage("Save this recording before leaving the browser?")
            .setPositiveButton("Stop & save") { _, _ -> stopBrowserRecording() }
            .setNeutralButton("Keep recording", null)
            .setNegativeButton("Discard") { _, _ -> discardBrowserRecording() }
            .show()
    }

    private fun detectMedia() {
        if (mode != MODE_DOWNLOAD) return
        binding.browserStatus.text = "Looking for the active media stream…"
        val script = """
            (function() {
              const visible = el => {
                if (!el) return false;
                const r = el.getBoundingClientRect();
                const s = getComputedStyle(el);
                return r.width > 80 && r.height > 45 && s.display !== 'none' && s.visibility !== 'hidden';
              };
              const adSelectors = [
                '.ad-showing', '.ad-playing', '.video-ads', '.ima-ad-container',
                '[class*="adPlaying"]', '[class*="ad-playing"]', '[data-ad-state="playing"]'
              ];
              const adPlaying = adSelectors.some(selector =>
                Array.from(document.querySelectorAll(selector)).some(visible)
              );
              const videos = Array.from(document.querySelectorAll('video'))
                .filter(visible)
                .sort((a, b) => {
                  const aPlaying = !a.paused && !a.ended ? 1 : 0;
                  const bPlaying = !b.paused && !b.ended ? 1 : 0;
                  return (bPlaying - aPlaying) ||
                    ((b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight));
                });
              const video = videos[0];
              const sources = video ? Array.from(video.querySelectorAll('source')).map(s => s.src) : [];
              const activeUrls = [video && video.currentSrc, video && video.src].concat(sources)
                .filter(u => u && !u.startsWith('blob:'));
              const resources = (performance.getEntriesByType('resource') || [])
                .map(entry => entry.name)
                .filter(Boolean)
                .slice(-500);
              return JSON.stringify({ adPlaying, activeUrls, resources });
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { raw ->
            val payload = runCatching {
                val encoded = JSONTokener(raw).nextValue() as? String ?: return@runCatching null
                JSONObject(encoded)
            }.getOrNull()
            if (payload?.optBoolean("adPlaying") == true) {
                // Do not allow URLs observed during the ad to remain eligible after it ends.
                mediaCandidates.clear()
                binding.browserStatus.text = "An in-video ad appears to be playing. Wait for the actual video, then try again."
                Toast.makeText(this, "Wait for the ad to finish", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }

            val activeUrls = payload?.optJSONArray("activeUrls").toStringList()
                .filter(MediaUrlClassifier::isDirectMediaUrl)
            val now = System.currentTimeMillis()
            activeUrls.forEach { url ->
                mediaCandidates.compute(url) { _, existing ->
                    MediaCandidate(url, now, existing?.headers.orEmpty())
                }
            }
            payload?.optJSONArray("resources").toStringList()
                .filter(MediaUrlClassifier::isDirectMediaUrl)
                .forEach { url -> mediaCandidates.putIfAbsent(url, MediaCandidate(url, now, emptyMap())) }

            val selected = selectBestCandidate(activeUrls.toSet())
            if (selected == null) {
                binding.browserStatus.text = "No direct, non-ad media stream detected. Play the actual video for a few seconds, then try again."
                Toast.makeText(this, "No media stream detected", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            completeDownloadSelection(selected)
        }
    }

    private fun completeDownloadSelection(primary: MediaCandidate) {
        val currentPage = binding.webView.url ?: pageUrl
        fun cookies(candidate: MediaCandidate): String = candidate.header("cookie").ifBlank {
            CookieManager.getInstance().getCookie(candidate.url).orEmpty()
                .ifBlank { CookieManager.getInstance().getCookie(currentPage).orEmpty() }
        }
        val result = Intent().apply {
            putExtra(EXTRA_MEDIA_URL, primary.url)
            putExtra(EXTRA_REFERER, primary.header("referer").ifBlank { currentPage })
            putExtra(EXTRA_COOKIES, cookies(primary))
            putExtra(EXTRA_USER_AGENT, primary.header("user-agent").ifBlank { binding.webView.settings.userAgentString })
            putExtra(EXTRA_HEADERS_JSON, JSONObject(primary.headers).toString())
            putExtra(EXTRA_MODE, MODE_DOWNLOAD)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun selectBestCandidate(activeUrls: Set<String>): MediaCandidate? {
        // Keep download selection independent of the stream-recording classifier.
        val eligible = mediaCandidates.values.filter { MediaUrlClassifier.isDownloadCandidate(it.url) }
        val active = eligible.filter { it.url in activeUrls }
        val pool = if (active.isNotEmpty()) {
            active
        } else {
            val newest = eligible.maxOfOrNull(MediaCandidate::capturedAtMs) ?: return null
            eligible.filter { it.capturedAtMs >= newest - FRESH_CANDIDATE_WINDOW_MS }
        }
        return pool.maxByOrNull { candidate ->
                MediaUrlClassifier.score(candidate.url, candidate.url in activeUrls, candidate.capturedAtMs)
            }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadState.browserVisible = true
        DownloadState.browserVisibilityChangedAt = SystemClock.elapsedRealtime()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (DownloadState.running && DownloadState.operation == DownloadState.OPERATION_RECORD) {
            startService(Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_USER_INTERACTION))
        }
    }

    override fun onResume() {
        super.onResume()
        DownloadState.browserResumed = true
        if (intent.getBooleanExtra(EXTRA_RECOVERY, false) &&
            binding.streamRecoveryControls.visibility != View.VISIBLE) enterRecovery()
        recordBrowserEvent("browser_activity_resumed")
    }

    override fun onPause() {
        DownloadState.browserResumed = false
        super.onPause()
    }

    override fun onStop() {
        DownloadState.browserVisible = false
        DownloadState.browserVisibilityChangedAt = SystemClock.elapsedRealtime()
        recordBrowserEvent("browser_activity_hidden")
        super.onStop()
    }

    override fun onDestroy() {
        recordBrowserEvent("browser_session_destroyed")
        pollHandler.removeCallbacksAndMessages(null)
        if (startPending) binding.webView.evaluateJavascript("window.__ytdlpElementCapture?.discard()", null)
        if (receiverRegistered) unregisterReceiver(stopReceiver)
        if (stateReceiverRegistered) unregisterReceiver(recordingStateReceiver)
        if (refreshReceiverRegistered) unregisterReceiver(refreshReceiver)
        if (captureActive) discardBrowserRecording()
        binding.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    private fun applyInsets() {
        val root = binding.root
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    companion object {
        const val EXTRA_PAGE_URL = "page_url"
        const val EXTRA_RECOVERY = "record_403_recovery"
        const val EXTRA_MEDIA_URL = "media_url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_HEADERS_JSON = "headers_json"
        const val EXTRA_MODE = "mode"
        const val MODE_DOWNLOAD = "download"
        const val MODE_RECORD = "record"

        private const val FRESH_CANDIDATE_WINDOW_MS = 10_000L
        private val SAFE_CAPTURED_HEADERS = setOf(
            "accept", "accept-language", "cookie", "origin", "referer", "user-agent",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform"
        )
    }

    private data class MediaCandidate(
        val url: String,
        val capturedAtMs: Long,
        val headers: Map<String, String>
    ) {
        fun header(name: String): String = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value.orEmpty()
    }

}
