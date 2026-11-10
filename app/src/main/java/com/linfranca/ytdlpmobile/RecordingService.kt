package com.linfranca.ytdlpmobile

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class RecordingEngine(owner: MediaTaskService, taskId: String) : MediaTaskEngine(owner,taskId,false) {
    private val executor = Executors.newSingleThreadExecutor()
    private var processId: String? = null
    @Volatile private var pairedProcess: Process? = null
    @Volatile private var activeFinalizer: Process? = null
    @Volatile private var finalizationSkipRequested = false
    @Volatile private var liveRelay: LiveHlsRelay? = null
    private var mouflonResolver: MouflonPlaylistResolver? = null
    private var mouflonCapture = false
    private var lastSessionRefreshRequestAt = 0L
    private val pendingSafeEvents = ConcurrentLinkedQueue<String>()
    @Volatile private var saveWhenStopped = false
    @Volatile private var discardWhenStopped = false
    @Volatile private var stopWasRequested = false
    private var sessionDir: File? = null
    private var storageWatch: StorageGuard.Watch? = null
    private var detailedLogFile: File? = null
    private var startedAt = 0L
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var audioFinalizationCompleted = false
    private var audioFinalizationBypassed = false
    private var pairedRecording = false
    private var timingSnapshot: LiveHlsRelay.AudioTimeline? = null
    private var timingOutcome = "original_no_timeline"
    private var plannedAudioFilter: String? = null
    private var capturedPacketTiming: TransportStreamTiming? = null
    private var savedPacketTiming: TransportStreamTiming? = null
    private var captureStoppedAt = 0L
    private var timingFailureReason = ""
    private var manualAudioDelayMs = 0
    @Volatile private var recordedMediaSeconds = 0L
    @Volatile private var mediaProgressSeen = false
    @Volatile private var outputStalledSince = 0L
    @Volatile private var stallWasDetected = false
    private var lastStallUserActionAt = 0L
    @Volatile private var completionMonitor: LiveCompletionMonitor? = null
    @Volatile private var directCaptureActive = false
    private var confirmedCompletion = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onDestroy() {
        executor.shutdown()
        completionMonitor?.close();completionMonitor=null
        getSystemService(NotificationManager::class.java).cancel(notificationId(STALL_NOTIFICATION_ID))
        recordingWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        recordingWakeLock = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int,fgsType: Int) {
        MonitoringStore.log(this,"media task timeout; task=$taskId; ${DeviceDiagnostics.snapshot(this)}")
        stopRecording(save = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var transferLease = ""
    private var transferId = ""
    private var transferStatus = "Interrupted before completion"
    private var transferLog = ""
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action in setOf(ACTION_START, ACTION_BROWSER_START)) {
            val id=intent?.getStringExtra(TransferQueue.JOB).orEmpty()
            val lease=intent?.getStringExtra(TransferQueue.LEASE).orEmpty()
            if(lease.isBlank() || !TransferQueue.owns(this,id,lease)) { stopSelfResult(startId);return START_NOT_STICKY }
            transferId=id;transferLease=lease
        }
        when (intent?.action) {
            ACTION_START -> if (!taskState.running) startRecording(intent)
            ACTION_BROWSER_START -> startBrowserCapture()
            ACTION_BROWSER_FINISH -> finishBrowserCapture()
            ACTION_STOP_SAVE -> if (browserCapture) requestBrowserStop() else stopRecording(save = true)
            ACTION_CANCEL -> if (browserCapture) cancelBrowserCapture() else stopRecording(save = false)
            ACTION_RENEW_STREAM -> renewStream(intent)
            ACTION_BROWSER_EVENT -> recordBrowserEvent(intent)
            ACTION_USER_INTERACTION -> if (taskState.running && !stopWasRequested &&
                outputStalledSince != 0L) {
                lastStallUserActionAt = SystemClock.elapsedRealtime()
                recordSafeEvent("stall_timeout_reset_by_user")
            }
        }
        return START_NOT_STICKY
    }

    private var browserCapture = false
    private var browserFinalizing = false

    private fun startBrowserCapture() {
        if (taskState.running) return
        browserCapture = true
        taskState.running = true
        taskState.operation = taskState.OPERATION_RECORD
        taskState.progress = 0
        recordedMediaSeconds = 0L
        mediaProgressSeen = false
        taskState.log = "Capturing the video element and its available audio track…"
        startForeground(notificationId(NOTIFICATION_ID), notification("Recording browser video…"))
        TransferQueue.started(this,transferId,transferLease)
        update("Recording browser video • use Stop & save when finished")
    }

    private fun requestBrowserStop() {
        update("Finishing browser recording…")
        sendBroadcast(Intent(ACTION_BROWSER_STOP_REQUEST).setPackage(packageName))
    }

    private fun finishBrowserCapture() {
        if (!browserCapture || browserFinalizing) return
        browserFinalizing=true
        val source = BrowserCaptureStore.take()
        executor.execute {
            try {
                check(source != null && source.length() >= 1024L) { "No media data was recorded." }
                exportBrowserWebm(source)
                finish("Recording saved to Downloads/yt-dlp Mobile/Recordings", taskState.log)
            } catch (error: Exception) {
                finish("Recording could not be saved: ${error.message}", taskState.log + "\n" + error.message)
            } finally {
                if(transferStatus.startsWith("Recording saved")) source?.delete()
                browserCapture = false;browserFinalizing=false
                TransferQueue.complete(this,transferId,transferStatus,transferLog,lease=transferLease)
            }
        }
    }

    private fun cancelBrowserCapture() {
        if(browserFinalizing) { update("Browser recording is already being saved…");return }
        BrowserCaptureStore.discard()
        browserCapture = false
        finish("Browser recording discarded", taskState.log)
        TransferQueue.complete(this,transferId,transferStatus,transferLog,lease=transferLease)
    }

    private fun exportBrowserWebm(source: File): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "browser-recording-$stamp.webm")
            put(MediaStore.Downloads.MIME_TYPE, "video/webm")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/yt-dlp Mobile/Recordings")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Android could not create the recording.")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                copyRecordingWithProgress(source, output)
            }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            TransferQueue.exported(this, transferId, uri,transferLease)
            return uri
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun startRecording(intent: Intent) {
        audioFinalizationCompleted = false
        audioFinalizationBypassed = false
        timingSnapshot = null
        plannedAudioFilter = null
        capturedPacketTiming = null
        savedPacketTiming = null
        captureStoppedAt = 0L
        timingOutcome = "original_no_timeline"
        timingFailureReason = "Live playlist relay did not provide a source timeline."
        finalizationSkipRequested = false
        saveWhenStopped = false
        discardWhenStopped = false
        stopWasRequested = false
        taskState.recording403RecoveryAvailable = false
        recordedMediaSeconds = 0L
        mediaProgressSeen = false
        outputStalledSince = 0L
        stallWasDetected = false
        lastStallUserActionAt = 0L
        completionMonitor?.close();completionMonitor=null;confirmedCompletion=false;directCaptureActive=false
        liveRelay = null
        mouflonResolver = null
        mouflonCapture = false
        lastSessionRefreshRequestAt = 0L
        pendingSafeEvents.clear()
        directDiagnosticKeys.clear()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL).orEmpty()
        taskState.recordingPageUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
        taskState.recordingVideoUrl = url
        taskState.recordingAudioUrl = audioUrl
        pairedRecording = audioUrl.isNotBlank()
        val audioDelayMs = intent.getIntExtra(EXTRA_AUDIO_DELAY_MS, 0).coerceIn(-10_000, 10_000)
        manualAudioDelayMs = audioDelayMs
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val cookies = intent.getStringExtra(EXTRA_COOKIES).orEmpty()
        val audioCookies = intent.getStringExtra(EXTRA_AUDIO_COOKIES).orEmpty()
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        val headers = parseHeaders(intent.getStringExtra(EXTRA_HEADERS_JSON).orEmpty())
        val audioHeaders = parseHeaders(intent.getStringExtra(EXTRA_AUDIO_HEADERS_JSON).orEmpty())
        val pageCookies = intent.getStringExtra(EXTRA_PAGE_COOKIES).orEmpty()
        val pageUserAgent = intent.getStringExtra(EXTRA_PAGE_USER_AGENT).orEmpty()
        val pageHeaders = WebpageAccessDiagnostic.pageHeaders(parseHeaders(intent.getStringExtra(EXTRA_PAGE_HEADERS_JSON).orEmpty()))
        val pageObservation = intent.getStringExtra(EXTRA_PAGE_OBSERVATION).orEmpty()
        var customExtractorAttempt = false

        taskState.running = true
        taskState.operation = taskState.OPERATION_RECORD
        taskState.progress = 0
        taskState.log = if (audioUrl.isBlank()) {
            "Starting direct stream recorder…"
        } else {
            "Starting paired video + audio stream recorder…\nChecking live stream timing." +
                if (audioDelayMs != 0) "\nManual audio adjustment: " +
                    String.format(Locale.US, "%+.2f s", audioDelayMs / 1000.0) else ""
        }
        taskState.log += "\nRecorder selection: " + if (audioUrl.isBlank())
            "direct yt-dlp; reason=no separate audio URL supplied. Live following depends on extractor result."
            else "paired FFmpeg; reason=separate audio URL supplied."
        taskState.log += "\n" + StreamDeliveryDiagnostics.describeUrl(url)
        taskState.log += "\n" + intent.getStringExtra(EXTRA_STREAM_DIAGNOSTICS).orEmpty().take(2000)
        taskState.log += "\nSession access: cookies present=${cookies.isNotBlank()}, extra headers=${headers.size}; values omitted."
        startedAt = SystemClock.elapsedRealtime()
        detailedLogFile = null
        update("Recording stream • 0s • 0 B")
        startForeground(notificationId(NOTIFICATION_ID), notification("Recording stream…"))
        TransferQueue.started(this,transferId,transferLease)
        // A foreground notification does not guarantee continuous CPU time
        // while the screen is off. Release this as soon as capture/export ends.
        recordingWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:stream-recording")
            .also { it.acquire(4L * 60L * 60L * 1_000L) }

        executor.execute {
            val folder = File(TransferQueue.workDirectory(this,transferId,"recording"),UUID.randomUUID().toString()).also {
                it.mkdirs()
                sessionDir = it
                storageWatch = StorageGuard.Watch(this,it)
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            // MPEG-TS remains readable if the live FFmpeg process is stopped mid-stream.
            var output = File(folder, "recording-$stamp.ts")
            try {
                StorageGuard.working(this,phase="recording")
                YoutubeDL.init(applicationContext)
                FFmpeg.init(applicationContext)
                completionMonitor = LiveCompletionMonitor(this, url, LiveHlsRelay.Access(userAgent,referer,cookies,headers),
                    taskState.recordingPageUrl,pageUserAgent,pageCookies,::recordSafeEvent) { outputStalledSince!=0L }.also { it.start() }
                if (saveWhenStopped || discardWhenStopped) error("Recording stopped before capture began.")
                detailedLogFile = File(folder, "recording-$stamp-diagnostic-log.txt").also {
                    it.writeText(taskState.log + "\n")
                }
                val installedVersion = runCatching {
                    YoutubeDL.versionName(applicationContext) ?: YoutubeDL.version(applicationContext) ?: "unknown"
                }.getOrDefault("unknown")
                recordSafeEvent("Installed yt-dlp version: " + (RecorderErrorDiagnostic.safeLine(installedVersion) ?: "unknown"))
                val hlsPlan = if (audioUrl.isBlank() && url.substringBefore('?').endsWith(".m3u8", true)) {
                    LiveHlsRecordingPlan.inspect(url, LiveHlsRelay.Access(userAgent, referer, cookies, headers)) { event -> recordSafeEvent(event) }
                } else null
                hlsPlan?.let { recordSafeEvent(it.detail) }
                if (saveWhenStopped || discardWhenStopped) error("Recording stopped during playlist inspection.")
                if (audioUrl.isNotBlank()) {
                    // A single yt-dlp "best" HLS format may select just the video
                    // rendition. Give FFmpeg two inputs and map both tracks.
                    recordPairedStreams(url, audioUrl, output, referer, userAgent,
                        cookies, audioCookies.ifBlank { cookies }, headers, audioHeaders, audioDelayMs)
                } else if (hlsPlan?.kind == LiveHlsRecordingPlan.Kind.LIVE) {
                    val separate = hlsPlan.audio.isNotBlank()
                    pairedRecording = separate
                    taskState.recordingVideoUrl = hlsPlan.video
                    taskState.recordingAudioUrl = hlsPlan.audio
                    recordSafeEvent(if (separate) "hls_preflight_selected_paired_relay" else "hls_preflight_selected_combined_relay")
                    recordPairedStreams(hlsPlan.video, hlsPlan.audio.ifBlank { hlsPlan.video },
                        output, referer, userAgent, cookies, cookies, headers, headers,
                        audioDelayMs, combinedInput = !separate)
                } else if (hlsPlan?.kind == LiveHlsRecordingPlan.Kind.CUSTOM &&
                    MouflonPlaylistResolver.compatibleMaster(url)) {
                    customExtractorAttempt = true
                    val knownPage = android.net.Uri.parse(taskState.recordingPageUrl).host.orEmpty().lowercase(Locale.ROOT) in
                        setOf("stripchat.com", "www.stripchat.com", "xhamsterlive.com", "www.xhamsterlive.com")
                    if (knownPage) verifyMouflonPublicSource(url, folder, pageUserAgent, pageCookies, pageHeaders)
                    else {
                        check(MouflonPlaylistResolver.observedMaster(url)) { "MOUFLON source was not observed in the browser. Play and detect again." }
                        recordSafeEvent("mouflon_compatible_source; browser master observed; page public-state verification unavailable; mapping and media validation required")
                    }
                    if (saveWhenStopped || discardWhenStopped) error("Recording stopped during public source verification.")
                    val access = LiveHlsRelay.Access(userAgent, referer, cookies, headers)
                    val resolver = MouflonPlaylistResolver(url) { event -> recordSafeEvent(event) }
                    mouflonResolver = resolver
                    val deadline = SystemClock.elapsedRealtime() + 25_000L
                    var selected: String? = null
                    while (selected == null && SystemClock.elapsedRealtime() < deadline &&
                        !saveWhenStopped && !discardWhenStopped) {
                        try {
                            val master = LiveHlsRecordingPlan.read(url, resolver.accessFor(java.net.URI(url), access)) { event -> recordSafeEvent(event) }
                            val rendition = resolver.preferredRendition(url, master)
                            val media = LiveHlsRecordingPlan.read(rendition, resolver.accessFor(java.net.URI(rendition), access)) { event -> recordSafeEvent(event) }
                            val resolved = resolver.rewrite(java.net.URI(rendition), media)
                            check(!resolved.contains("#EXT-X-ENDLIST") && resolved.contains("#EXTINF:")) {
                                "The browser stream has ended or has no complete media segments."
                            }
                            selected = rendition
                        } catch (_: MouflonPlaylistResolver.Pending) {
                            Thread.sleep(500)
                        }
                    }
                    if (saveWhenStopped || discardWhenStopped) error("Recording stopped during browser mapping verification.")
                    val source = selected ?: error("No verified fresh MOUFLON mapping. Reopen Browser Assist, play the stream, and detect again.")
                    taskState.recordingVideoUrl = source
                    taskState.recordingAudioUrl = ""
                    pairedRecording = false
                    mouflonCapture = true
                    // AV1 in MPEG-TS can become an unrecognized private-data track.
                    // Fragmented MP4 preserves AV1/AAC and remains salvageable on stop.
                    output = File(folder, "recording-$stamp.mp4")
                    recordSafeEvent("mouflon_output_selected_fragmented_mp4; AV1/AAC retained without transcoding")
                    recordSafeEvent("mouflon_verified_relay_selected; browser rendition retained; combined audio/video; source timestamps retained")
                    recordPairedStreams(source, source, output, referer, userAgent,
                        cookies, cookies, headers, headers, 0, combinedInput = true)
                } else {
                    pendingSafeEvents.clear()
                    recordSafeEvent("direct_recorder_started")
                    processId = "record-${UUID.randomUUID()}"
                    val customPlaylist = hlsPlan?.kind == LiveHlsRecordingPlan.Kind.CUSTOM
                    val extractorUrl = if (customPlaylist) taskState.recordingPageUrl
                        .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                        ?: error("This custom playlist needs the original webpage. Reopen Browser Assist and detect again.")
                        else url
                    val request = YoutubeDLRequest(extractorUrl)
                        .addOption("--newline")
                        .addOption("--hls-use-mpegts")
                        .addOption("--no-part")
                        .addOption("--no-mtime")
                        .addOption("--retries", 10)
                        .addOption("--fragment-retries", 10)
                        .addOption("-o", output.absolutePath)
                    if (customPlaylist) {
                        customExtractorAttempt = true
                        val pageHost = android.net.Uri.parse(extractorUrl).host.orEmpty().lowercase(Locale.ROOT)
                        if (pageHost in setOf("stripchat.com", "www.stripchat.com", "xhamsterlive.com", "www.xhamsterlive.com")) {
                            val pluginRoot = File(folder, "extractor-compat")
                            val plugin = File(pluginRoot, "mobile-public-state/yt_dlp_plugins/extractor/mobile_public_stream.py")
                            plugin.parentFile?.mkdirs()
                            assets.open("yt_dlp_plugins/extractor/mobile_public_stream.py").use { input ->
                                plugin.outputStream().use { input.copyTo(it) }
                            }
                            val encodedMaster = android.util.Base64.encodeToString(url.toByteArray(Charsets.UTF_8),
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                            request.addOption("--plugin-dirs", pluginRoot.absolutePath)
                            request.addOption("--extractor-args", "stripchat:browser_master=$encodedMaster")
                            request.addOption("--socket-timeout", 10)
                            recordSafeEvent("public_state_compatibility_requested; page host=$pageHost; revision=1; plugin selection will be confirmed by extractor output")
                        }
                        request.addOption("--downloader", "ffmpeg")
                        recordSafeEvent("custom_playlist_site_extractor_attempt; FFmpeg live following requested; support depends on installed yt-dlp extractor")
                        taskState.log += "\nTrying the original webpage extractor for this custom stream. Support is not yet guaranteed."
                    } else if (MediaUrlClassifier.isContinuousManifest(url)) request.addOption("--force-generic-extractor")
                    if (customPlaylist) {
                        recordSafeEvent("custom_page_attempt=1; request context=webpage; media headers excluded; header names=" + pageHeaders.keys.joinToString(", ") + ", User-Agent")
                        recordSafeEvent("media request context excluded from page; header names=" + headers.keys.sorted().joinToString(", ") + "; media cookies present=${cookies.isNotBlank()}; audio cookies present=${audioCookies.isNotBlank()}")
                        recordSafeEvent("page cookie source=WebView lookup for original page URL; values omitted; original cookie attributes unavailable; exporting host-only session cookies; present=${pageCookies.isNotBlank()}")
                        recordSafeEvent("Browser comparison: " + (RecorderErrorDiagnostic.safeLine(pageObservation) ?: "no page observation available"))
                        if (pageUserAgent.isNotBlank()) request.addOption("--user-agent", pageUserAgent)
                        pageHeaders.forEach { (name, value) -> request.addOption("--add-headers", "$name:$value") }
                        if (pageCookies.isNotBlank()) {
                            val pageCookieFile = File(folder, "page-access-cookies.txt")
                            pageCookieFile.writeText(WebpageAccessDiagnostic.cookieFile(extractorUrl, pageCookies))
                            request.addOption("--cookies", pageCookieFile.absolutePath)
                        }
                        recordSafeEvent("custom_page_retry_policy; yt-dlp retries=10, fragment retries=10; application does not silently repeat page extraction; extractor retry messages logged when available")
                    } else {
                        if (referer.isNotBlank()) request.addOption("--referer", referer)
                        if (userAgent.isNotBlank()) request.addOption("--user-agent", userAgent)
                        val usableCookies = cookies.ifBlank { audioCookies }
                        if (usableCookies.isNotBlank()) request.addOption("--add-headers", "Cookie:$usableCookies")
                        (audioHeaders + headers).filterKeys { it.lowercase() !in setOf("cookie", "referer", "user-agent") }
                            .forEach { (name, value) -> request.addOption("--add-headers", "$name:$value") }
                    }

                    if (saveWhenStopped || discardWhenStopped) error("Recording stopped before capture began.")
                    directCaptureActive = true
                    watchDirectCompletion(output)
                    YoutubeDL.execute(request, processId, true) { _, _, line ->
                        if (customPlaylist && line.contains("App compatibility:")) {
                            RecorderErrorDiagnostic.safeLine(line)?.let { recordSafeEvent("Compatibility observation: $it") }
                        }
                        if (customPlaylist && (line.contains("Extracting URL:") || line.contains("Retrying") || line.contains("retry", true))) {
                            RecorderErrorDiagnostic.safeLine(line)?.let { recordSafeEvent("Extractor observation: $it") }
                        }
                        if (line.trimStart().startsWith("ERROR:")) {
                            RecorderErrorDiagnostic.safeLine(line)?.let { detail ->
                                recordDirectDiagnosticOnce("extractor_error", "Extractor failure: $detail")
                            }
                        }
                        if (line.contains("[ffmpeg]")) recordDirectDiagnosticOnce("engine_ffmpeg", "Direct engine: FFmpeg selected by yt-dlp.")
                        if (line.contains("[hlsnative]")) recordDirectDiagnosticOnce("engine_hlsnative", "Direct engine: hlsnative selected by yt-dlp.")
                        if (line.contains("Total fragments:")) recordSafeEvent("Extractor fragment report: " + line.substringAfter("Total fragments:").trim().take(32))
                        if (line.isNotBlank()) taskState.log = (taskState.log + "\n" + line).takeLast(MAX_LOG_CHARS)
                        update(recordingStatus(folder))
                    }
                }
                if (customExtractorAttempt) recordSafeEvent("custom_page_attempt=1 completed; user stop=$stopWasRequested; bytes=${output.length()}; return does not prove stream ended")
                recordSafeEvent(if (confirmedCompletion || completionMonitor?.reason!=null || liveRelay?.sourceEnded()==true)
                    "recorder_saved_after_confirmed_completion" else if (stopWasRequested) "recorder_exit_after_stop_request" else
                    "recorder_returned_without_user_stop_stream_end_unconfirmed")
                saveWhenStopped = true // Preserve existing save behavior; return alone is not proof of ENDLIST.
            } catch (error: Exception) {
                recordSafeEvent("Recorder exception: type=${error.javaClass.simpleName}; user stop=$stopWasRequested; discard=$discardWhenStopped")
                RecorderErrorDiagnostic.describe(error).forEach(::recordSafeEvent)
                if (customExtractorAttempt) {
                    recordSafeEvent("custom_page_attempt=1 failed; bytes=${output.length()}; request change=page access separated from CDN access")
                    if (!stopWasRequested && !discardWhenStopped) {
                        WebpageAccessDiagnostic.probe(taskState.recordingPageUrl, pageUserAgent, pageCookies, pageHeaders, ::recordSafeEvent)
                    } else recordSafeEvent("page_probe_skipped; user stopped recording")
                }
                if (!saveWhenStopped && !discardWhenStopped) {
                    taskState.log = (taskState.log + "\n" + error.message.orEmpty()).takeLast(MAX_LOG_CHARS)
                }
            } finally {
                directCaptureActive=false
                completionMonitor?.close();completionMonitor=null
                File(folder, "page-access-cookies.txt").delete()
                if (mouflonCapture && !discardWhenStopped && output.isFile && output.length() > 0L) {
                    runCatching { FragmentedMp4Recovery.trimIncompleteTail(output) }
                        .onSuccess { recordSafeEvent("mouflon_mp4_finalization; complete fragments retained; incomplete/trailing bytes removed=$it") }
                        .onFailure { recordSafeEvent("mouflon_mp4_finalization_failed; error type=${it.javaClass.simpleName}") }
                }
                mouflonResolver = null
                processId = null
                pairedProcess = null
                if (captureStoppedAt == 0L) captureStoppedAt = SystemClock.elapsedRealtime()
                val elapsed = (captureStoppedAt - startedAt).coerceAtLeast(0L) / 1_000L
                savedPacketTiming = TransportStreamTiming.inspect(output)
                val savedSpan = savedPacketTiming?.video?.spanSeconds()
                val durationNote = if (savedSpan != null) "\nSaved video packet span: ${String.format(Locale.ROOT, "%.3f", savedSpan)}s (timestamp estimate; excludes final frame duration)." else ""
                val timing = if (mediaProgressSeen) {
                    "\nCapture timing: ${recordedMediaSeconds}s media over ${elapsed}s elapsed."
                } else "\nCapture timing: ${elapsed}s elapsed; native media progress unavailable."
                taskState.log = (taskState.log + timing + durationNote).takeLast(MAX_LOG_CHARS)
                if (audioUrl.isBlank()) recordSafeEvent("Final capture: elapsed=${elapsed}s; bytes=${output.length()}; native media progress=$mediaProgressSeen")
                detailedLogFile?.takeIf { it.isFile && it.length() > 0 }?.let { logFile ->
                    runCatching { exportDiagnosticLog(logFile) }
                        .onSuccess { taskState.log = (taskState.log +
                            "\n" + (if (audioUrl.isBlank()) "Recorder diagnostic log" else "Full HTTP event log") +
                            " saved to Downloads/yt-dlp Mobile/Recordings/${logFile.name}.")
                            .takeLast(MAX_LOG_CHARS) }
                        .onFailure { taskState.log = (taskState.log +
                            "\nCould not export HTTP event log: ${it.message}").takeLast(MAX_LOG_CHARS) }
                }
                detailedLogFile = null
                val source = RecordingMedia.choose(folder, stopWasRequested, allowFragmentedMp4 = mouflonCapture)
                when {
                    discardWhenStopped -> finish("Recording discarded", taskState.log)
                    saveWhenStopped && source != null -> {
                        val audioPresent = recordedAudioPresent(source.file)
                        val truncatedTs = stopWasRequested && source.extension == "ts" && source.file.length() % 188L != 0L
                        val tail = if (truncatedTs) runCatching { RecordingTsTail.plan(source.file) }.getOrNull() else null
                        if (truncatedTs) recordSafeEvent(if (tail != null)
                            "Interrupted TS export: omitting ${tail.sourceBytes - tail.exportBytes()} bytes from incomplete trailing media."
                        else "Interrupted TS has an unaligned tail; a conservative repair could not be verified.")
                        runCatching { export(source, tail) }
                            .onSuccess {
                                val partial = mediaProgressSeen && elapsed > 30L &&
                                    recordedMediaSeconds + 10L < elapsed / 2L
                                val status = if (partial) {
                                    "Partial recording saved: ${recordedMediaSeconds}s media / ${elapsed}s elapsed" +
                                        if (stallWasDetected) " (output stalled)" else ""
                                } else when (audioPresent) {
                                    false -> "Recording saved, but no audio track was detected"
                                    null -> "Recording saved; audio could not be verified"
                                    true -> "Recording saved to Downloads/yt-dlp Mobile/Recordings"
                                }
                                if (pairedRecording) {
                                    val report = File(folder, source.file.nameWithoutExtension + "-timing.json")
                                    runCatching {
                                        report.writeText(timingReport(source.file, elapsed).toString(2))
                                        exportDiagnosticLog(report)
                                    }.onSuccess {
                                        taskState.log = (taskState.log +
                                            "\nTiming report saved to Downloads/yt-dlp Mobile/Recordings/${report.name}" +
                                            " (video gaps=${timingSnapshot?.videoGaps?.size ?: 0}, " +
                                            "audio gaps=${timingSnapshot?.audioGaps?.size ?: 0}, " +
                                            "clock jumps=${(timingSnapshot?.videoClockJumps?.size ?: 0) + (timingSnapshot?.audioClockJumps?.size ?: 0)}).")
                                            .takeLast(MAX_LOG_CHARS)
                                    }.onFailure { error ->
                                        taskState.log = (taskState.log +
                                            "\nCould not save timing report: ${FfmpegDiagnostic.safeLine(error.message.orEmpty()) ?: error.javaClass.simpleName}")
                                            .takeLast(MAX_LOG_CHARS)
                                    }
                                }
                                val tailWarning = if (truncatedTs && tail == null) " • recording end may be incomplete" else ""
                                finish(status + (if (audioFinalizationBypassed) " • audio alignment incomplete" else "") + tailWarning,
                                    taskState.log + "\nSaved ${source.extension} recording; " +
                                    when (audioPresent) {
                                        true -> "audio track detected"
                                        false -> "no audio track detected"
                                        null -> "audio could not be verified"
                                    })
                            }
                            .onFailure { finish("Could not save recording: ${it.message}", taskState.log + "\n" + it.message) }
                    }
                    else -> {
                        val files = folder.walkTopDown().filter(File::isFile)
                            .joinToString { "${it.name} (${it.length()} bytes)" }
                        finish(recordingFailureStatus(taskState.log),
                            taskState.log + "\nRecorder files after stop: " + files)
                    }
                }
                if(discardWhenStopped || transferStatus.startsWith("Recording saved") || transferStatus.startsWith("Partial recording saved")) folder.deleteRecursively()
                else recordSafeEvent("Capture retained in private recovery storage; use Save recovered recording in Queue & history")
                storageWatch=null
                sessionDir = null
                recordingWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
                recordingWakeLock = null
                TransferQueue.complete(this,transferId,transferStatus,transferLog,lease=transferLease)
            }
        }
    }

    private fun verifyMouflonPublicSource(master: String, folder: File, pageAgent: String,
                                         pageCookie: String, pageHeaders: Map<String, String>) {
        val pluginRoot = File(folder, "extractor-compat")
        val plugin = File(pluginRoot, "mobile-public-state/yt_dlp_plugins/extractor/mobile_public_stream.py")
        plugin.parentFile?.mkdirs()
        assets.open("yt_dlp_plugins/extractor/mobile_public_stream.py").use { input ->
            plugin.outputStream().use { input.copyTo(it) }
        }
        val encoded = android.util.Base64.encodeToString(master.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        val page = taskState.recordingPageUrl
        val request = YoutubeDLRequest(page)
            .addOption("--plugin-dirs", pluginRoot.absolutePath)
            .addOption("--extractor-args", "stripchat:browser_master=$encoded;public_probe=true")
            .addOption("--skip-download").addOption("--no-check-formats")
            .addOption("--no-playlist").addOption("--socket-timeout", 10)
            .addOption("--retries", 2).addOption("--newline")
        if (pageAgent.isNotBlank()) request.addOption("--user-agent", pageAgent)
        pageHeaders.forEach { (name, value) -> request.addOption("--add-headers", "$name:$value") }
        if (pageCookie.isNotBlank()) {
            val jar = File(folder, "page-access-cookies.txt")
            jar.writeText(WebpageAccessDiagnostic.cookieFile(page, pageCookie))
            request.addOption("--cookies", jar.absolutePath)
        }
        recordSafeEvent("mouflon_public_source_probe_started; revision=2; page request context; media headers excluded; page cookies present=${pageCookie.isNotBlank()}; header names=" + pageHeaders.keys.joinToString(","))
        processId = "public-probe-${UUID.randomUUID()}"
        var confirmed = false
        try {
            YoutubeDL.execute(request, processId, true) { _, _, line ->
                if (line.contains("App compatibility:") || line.contains("Extracting URL:") ||
                    line.contains("Retrying") || line.trimStart().startsWith("ERROR:")) {
                    RecorderErrorDiagnostic.safeLine(line)?.let { recordSafeEvent("mouflon_probe: $it") }
                }
                if (line.contains("App compatibility: public source verified; returning identity probe only")) confirmed = true
            }
            check(confirmed) { "The public-stream adapter was not selected or did not verify this room. No recording was started." }
            recordSafeEvent("mouflon_public_source_probe_completed; public room and browser stream identity verified")
        } catch (error: Exception) {
            RecorderErrorDiagnostic.describe(error).forEach { detail ->
                recordSafeEvent("mouflon_public_source_probe_failed: $detail")
            }
            throw error
        } finally {
            processId = null
            File(folder, "page-access-cookies.txt").delete()
        }
    }

    private fun recordPairedStreams(video: String, audio: String, output: File, referer: String,
                                    userAgent: String, videoCookie: String, audioCookie: String,
                                    videoHeaders: Map<String, String>, audioHeaders: Map<String, String>,
                                    audioDelayMs: Int, forceSeparateInputs: Boolean = false,
                                    combinedInput: Boolean = false) {
        val packages = sequenceOf(filesDir, noBackupFilesDir).flatMap { root ->
            sequenceOf("arm64", "arm", "x86_64", "x86", "arm64-v8a", "armeabi-v7a")
                .map { arch -> File(root, "youtubedl-android/packages/$arch/usr/bin/ffmpeg") }
        }
        val binary = (packages + sequenceOf(
            File(noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(filesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(noBackupFilesDir, "youtubedl-android/ffmpeg/ffmpeg"),
            File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
        )).firstOrNull { it.isFile && it.canExecute() }
            ?: error("Bundled FFmpeg executable was not found; cannot pair audio with video.")
        if (!File(applicationInfo.nativeLibraryDir, "libc++_shared.so").isFile) {
            error("Shared C++ runtime is missing from the APK. Rebuild with the included native CMake target.")
        }
        // FFmpeg.init unpacks libraries into more than one package directory.
        // Include every compatible library directory, not just libavdevice's.
        val nativeDirectory = File(applicationInfo.nativeLibraryDir)
        val layout = NativeLibraryLayout.discover(binary, nativeDirectory, listOf(
            File(filesDir, "youtubedl-android/packages"),
            File(noBackupFilesDir, "youtubedl-android/packages")
        ))
        val libraryDirs = layout.directories.toMutableList()
        libraryDirs.add(nativeDirectory.absolutePath)
        if (binary.parentFile?.name == "bin") {
            libraryDirs.add(File(binary.parentFile!!.parentFile!!, "lib").absolutePath)
        }
        val aliases = File(codeCacheDir, "ffmpeg-library-aliases").also { it.mkdirs() }
        fun configure(builder: ProcessBuilder) {
            builder.redirectErrorStream(true)
            builder.environment()["LD_LIBRARY_PATH"] =
                (libraryDirs + builder.environment().getOrDefault("LD_LIBRARY_PATH", ""))
                    .filter(String::isNotBlank).distinct().joinToString(":")
            if (binary.parentFile?.name == "bin") {
                builder.environment()["PATH"] = binary.parentFile!!.absolutePath + ":" +
                    builder.environment().getOrDefault("PATH", "")
            }
        }
        // Check the entire transitive link chain before touching a live URL.
        // Some bundles omit symlinks such as libexpat.so.1 while retaining
        // libexpat.so.1.9.3; give the linker the expected filename in a cache.
        var linked = false
        for (attempt in 0 until 16) {
            val check = ProcessBuilder(binary.absolutePath, "-version").also(::configure).start()
            val result = check.inputStream.bufferedReader().use { it.readText().takeLast(8_192) }
            if (check.waitFor() == 0) {
                linked = true
                break
            }
            val missing = Regex("""library "([^"]+\.so(?:\.[0-9]+)*)" not found""")
                .find(result)?.groupValues?.get(1)
            val candidate = missing?.let(layout::versionedCandidate)
            if (missing == null || candidate == null || missing.contains('/') || missing == candidate.name) {
                val detail = result.lines().mapNotNull(FfmpegDiagnostic::safeLine).takeLast(3).joinToString("; ")
                error("FFmpeg startup check failed. ${detail.ifBlank { "Missing native dependency." }}")
            }
            val alias = File(aliases, missing)
            candidate.copyTo(alias, overwrite = true)
            libraryDirs.add(0, aliases.absolutePath)
        }
        // A final probe also covers the case where the alias limit was reached.
        if (!linked) {
            val finalCheck = ProcessBuilder(binary.absolutePath, "-version").also(::configure).start()
            val finalOutput = finalCheck.inputStream.bufferedReader().use { it.readText().takeLast(8_192) }
            if (finalCheck.waitFor() != 0) {
                val detail = finalOutput.lines().mapNotNull(FfmpegDiagnostic::safeLine).takeLast(3).joinToString("; ")
                error("FFmpeg native startup failed: $detail")
            }
        }
        val isHlsPair = video.substringBefore('?').endsWith(".m3u8", true) &&
            audio.substringBefore('?').endsWith(".m3u8", true)
        // Prefer verified app-side live segment fetching for any paired HLS
        // stream. Restricting it to one CDN let other sites fall back to two
        // independent FFmpeg inputs and lose their shared media timeline.
        val tryLiveHlsRelay = isHlsPair
        val sameRequest = videoCookie == audioCookie &&
            (videoHeaders == audioHeaders || videoHeaders.isEmpty() || audioHeaders.isEmpty())
        // Some low-latency CMAF streams replace their timestamp origin at a
        // discontinuity. Passing those timestamps through verbatim makes a
        // recording jump around when the playlist refreshes.
        val unstableLowLatencyHls = isHlsPair && listOf(video, audio).any { url ->
            val host = runCatching { java.net.URI(url).host.orEmpty().lowercase(Locale.US) }
                .getOrDefault("")
            (host == "mmcdn.com" || host.endsWith(".mmcdn.com")) &&
                url.substringBefore('?').contains("_llhls.m3u8", ignoreCase = true)
        }
        val useHlsMaster = !combinedInput && isHlsPair && sameRequest && audioDelayMs == 0 &&
            !forceSeparateInputs && !unstableLowLatencyHls
        // Verbose HLS events are reduced to counts below. Never retain signed URLs.
        val options = mutableListOf("-nostdin", "-hide_banner", "-loglevel", "verbose",
            "-nostats", "-stats_period", "3", "-progress", "pipe:1", "-y")
        if (!useHlsMaster && !tryLiveHlsRelay && !forceSeparateInputs) {
            // Ordinary separate streams can preserve a shared source clock.
            options.add("-copyts")
        } else if (tryLiveHlsRelay || forceSeparateInputs) {
            // -copyts disables FFmpeg's HLS discontinuity correction. Keep it
            // off for these streams, and repair jumps beyond two seconds.
            options.addAll(listOf("-dts_delta_threshold", "2"))
            taskState.log = (taskState.log +
                "\nUsing discontinuity correction for unstable live timestamps.")
                .takeLast(MAX_LOG_CHARS)
        }
        if (unstableLowLatencyHls) {
            taskState.log = (taskState.log +
                "\nUsing persistent and overlapping HTTP segment requests for low-latency HLS.")
                .takeLast(MAX_LOG_CHARS)
        }
        fun input(url: String, cookie: String, headers: Map<String, String>) {
            if (url.substringBefore('?').endsWith(".m3u8", true)) {
                if (unstableLowLatencyHls && !url.startsWith("http://127.0.0.1:")) {
                    // Short LL-HLS segments arrive frequently. Reconnecting for
                    // each one can cost enough time to fall behind the live edge.
                    // FFmpeg reconnects if the server moves a segment to a new host.
                    options.addAll(listOf("-http_persistent", "1", "-http_multiple", "1"))
                } else {
                    // Other HLS feeds have been seen switching CDN hosts; retain
                    // their existing per-request connection behavior.
                    options.addAll(listOf("-http_persistent", "0", "-http_multiple", "0"))
                }
            }
            if (userAgent.isNotBlank()) options.addAll(listOf("-user_agent", userAgent))
            if (referer.isNotBlank()) options.addAll(listOf("-referer", referer))
            val allHeaders = headers.filterKeys { it.lowercase() !in setOf("cookie", "referer", "user-agent") }
                .map { (key, value) -> "$key: $value\r\n" }.joinToString("") +
                if (cookie.isNotBlank()) "Cookie: $cookie\r\n" else ""
            if (allHeaders.isNotBlank()) options.addAll(listOf("-headers", allHeaders))
            options.addAll(listOf("-i", url))
        }
        // The live media playlists advance independently. Fetch their complete
        // segments and serve a shared master from loopback. Unsupported HLS
        // playlists retain the existing FFmpeg fallback.
        val relayLog = File(output.parentFile, output.nameWithoutExtension + "-http-log.txt")
        val relay = if (tryLiveHlsRelay) runCatching {
            if (combinedInput && mouflonResolver != null) LiveHlsRelay(video,
                LiveHlsRelay.Access(userAgent, referer, videoCookie, videoHeaders), relayLog, mouflonResolver!!)
            else if (combinedInput) LiveHlsRelay(video,
                LiveHlsRelay.Access(userAgent, referer, videoCookie, videoHeaders), relayLog)
            else LiveHlsRelay(video, audio,
                LiveHlsRelay.Access(userAgent, referer, videoCookie, videoHeaders),
                LiveHlsRelay.Access(userAgent, referer, audioCookie, audioHeaders), relayLog)
        }.onFailure { error ->
            if (relayLog.isFile && relayLog.length() > 0) detailedLogFile = relayLog
            val cause = error.message?.takeIf { it.startsWith("Live stream could not start:") }
                ?: "initial fetch failed"
            taskState.log = (taskState.log + "\nLive playlist relay unavailable: " +
                cause + (if (combinedInput) "; capture was not started." else "; using direct recorder."))
                .takeLast(MAX_LOG_CHARS)
        }.getOrNull() else null
        if (combinedInput && relay == null) error("Live HLS segment verification failed; check the saved diagnostic log. No finite-download fallback was started.")
        recordSafeEvent("Paired input selection: HLS pair=$isHlsPair; relay enabled=${relay != null}; shared input requested=$useHlsMaster; separate inputs forced=$forceSeparateInputs")
        if (relay != null) {
            liveRelay = relay
            while (true) {
                val pending = pendingSafeEvents.poll() ?: break
                relay.logDiagnosticEvent(pending)
            }
            val initialDiagnostic = detailedLogFile
            if (initialDiagnostic != null && initialDiagnostic != relayLog && initialDiagnostic.isFile) {
                if (mouflonResolver != null) initialDiagnostic.forEachLine { line ->
                    RecorderErrorDiagnostic.safeLine(line)?.let { relay.logDiagnosticEvent("preflight $it") }
                } else initialDiagnostic.readLines().filter { it.startsWith("+") }.takeLast(100)
                    .forEach { relay.logDiagnosticEvent("preflight " + it) }
            }
            detailedLogFile = relayLog
            taskState.log = (taskState.log +
                "\nFollowing live audio and video segments with verified HTTP responses.")
                .takeLast(MAX_LOG_CHARS)
        }
        val measuredOffset = (if (combinedInput) null else relay?.initialAudioOffsetSeconds())?.takeIf {
            kotlin.math.abs(it) <= 30.0
        }
        if (relay != null) {
            val alignment = if (measuredOffset != null) {
                "\nLive audio start offset from playlist time: " +
                    String.format(Locale.US, "%+.3f s", measuredOffset) + "."
            } else "\nLive playlist wall-clock time unavailable; preserving source packet timing."
            taskState.log = (taskState.log + alignment).takeLast(MAX_LOG_CHARS)
        }
        if (saveWhenStopped || discardWhenStopped) {
            relay?.close()
            return
        }
        // The relay preserves the original CMAF packet timestamps. Feed both
        // renditions through one HLS demuxer so FFmpeg cannot rebase the two
        // input clocks independently (which loses their actual separation).
        val useRelayMaster = !combinedInput && relay != null && audioDelayMs == 0 && !forceSeparateInputs
        val useSharedMaster = useHlsMaster || useRelayMaster
        val playlistServer = if (useSharedMaster) {
            PairedPlaylistServer(PairedHlsMaster.build(
                relay?.videoUrl() ?: video, relay?.audioUrl() ?: audio))
        } else null
        try {
            var firstMediaProgressUs = -1L
            if (combinedInput) {
                input(relay!!.videoUrl(), "", emptyMap())
                options.addAll(listOf("-map", "0:v:0", "-map", "0:a:0?"))
                recordSafeEvent("combined_hls_single_demuxer; original packet timing retained; no independent audio alignment")
                taskState.log += "\nFollowing combined live audio/video on one source timeline."
            } else if (useSharedMaster) {
                // A single HLS demuxer follows both renditions on one media timeline.
                // Serve the temporary master over HTTP so FFmpeg propagates cookies,
                // user agent and referer to both remote playlists and their segments.
                input(playlistServer!!.url,
                    if (useRelayMaster) "" else videoCookie,
                    if (useRelayMaster) emptyMap() else videoHeaders.ifEmpty { audioHeaders })
                options.addAll(listOf("-map", "0:v:0", "-map", "0:a:0"))
                taskState.log = (taskState.log +
                    "\nUsing a shared HLS input to preserve the source audio/video timeline.")
                    .takeLast(MAX_LOG_CHARS)
            } else {
                input(relay?.videoUrl() ?: video,
                    if (relay == null) videoCookie else "",
                    if (relay == null) videoHeaders else emptyMap())
                val audioOffset = (measuredOffset ?: 0.0) + audioDelayMs / 1000.0
                if (audioOffset != 0.0) {
                    // Preserve the difference in the source segment start times;
                    // FFmpeg otherwise rebases two HLS inputs independently.
                    // Positive moves audio later; negative moves it earlier.
                    options.addAll(listOf("-itsoffset", String.format(Locale.US, "%.3f", audioOffset)))
                }
                input(relay?.audioUrl() ?: audio,
                    if (relay == null) audioCookie else "",
                    if (relay == null) audioHeaders else emptyMap())
                options.addAll(listOf("-map", "0:v:0", "-map", "1:a:0"))
            }
            if (mouflonResolver != null) options.addAll(listOf("-c", "copy", "-movflags",
                "+frag_keyframe+empty_moov+default_base_moof", "-f", "mp4", output.absolutePath))
            else options.addAll(listOf("-c", "copy", "-f", "mpegts", output.absolutePath))
            val command = mutableListOf(binary.absolutePath).apply { addAll(options) }
            val builder = ProcessBuilder(command).also(::configure)
            val process = builder.start()
            pairedProcess = process
            outputStalledSince = 0L
            lastStallUserActionAt = 0L
            val trace = PairedCaptureTrace(SystemClock.elapsedRealtime())
            var lastBytes = output.length()
            var lastGrowthAt = SystemClock.elapsedRealtime()
            var lastMediaSeconds = recordedMediaSeconds
            var lastMediaAt = lastGrowthAt
            val statusRefresh = object : Runnable {
                override fun run() {
                    if (pairedProcess === process && process.isAlive) {
                        val now = SystemClock.elapsedRealtime()
                        val bytes = output.length()
                        trace.observeBytes(bytes, now)
                        if (bytes > lastBytes) {
                            lastBytes = bytes
                            lastGrowthAt = now
                        }
                        if (recordedMediaSeconds > lastMediaSeconds) {
                            lastMediaSeconds = recordedMediaSeconds
                            lastMediaAt = now
                        }
                        val noFileProgress = now - lastGrowthAt >=
                            (if (lastBytes == 0L) STARTUP_WARNING_MS else STALL_WARNING_MS)
                        val noMediaProgress = mediaProgressSeen && lastBytes > 0L &&
                            now - lastMediaAt >= STALL_WARNING_MS
                        val stalled = !stopWasRequested && (noFileProgress || noMediaProgress)
                        val relayEnded=relay?.sourceEnded()==true
                        if(!stopWasRequested && ((relayEnded && relay?.finalSegmentsServed()==true &&
                            now-maxOf(lastGrowthAt,lastMediaAt)>=10_000L) ||
                            (LiveEndEvidence.independentCompletionApplies(completionMonitor?.reason,relay!=null) && stalled))) {
                            confirmedCompletion=true
                            recordSafeEvent("stream_end_drain_finished; evidence="+(if(relayEnded) "all active HLS tracks ENDLIST; final segments served" else completionMonitor?.reason))
                            stopRecording(save=true);return
                        }
                        if (!stopWasRequested && (relay?.hasRecentHttp403() == true ||
                            relay?.waitingForMouflonMapping() == true) &&
                            now - lastGrowthAt >= 10_000L &&
                            (lastSessionRefreshRequestAt == 0L ||
                                now - lastSessionRefreshRequestAt >= 60_000L)) {
                            lastSessionRefreshRequestAt = now
                            recordSafeEvent("recorder_requests_browser_session_refresh")
                            if (relay?.waitingForMouflonMapping() == true) {
                                recordSafeEvent("mouflon_recovery_attempt; fresh browser detection requested; existing recording preserved")
                            }
                            sendBroadcast(Intent(ACTION_BROWSER_REFRESH_REQUEST).setPackage(packageName))
                        }
                        if (stalled && outputStalledSince == 0L) {
                            outputStalledSince = if (noFileProgress) lastGrowthAt else lastMediaAt
                            lastStallUserActionAt = now
                            stallWasDetected = true
                            recordSafeEvent("stall_five_minute_auto_save_timer_started")
                            if (relay?.hasRecentHttp403() == true &&
                                taskState.recordingPageUrl.isNotBlank()) {
                                taskState.recording403RecoveryAvailable = true
                                recordSafeEvent("manual_403_recovery_available")
                            }
                            val warning = when {
                                relay?.hasRecentHttp403() == true ->
                                    "Stream server returned 403. Automatic refresh continues. Reopen stalled stream or tap Stop & save. Without user activity, it will automatically stop and save in 5 minutes."
                                lastBytes == 0L -> "No recording data after 45 seconds. Check the stream or tap Stop & save. It will stop automatically after 5 minutes without user activity."
                                noFileProgress -> "Recording stalled: the file has not grown for 30 seconds. It will stop and save after 5 minutes without user activity."
                                else -> "Recording stalled: media time has not advanced for 30 seconds. It will stop and save after 5 minutes without user activity."
                            }
                            val stopped = when {
                                noFileProgress && noMediaProgress -> "file growth and media time"
                                noFileProgress -> "file growth"
                                else -> "media time"
                            }
                            val snapshot = "Stall detected: $stopped " +
                                "has stopped. Elapsed=${(now - startedAt) / 1_000L}s, " +
                                "media=${if (mediaProgressSeen) "${recordedMediaSeconds}s" else "unavailable"}, " +
                                "output=${humanBytes(bytes)}, " +
                                "last file growth=${(now - lastGrowthAt) / 1_000L}s ago, " +
                                "last media advance=${if (mediaProgressSeen) "${(now - lastMediaAt) / 1_000L}s ago" else "never"}.\n" +
                                recordingEnvironmentSnapshot(now) + "\n" + trace.summary(now) + "\n" +
                                (relay?.stallSnapshot() ?: "Live relay inactive; direct FFmpeg requests show attempts, not confirmed HTTP responses.") +
                                "\nThis snapshot records observations; it cannot prove why the browser or server stopped sending data."
                            taskState.log = (taskState.log + "\n" + warning + "\n" + snapshot)
                                .takeLast(MAX_LOG_CHARS)
                            relay?.logDiagnosticEvent(warning)
                            snapshot.lineSequence().forEach { line ->
                                relay?.logDiagnosticEvent(line)
                            }
                            showStallNotification(warning)
                        } else if (!stopWasRequested && stalled && relay?.hasRecentHttp403() == true &&
                            !taskState.recording403RecoveryAvailable &&
                            taskState.recordingPageUrl.isNotBlank()) {
                            taskState.recording403RecoveryAvailable = true
                            recordSafeEvent("manual_403_recovery_available_after_stall")
                            val warning = "Stream server returned 403. Automatic refresh continues. Reopen stalled stream or tap Stop & save. It will stop and save after 5 minutes without user activity."
                            taskState.log = (taskState.log + "\n" + warning).takeLast(MAX_LOG_CHARS)
                            showStallNotification(warning)
                        } else if (!stopWasRequested && !stalled && outputStalledSince != 0L) {
                            outputStalledSince = 0L
                            lastStallUserActionAt = 0L
                            if (taskState.recording403RecoveryAvailable) {
                                taskState.recording403RecoveryAvailable = false
                                recordSafeEvent("manual_403_recovery_cleared_after_resume")
                            }
                            getSystemService(NotificationManager::class.java).cancel(notificationId(STALL_NOTIFICATION_ID))
                            taskState.log = (taskState.log +
                                "\nRecording output resumed after a stall.").takeLast(MAX_LOG_CHARS)
                            relay?.logDiagnosticEvent("recording output resumed after stall; " +
                                "media=${recordedMediaSeconds}s, output=${humanBytes(bytes)}")
                        }
                        if (stalled && !stopWasRequested && lastStallUserActionAt != 0L &&
                            now - lastStallUserActionAt >= STALL_AUTO_SAVE_MS) {
                            val detail = "Stall stayed unresolved for 5 minutes without user activity. " +
                                "Stopping and saving automatically; captured media=${recordedMediaSeconds}s, " +
                                "output=${humanBytes(bytes)}."
                            recordSafeEvent("stall_timeout_auto_stop_and_save")
                            taskState.log = (taskState.log + "\n" + detail).takeLast(MAX_LOG_CHARS)
                            relay?.logDiagnosticEvent(detail)
                            stopRecording(save = true)
                            return
                        }
                        update(recordingStatus(output.parentFile!!))
                        android.os.Handler(mainLooper).postDelayed(this, 1_500)
                    }
                }
            }
            android.os.Handler(mainLooper).post(statusRefresh)
            // The verbose output can contain signed URLs, cookies and headers.
            // Only retain counts and fixed issue labels, never raw lines.
            var readFailure: IOException? = null
            var lastMouflonProgress = -5L
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                            val timeUs = NativeMedia.decimal(line.substringAfter('='))
                            if (timeUs != null && timeUs > 0L) {
                                if (firstMediaProgressUs < 0L) firstMediaProgressUs = timeUs
                                // FFmpeg's progress value is already the output
                                // duration unless -copyts preserves an absolute
                                // source clock. Subtracting the first update loses
                                // the media written before that update arrives.
                                val outputUs = if ("-copyts" in options) {
                                    (timeUs - firstMediaProgressUs).coerceAtLeast(0L)
                                } else timeUs
                                recordedMediaSeconds = outputUs / 1_000_000L
                                mediaProgressSeen = true
                                trace.observeMedia(timeUs, SystemClock.elapsedRealtime())
                                if (mouflonResolver != null && recordedMediaSeconds >= lastMouflonProgress + 5L) {
                                    lastMouflonProgress = recordedMediaSeconds
                                    relay?.logDiagnosticEvent("mouflon_media_progress; media=${recordedMediaSeconds}s; output bytes=${output.length()}")
                                }
                            }
                            update(recordingStatus(output.parentFile!!))
                        } else if (line.startsWith("frame=") || line.startsWith("size=")) {
                            update(recordingStatus(output.parentFile!!))
                        } else if (line.isNotBlank()) {
                            trace.observeLog(line)
                            if (mouflonResolver != null && listOf("error", "invalid", "failed", "unsupported")
                                    .any { line.contains(it, ignoreCase = true) }) {
                                RecorderErrorDiagnostic.safeLine(line)?.let {
                                    relay?.logDiagnosticEvent("mouflon_ffmpeg_diagnostic: $it")
                                }
                            }
                        }
                    }
                }
            } catch (error: IOException) {
                // Stop & save destroys FFmpeg while its output reader may still be
                // blocked. Preserve the trace even when that reader is closed.
                if (!stopWasRequested) readFailure = error
            } finally {
                trace.observeBytes(output.length(), SystemClock.elapsedRealtime())
                taskState.log = (taskState.log + "\n" +
                    trace.summary(SystemClock.elapsedRealtime())).takeLast(MAX_LOG_CHARS)
            }
            val exit = process.waitFor()
            if(relay?.sourceEnded()==true) {
                confirmedCompletion=true
                recordSafeEvent("stream_end_confirmed; all active HLS tracks ENDLIST; recorder finished draining")
            }
            relay?.stopFetching()
            captureStoppedAt = SystemClock.elapsedRealtime()
            capturedPacketTiming = TransportStreamTiming.inspect(output)
            capturedPacketTiming?.let { packets ->
                relay?.logDiagnosticEvent("captured packet bounds: video first=${packets.video.firstSeconds()} last=${packets.video.lastSeconds()}; audio first=${packets.audio.firstSeconds()} last=${packets.audio.lastSeconds()}; sampling bytes=${packets.bytesScanned}")
            }
            recordSafeEvent("FFmpeg exit=$exit; user stop=$stopWasRequested; media=${recordedMediaSeconds}s; bytes=${output.length()}")
            relay?.logDiagnosticEvent("recorder exit=$exit; stop requested=$stopWasRequested; " +
                "media=${recordedMediaSeconds}s, output=${humanBytes(output.length())}")
            if (relay != null && !combinedInput) {
                timingSnapshot = relay.audioTimeline()
                timingOutcome = when {
                    audioDelayMs != 0 -> "captured_with_manual_audio_offset"
                    forceSeparateInputs -> "captured_with_separate_inputs"
                    else -> "original_before_alignment"
                }
                timingFailureReason = ""
            }
            if (readFailure != null) throw readFailure
            if (!stopWasRequested && exit != 0) {
                taskState.log = (taskState.log +
                    "\nFFmpeg exited with code $exit.").takeLast(MAX_LOG_CHARS)
                if (useSharedMaster && !saveWhenStopped && !discardWhenStopped) {
                    output.delete()
                    recordedMediaSeconds = 0L
                    mediaProgressSeen = false
                    taskState.log = (taskState.log +
                        "\nShared HLS capture failed; retrying separate streams with timestamp correction.")
                        .takeLast(MAX_LOG_CHARS)
                    recordPairedStreams(video, audio, output, referer, userAgent, videoCookie,
                        audioCookie, videoHeaders, audioHeaders, audioDelayMs, forceSeparateInputs = true)
                    return
                }
                error("Paired stream recorder exited with code $exit.")
            }
            if (relay != null && !combinedInput && audioDelayMs == 0 && !forceSeparateInputs &&
                !discardWhenStopped && output.isFile && output.length() > 188L) {
                // The live recorder must finish writing before any audio is altered.
                // Use source playlist time for the initial offset and verified video
                // holes; never guess an offset from network arrival times.
                val timeline = relay.audioTimeline()
                timingSnapshot = timeline
                val packets = capturedPacketTiming
                val origin = packets?.originSeconds()
                val firstVideo = packets?.video?.firstSeconds()
                val lastVideo = packets?.video?.lastSeconds()?.let { it + (packets.video.estimatedFrameSeconds() ?: 0.050) }
                // The demuxer already puts the two tracks on the packet clock. Do not
                // apply the playlist start difference a second time. Bound padding
                // to the observed video end instead of padding indefinitely.
                val filter = if (origin != null && firstVideo != null && lastVideo != null &&
                    lastVideo > firstVideo && kotlin.math.abs(firstVideo - (packets.audio.firstSeconds() ?: firstVideo)) < 30 && packets.video.backwards == 0L && packets.audio.backwards == 0L)
                    timeline.filter(firstVideo - origin, lastVideo - origin) else null
                plannedAudioFilter = filter
                if (filter == null) {
                    audioFinalizationBypassed = true
                    timingOutcome = "original_timeline_unavailable"
                    timingFailureReason = "Source/packet timing was missing, discontinuous, or a source gap may have been compressed by capture timestamp correction."
                    relay.logDiagnosticEvent("audio timeline correction not safely determined from source and packet clocks; original capture retained")
                } else {
                    val gapCount = timeline.videoGaps.size
                    relay.logDiagnosticEvent("audio timeline finalization started; video gaps=$gapCount, " +
                        "source start offset=${(timeline.audioStartMs - timeline.videoStartMs)}ms")
                    taskState.log = (taskState.log +
                        "\nFinalizing audio against the source video timeline; $gapCount video gaps.")
                        .takeLast(MAX_LOG_CHARS)
                    val processed = File(output.parentFile, output.nameWithoutExtension + "-aligned.ts")
                    val finalizationFinished = AtomicBoolean(false)
                    try {
                        // Copy video bit for bit. Audio timestamps remain on the
                        // original clock: aresample fills absent audio with silence,
                        // and volume mutes spans with no recorded video.
                        val args = listOf(binary.absolutePath, "-nostdin", "-hide_banner", "-loglevel", "error",
                            "-progress", "pipe:1",
                            "-y", "-i", output.absolutePath, "-map", "0:v:0", "-map", "0:a:0",
                            "-c:v", "copy", "-af", filter, "-c:a", "aac", "-b:a", "128k",
                            "-f", "mpegts", processed.absolutePath)
                        val finalizer = ProcessBuilder(args).also(::configure).start()
                        activeFinalizer = finalizer
                        val finalizationStart = SystemClock.elapsedRealtime()
                        val lastActivity = AtomicLong(finalizationStart)
                        val lastOutputTimeUs = AtomicLong(-1L)
                        val interruptedReason = AtomicReference<String?>(null)
                        Thread({
                            var lastSize = 0L
                            while (!finalizationFinished.get() && finalizer.isAlive) {
                                try { Thread.sleep(1_000L) } catch (_: InterruptedException) { break }
                                val size = processed.length()
                                if (size > lastSize) {
                                    lastSize = size
                                    lastActivity.set(SystemClock.elapsedRealtime())
                                }
                                if (SystemClock.elapsedRealtime() - lastActivity.get() >= 90_000L) {
                                    interruptedReason.set("Audio alignment made no progress for 90 seconds.")
                                    relay.logDiagnosticEvent("audio finalization watchdog: no progress for 90s; stopping FFmpeg")
                                    finalizer.destroyForcibly()
                                    break
                                }
                            }
                        }, "recording-finalization-watchdog").apply { isDaemon = true; start() }
                        var lastPercent = 0
                        var lastUpdateAt = 0L
                        val result = StringBuilder()
                        savingProgress(1, "Aligning audio", null)
                        finalizer.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (result.length > 8_192) result.delete(0, result.length - 4_096)
                                result.append(line).append('\n')
                                if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                                    val timeUs = NativeMedia.decimal(line.substringAfter('='))
                                    if (timeUs != null && recordedMediaSeconds > 0L) {
                                        if (timeUs > lastOutputTimeUs.get()) {
                                            lastOutputTimeUs.set(timeUs)
                                            lastActivity.set(SystemClock.elapsedRealtime())
                                        }
                                        val percent = (timeUs / 1_000_000.0 / recordedMediaSeconds * 79)
                                            .toInt().coerceIn(1, 79)
                                        val now = SystemClock.elapsedRealtime()
                                        if (percent > lastPercent && now - lastUpdateAt >= 500L) {
                                            val elapsed = (now - finalizationStart) / 1_000L
                                            val remaining = if (percent >= 3 && elapsed >= 2L)
                                                (elapsed * (79 - percent) / percent).coerceAtLeast(0L) else null
                                            savingProgress(percent, "Aligning audio", remaining)
                                            lastPercent = percent
                                            lastUpdateAt = now
                                        }
                                    }
                                }
                            }
                        }
                        val status = finalizer.waitFor()
                        if (finalizationSkipRequested) error("Audio alignment skipped after another Stop & save request.")
                        interruptedReason.get()?.let { error(it) }
                        if (status != 0 || !processed.isFile || processed.length() < 188L) {
                            error("audio finalization failed (FFmpeg exit $status): " +
                                result.toString().lines().mapNotNull(FfmpegDiagnostic::safeLine).takeLast(2).joinToString("; "))
                        }
                        java.nio.file.Files.move(processed.toPath(), output.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        audioFinalizationCompleted = true
                        timingOutcome = "audio_processed_not_lipsync_verified"
                        relay.logDiagnosticEvent("audio timeline processing completed; video gaps muted=$gapCount; lip sync not independently verified")
                        taskState.log = (taskState.log + "\nAudio timing processed; source video gaps muted=$gapCount. Lip sync has not been independently verified.")
                            .takeLast(MAX_LOG_CHARS)
                    } catch (failure: Exception) {
                        audioFinalizationBypassed = true
                        timingOutcome = "original_after_alignment_failure"
                        timingFailureReason = FfmpegDiagnostic.safeLine(failure.message.orEmpty())
                            ?: failure.javaClass.simpleName
                        relay.logDiagnosticEvent("audio finalization failed; keeping original recording: " +
                            failure.javaClass.simpleName)
                        taskState.log = (taskState.log +
                            "\nAudio timing finalization failed; original recording kept. " +
                            failure.message.orEmpty().take(160)).takeLast(MAX_LOG_CHARS)
                    } finally {
                        finalizationFinished.set(true)
                        activeFinalizer?.let { if (it.isAlive) it.destroyForcibly() }
                        activeFinalizer = null
                        processed.delete()
                    }
                }
            }
        } finally {
            playlistServer?.close()
            if (liveRelay === relay) liveRelay = null
            relay?.close()
            if (relay != null) taskState.log = (taskState.log +
                "\n" + relay.report()).takeLast(MAX_LOG_CHARS)
        }
    }

    /** Observes output only; capture selection and normal download paths are unchanged. */
    private fun watchDirectCompletion(output: File) {
        fun mediaBytes()=output.parentFile?.listFiles().orEmpty().filter { it.isFile &&
            it.name.startsWith(output.nameWithoutExtension) && it.extension.lowercase() in setOf("ts","mp4","m4a","webm","mkv","aac","m4s","part") }.sumOf { it.length() }
        var lastBytes=mediaBytes();var lastGrowth=SystemClock.elapsedRealtime()
        val watched=completionMonitor
        val handler=android.os.Handler(mainLooper)
        handler.post(object: Runnable {
            override fun run() {
                if(!directCaptureActive || stopWasRequested || completionMonitor!==watched) return
                val now=SystemClock.elapsedRealtime();val bytes=mediaBytes()
                if(bytes>lastBytes) { lastBytes=bytes;lastGrowth=now }
                if(watched?.reason!=null && now-lastGrowth>=STALL_WARNING_MS) {
                    confirmedCompletion=true;recordSafeEvent("stream_end_drain_finished; "+watched.reason)
                    stopRecording(save=true);return
                }
                handler.postDelayed(this,1500)
            }
        })
    }

    private fun stopRecording(save: Boolean) {
        if (!taskState.running || taskState.operation != taskState.OPERATION_RECORD) return
        taskState.recording403RecoveryAvailable = false
        val alreadyStopping = stopWasRequested
        recordSafeEvent(if (save) "stop_and_save_requested" else "cancel_and_discard_requested")
        saveWhenStopped = save
        discardWhenStopped = !save
        stopWasRequested = true
        if (!alreadyStopping) sendBroadcast(Intent(ACTION_BROWSER_FINALIZING).setPackage(packageName))
        activeFinalizer?.let { finalizer ->
            if (!save || alreadyStopping) {
                if (!finalizationSkipRequested) recordSafeEvent("audio_finalization_skip_requested")
                finalizationSkipRequested = true
                update(if (save) "Skipping audio alignment; saving original recording…" else "Discarding recording…")
                finalizer.destroyForcibly()
                return
            }
        }
        update(if (save) "Stopping and finalizing recording…" else "Canceling recording…")
        processId?.let(YoutubeDL::destroyProcessById)
        pairedProcess?.let { process ->
            process.destroy()
            android.os.Handler(mainLooper).postDelayed({ if (process.isAlive) process.destroyForcibly() }, 3_000)
        }
    }

    private fun renewStream(intent: Intent) {
        if (!taskState.running || taskState.operation != taskState.OPERATION_RECORD ||
            stopWasRequested) return
        val relay = liveRelay ?: run {
            recordSafeEvent("renewal_unavailable_no_live_relay")
            return
        }
        val kind = intent.getStringExtra(EXTRA_RENEW_KIND).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!MediaUrlClassifier.isContinuousManifest(url) || !url.substringBefore('?').endsWith(".m3u8", true)) {
            recordSafeEvent("renewal_rejected_invalid_playlist")
            return
        }
        recordSafeEvent(if (kind == "audio") "renewal_attempt_audio" else "renewal_attempt_video")
        val access = LiveHlsRelay.Access(
            intent.getStringExtra(EXTRA_USER_AGENT).orEmpty(),
            intent.getStringExtra(EXTRA_REFERER).orEmpty(),
            intent.getStringExtra(EXTRA_COOKIES).orEmpty(),
            parseHeaders(intent.getStringExtra(EXTRA_HEADERS_JSON).orEmpty()))
        if (relay.renew(kind, url, access, intent.getBooleanExtra(EXTRA_RENEW_FORCE, false))) {
            taskState.log = (taskState.log +
                "\nBrowser supplied updated $kind stream credentials; checking for a successful new segment.")
                .takeLast(MAX_LOG_CHARS)
            sendState()
            recordSafeEvent(if (kind == "audio") "renewal_route_updated_audio" else "renewal_route_updated_video")
        } else {
            recordSafeEvent(if (kind == "audio") "renewal_not_applied_audio" else "renewal_not_applied_video")
        }
    }

    private fun recordBrowserEvent(intent: Intent) {
        if (!taskState.running || taskState.operation != taskState.OPERATION_RECORD) return
        val event = intent.getStringExtra(EXTRA_EVENT_CODE).orEmpty()
        if (event.length !in 3..64 || !event.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) return
        if (!stopWasRequested && outputStalledSince != 0L &&
            event in setOf("manual_403_browser_opened", "manual_403_stream_check_requested")) {
            lastStallUserActionAt = SystemClock.elapsedRealtime()
            recordSafeEvent("stall_timeout_reset_by_user")
        }
        val detail = intent.getStringExtra(EXTRA_EVENT_DETAIL).orEmpty().take(500)
        val safeDetail = detail.takeIf { it.all { c -> c.isLetterOrDigit() || c in " ,;:=._()+-" } }.orEmpty()
        recordSafeEvent(event + if (safeDetail.isNotBlank()) ": $safeDetail" else "")
    }

    private val directDiagnosticKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private fun recordDirectDiagnosticOnce(key: String, message: String) {
        if (directDiagnosticKeys.add(key)) recordSafeEvent(message)
    }

    private fun recordSafeEvent(event: String) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) / 1_000L
        taskState.log = (taskState.log + "\n+$elapsed s $event").takeLast(MAX_LOG_CHARS)
        val relay = liveRelay
        if (relay != null) relay.logDiagnosticEvent(event) else {
            val file = detailedLogFile
            if (file == null) pendingSafeEvents.add(event)
            else synchronized(directDiagnosticKeys) {
                runCatching { file.appendText("+$elapsed s $event\n") }
            }
        }
        sendState()
    }

    private fun recordingStatus(folder: File): String {
        if(!stopWasRequested && !discardWhenStopped) try { storageWatch?.poll() } catch(e: InsufficientStorage) {
            recordSafeEvent(StorageGuard.MESSAGE)
            stopRecording(save=true)
        }
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        if (outputStalledSince != 0L) {
            val stalledSeconds = (SystemClock.elapsedRealtime() - outputStalledSince) / 1000
            val remaining = ((STALL_AUTO_SAVE_MS -
                (SystemClock.elapsedRealtime() - lastStallUserActionAt)).coerceAtLeast(0L) + 999L) / 1000L
            return if (taskState.recording403RecoveryAvailable)
                "Recording stalled under 403 • ${stalledSeconds}s • auto-save in ${remaining}s without input • Reopen stream or Stop & save"
            else "Recording stalled • ${stalledSeconds}s • auto-save in ${remaining}s without input • Stop & save available"
        }
        val bytes = folder.walkTopDown().filter { it.isFile && it.extension != "txt" }
            .sumOf(File::length)
        val media = if (mediaProgressSeen) " • ${recordedMediaSeconds}s media" else ""
        return "Recording stream • ${seconds}s elapsed$media • ${humanBytes(bytes)}"
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MiB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun recordingEnvironmentSnapshot(now: Long): String {
        val power = getSystemService(PowerManager::class.java)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val network = getSystemService(ConnectivityManager::class.java)
        val active = network.getNetworkCapabilities(network.activeNetwork)
        val transports = if (active == null) "none" else buildList {
            if (active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (active.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }.joinToString("+").ifBlank { "other" }
        val browserAge = DownloadState.browserVisibilityChangedAt.takeIf { it > 0L }
            ?.let { "${(now - it).coerceAtLeast(0L) / 1_000L}s ago" } ?: "unknown"
        return "Device at stall: screen interactive=${power.isInteractive}, locked=$locked, " +
            "browser visible=${DownloadState.browserVisible}, resumed=${DownloadState.browserResumed} " +
            "(visibility changed $browserAge), wake lock held=${recordingWakeLock?.isHeld == true}, " +
            "network=$transports, validated=${active?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}."
    }

    private fun recordedAudioPresent(source: File): Boolean? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } finally { extractor.release() }
    }.getOrNull()

    private fun export(candidate: RecordingMedia.Candidate, tail: RecordingTsTail.Plan? = null): Uri {
        val source = candidate.file
        val extension = candidate.extension
        val displayName = source.name.substringBefore('.') + "." + extension
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, when (extension) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                else -> "video/mp2t"
            })
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/yt-dlp Mobile/Recordings")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values) ?: error("Android could not create the recording.")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                copyRecordingWithProgress(source, output, tail)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            TransferQueue.exported(this, transferId, uri,transferLease)
            return uri
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun exportDiagnosticLog(source: File) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/yt-dlp Mobile/Recordings")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values) ?: error("Android could not create the HTTP event log.")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { target ->
                FileInputStream(source).use { it.copyTo(target) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    /** Offline repair data for the saved file; never includes media URLs or credentials. */
    private fun timingReport(source: File, elapsedSeconds: Long): JSONObject {
        val timeline = timingSnapshot
        val videoStart = timeline?.videoStartMs
        val audioStart = timeline?.audioStartMs
        val videoEnd = timeline?.videoEndMs
        val audioEnd = timeline?.audioEndMs
        val firstStart = listOfNotNull(videoStart, audioStart).minOrNull()
        fun gaps(items: List<LiveHlsRelay.TimeGap>): JSONArray = JSONArray().apply {
            items.forEach { gap ->
                put(JSONObject().apply {
                    put("start_epoch_ms", gap.startMs)
                    put("end_epoch_ms", gap.endMs)
                    put("duration_ms", gap.endMs - gap.startMs)
                    put("start_on_shared_timeline_ms",
                        firstStart?.let { gap.startMs - it } ?: JSONObject.NULL)
                    put("end_on_shared_timeline_ms",
                        firstStart?.let { gap.endMs - it } ?: JSONObject.NULL)
                })
            }
        }
        return JSONObject().apply {
            put("schema_version", 2)
            fun packetReport(packets: TransportStreamTiming?): Any = packets?.let {
                JSONObject().apply {
                    put("video_first_pts_seconds", it.video.firstSeconds() ?: JSONObject.NULL)
                    put("video_last_pts_seconds", it.video.lastSeconds() ?: JSONObject.NULL)
                    put("audio_first_pts_seconds", it.audio.firstSeconds() ?: JSONObject.NULL)
                    put("audio_last_pts_seconds", it.audio.lastSeconds() ?: JSONObject.NULL)
                    put("video_packet_span_seconds", it.video.spanSeconds() ?: JSONObject.NULL)
                    put("audio_packet_span_seconds", it.audio.spanSeconds() ?: JSONObject.NULL)
                    put("estimated_video_frame_seconds", it.video.estimatedFrameSeconds() ?: JSONObject.NULL)
                    put("bytes_sampled", it.bytesScanned)
                    put("whole_file_scanned", it.complete)
                    put("backward_video_pts_over_1s", it.video.backwards)
                    put("backward_audio_pts_over_1s", it.audio.backwards)
                }
            } ?: JSONObject.NULL
            put("captured_packet_timing", packetReport(capturedPacketTiming))
            put("saved_packet_timing", packetReport(savedPacketTiming))
            put("lip_sync_verified", false)
            put("recording_file", source.name)
            put("result", timingOutcome)
            put("failure_reason", timingFailureReason)
            put("source_clock", "HLS EXT-X-PROGRAM-DATE-TIME from verified segments")
            put("video_start_epoch_ms", videoStart ?: JSONObject.NULL)
            put("audio_start_epoch_ms", audioStart ?: JSONObject.NULL)
            put("video_last_fetched_source_end_epoch_ms", videoEnd ?: JSONObject.NULL)
            put("audio_last_fetched_source_end_epoch_ms", audioEnd ?: JSONObject.NULL)
            put("video_fetched_source_span_ms",
                if (videoStart != null && videoEnd != null) videoEnd - videoStart else JSONObject.NULL)
            put("audio_fetched_source_span_ms",
                if (audioStart != null && audioEnd != null) audioEnd - audioStart else JSONObject.NULL)
            put("audio_start_minus_video_start_ms",
                if (videoStart != null && audioStart != null) audioStart - videoStart else JSONObject.NULL)
            put("manual_audio_delay_ms", manualAudioDelayMs)
            put("video_gaps", gaps(timeline?.videoGaps.orEmpty()))
            put("audio_gaps", gaps(timeline?.audioGaps.orEmpty()))
            put("video_source_clock_jumps", gaps(timeline?.videoClockJumps.orEmpty()))
            put("audio_source_clock_jumps", gaps(timeline?.audioClockJumps.orEmpty()))
            put("planned_audio_filter", plannedAudioFilter ?: JSONObject.NULL)
            put("capture_media_seconds", if (mediaProgressSeen) recordedMediaSeconds else JSONObject.NULL)
            put("capture_elapsed_seconds", elapsedSeconds)
            put("saved_file_bytes", source.length())
            put("notes", "Gaps are verified source clock holes of 0.3 to 30 seconds. Larger or backward jumps require review. Fetched source end times may extend beyond packets saved in the file; inspect the saved file for its actual end. This report does not measure lip sync from decoded frames.")
        }
    }

    private fun parseHeaders(json: String): Map<String, String> = runCatching {
        val value = JSONObject(json)
        buildMap { value.keys().forEach { key -> value.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) } } }
    }.getOrDefault(emptyMap())

    private fun copyRecordingWithProgress(source: File, target: java.io.OutputStream, tail: RecordingTsTail.Plan? = null) {
        if (tail != null) check(source.length() == tail.sourceBytes) { "Recording changed during finalization" }
        val total = (tail?.exportBytes() ?: source.length()).coerceAtLeast(1L)
        StorageGuard.destination(this,total)
        val storage=StorageGuard.Watch(this)
        val base = if (audioFinalizationCompleted || taskState.progress > 1) 80 else 1
        val span = 99 - base
        var copied = 0L
        var lastPercent = -1
        var lastUpdateAt = 0L
        val started = SystemClock.elapsedRealtime()
        savingProgress(base, "Saving recording", null)
        FileInputStream(source).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val remaining = tail?.let { it.prefixBytes - copied }
                if (remaining == 0L) break
                val count = input.read(buffer, 0, remaining?.coerceAtMost(buffer.size.toLong())?.toInt() ?: buffer.size)
                if (count < 0) {
                    check(tail == null) { "Recording ended while copying the verified prefix" }
                    break
                }
                storage.poll()
                target.write(buffer, 0, count)
                copied += count
                val percent = (base + (copied.toDouble() / total * span).toInt()).coerceIn(base, 99)
                val now = SystemClock.elapsedRealtime()
                if (percent > lastPercent && now - lastUpdateAt >= 500L) {
                    val elapsed = (now - started) / 1_000L
                    val remaining = if (copied >= total / 20 && elapsed >= 2L)
                        (elapsed.toDouble() * (total - copied).coerceAtLeast(0L) / copied)
                            .toLong().coerceAtLeast(0L) else null
                    savingProgress(percent, "Saving recording", remaining)
                    lastPercent = percent
                    lastUpdateAt = now
                }
            }
        }
        if (tail != null) {
            check(source.length() == tail.sourceBytes) { "Recording changed during finalization" }
            storage.poll()
            target.write(tail.suffix)
        }
        savingProgress(99, "Finishing save", null)
    }

    private fun savingProgress(percent: Int, stage: String, remainingSeconds: Long?) {
        taskState.progress = percent.coerceIn(1, 99)
        val eta = remainingSeconds?.let { seconds ->
            val minutes = seconds / 60
            if (minutes > 0) " • about ${minutes}m ${seconds % 60}s left"
            else " • about ${seconds}s left"
        }.orEmpty()
        update("$stage • ${taskState.progress}%$eta")
    }

    private fun update(status: String) {
        taskState.status = status
        sendState()
        updateTaskNotification(notificationId(NOTIFICATION_ID), notification(status))
    }

    private fun recordingFailureStatus(log: String): String {
        val detail = log.lowercase(Locale.US)
        val exitCode = detail.substringAfterLast("ffmpeg exited with code ", "")
            .takeWhile(Char::isDigit)
        return when {
            exitCode == "139" -> "Recorder crashed while opening the stream (FFmpeg code 139). See diagnostic log."
            "video http 403" in detail ->
                "Video stream request returned 403. Replay the video to refresh the browser session."
            "audio http 403" in detail ->
                "Audio stream request returned 403. Replay the video to refresh the browser session."
            "http 403" in detail ->
                "Stream request returned 403. Replay the video to refresh the browser session."
            "http 404" in detail || "http 410" in detail ->
                "Stream request returned 404 or 410. Replay the video and try recording again."
            exitCode == "8" ->
                "Recorder could not open this stream (FFmpeg code 8). See diagnostic log."
            "no media segments" in detail ->
                "Playlist loaded, but no complete media segments arrived. Replay the video and try again."
            exitCode.isNotEmpty() -> "Recorder exited with FFmpeg code $exitCode. See diagnostic log."
            stopWasRequested -> "Recording stopped before media could be captured."
            else -> "Recording failed: no usable media was captured. See diagnostic log."
        }
    }

    private fun showStallNotification(message: String) {
        val stop = PendingIntent.getService(this, notificationId(STALL_NOTIFICATION_ID),
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP_SAVE).putExtra(TransferQueue.JOB,taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, STALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Stream recording stalled")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(PendingIntent.getActivity(this, notificationId(STALL_NOTIFICATION_ID),
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .apply {
                if (taskState.recording403RecoveryAvailable) {
                    addAction(0, "Reopen stream", PendingIntent.getActivity(this@RecordingEngine,
                        notificationId(STALL_NOTIFICATION_ID) + 1, Intent(this@RecordingEngine, BrowserSessionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(BrowserSessionActivity.EXTRA_PAGE_URL, taskState.recordingPageUrl)
                            .putExtra(BrowserSessionActivity.EXTRA_MODE, BrowserSessionActivity.MODE_RECORD)
                            .putExtra(BrowserSessionActivity.EXTRA_RECOVERY, true),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            .addAction(0, "Stop & save", stop)
            .build()
        updateTaskNotification(notificationId(STALL_NOTIFICATION_ID), notice)
    }

    private fun finish(status: String, log: String) {
        transferStatus=status;transferLog=log
        TransferQueue.finalizing(this,transferId,status,transferLease)
        outputStalledSince = 0L
        lastStallUserActionAt = 0L
        taskState.recording403RecoveryAvailable = false
        taskState.recordingPageUrl = ""
        taskState.recordingVideoUrl = ""
        taskState.recordingAudioUrl = ""
        getSystemService(NotificationManager::class.java).cancel(notificationId(STALL_NOTIFICATION_ID))
        taskState.running = false
        taskState.operation = taskState.OPERATION_IDLE
        taskState.progress = if (status.startsWith("Recording saved") ||
            status.startsWith("Partial recording saved")) 100 else 0
        taskState.status = status
        taskState.log = log.takeLast(MAX_LOG_CHARS)
        sendState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendState() {
        TransferQueue.progress(this,transferId,taskState.status,taskState.log,transferLease,taskState.progress)
        sendBroadcast(Intent(DownloadService.ACTION_STATE).setPackage(packageName).apply {
            putExtra(DownloadService.EXTRA_RUNNING, taskState.running)
            putExtra(DownloadService.EXTRA_OPERATION, taskState.operation)
            putExtra(DownloadService.EXTRA_PROGRESS, taskState.progress)
            putExtra(DownloadService.EXTRA_STATUS, taskState.status)
            putExtra(DownloadService.EXTRA_LOG, taskState.log)
            putExtra(EXTRA_403_RECOVERY_AVAILABLE, taskState.recording403RecoveryAvailable)
        })
    }

    private fun notification(status: String, finished: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("yt-dlp Mobile stream recorder")
            .setContentText(status)
            .setProgress(100, taskState.progress.coerceIn(0, 100),
                !finished && taskState.progress == 0)
            .setOnlyAlertOnce(!finished)
            .setOngoing(!finished)
            .setContentIntent(PendingIntent.getActivity(this, notificationId(0), Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (!finished) addAction(0, "Stop & save", PendingIntent.getService(
                    this@RecordingEngine, notificationId(2), Intent(this@RecordingEngine, RecordingService::class.java).setAction(ACTION_STOP_SAVE).putExtra(TransferQueue.JOB,taskId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
            }.build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stream recordings", NotificationManager.IMPORTANCE_LOW)
            )
            notifications.createNotificationChannel(
                NotificationChannel(STALL_CHANNEL_ID, "Recording problems", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    companion object {
        const val ACTION_START = "com.linfranca.ytdlpmobile.RECORD_START"
        const val ACTION_BROWSER_START = "com.linfranca.ytdlpmobile.BROWSER_RECORD_START"
        const val ACTION_BROWSER_FINISH = "com.linfranca.ytdlpmobile.BROWSER_RECORD_FINISH"
        const val ACTION_BROWSER_STOP_REQUEST = "com.linfranca.ytdlpmobile.BROWSER_RECORD_STOP_REQUEST"
        const val ACTION_STOP_SAVE = "com.linfranca.ytdlpmobile.RECORD_STOP_SAVE"
        const val ACTION_CANCEL = "com.linfranca.ytdlpmobile.RECORD_CANCEL"
        const val ACTION_RENEW_STREAM = "com.linfranca.ytdlpmobile.RECORD_RENEW_STREAM"
        const val ACTION_BROWSER_EVENT = "com.linfranca.ytdlpmobile.RECORD_BROWSER_EVENT"
        const val ACTION_USER_INTERACTION = "com.linfranca.ytdlpmobile.RECORD_USER_INTERACTION"
        const val ACTION_BROWSER_REFRESH_REQUEST = "com.linfranca.ytdlpmobile.RECORD_BROWSER_REFRESH_REQUEST"
        const val ACTION_BROWSER_FINALIZING = "com.linfranca.ytdlpmobile.RECORD_BROWSER_FINALIZING"
        const val EXTRA_URL = "record_url"
        const val EXTRA_PAGE_URL = "record_page_url"
        const val EXTRA_PAGE_COOKIES = "record_page_cookies"
        const val EXTRA_PAGE_USER_AGENT = "record_page_user_agent"
        const val EXTRA_PAGE_HEADERS_JSON = "record_page_headers_json"
        const val EXTRA_PAGE_OBSERVATION = "record_page_observation"
        const val EXTRA_403_RECOVERY_AVAILABLE = "record_403_recovery_available"
        const val EXTRA_AUDIO_URL = "record_audio_url"
        const val EXTRA_AUDIO_DELAY_MS = "record_audio_delay_ms"
        const val EXTRA_REFERER = "record_referer"
        const val EXTRA_COOKIES = "record_cookies"
        const val EXTRA_AUDIO_COOKIES = "record_audio_cookies"
        const val EXTRA_USER_AGENT = "record_user_agent"
        const val EXTRA_HEADERS_JSON = "record_headers_json"
        const val EXTRA_AUDIO_HEADERS_JSON = "record_audio_headers_json"
        const val EXTRA_RENEW_KIND = "record_renew_kind"
        const val EXTRA_RENEW_FORCE = "renew_verified_retry"
        const val EXTRA_STREAM_DIAGNOSTICS = "stream_delivery_diagnostics"
        const val EXTRA_EVENT_DETAIL = "record_event_detail"
        const val EXTRA_EVENT_CODE = "record_event_code"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 85
        private const val STALL_CHANNEL_ID = "recording_stalled"
        private const val STALL_NOTIFICATION_ID = 86
        private const val STARTUP_WARNING_MS = 45_000L
        private const val STALL_WARNING_MS = 30_000L
        private const val STALL_AUTO_SAVE_MS = 5 * 60_000L
        private const val MAX_LOG_CHARS = 16_000
    }
}

/** Bounded, URL-free trace of FFmpeg's independently fetched live inputs. */
private class PairedCaptureTrace(private val startedMs: Long) {
    private val openings = Regex("Opening 'https?://([^'\\s]+)'", RegexOption.IGNORE_CASE)
    private val playlists = IntArray(3)
    private val segments = IntArray(3)
    private val problems = linkedMapOf<String, Int>()
    private var lastPlaylistMs = -1L
    private var lastSegmentMs = -1L
    private var lastMediaMs = -1L
    private var lastBytesMs = -1L
    private var lastMediaUs = -1L
    private var lastBytes = 0L

    @Synchronized fun observeLog(line: String) {
        val now = SystemClock.elapsedRealtime()
        openings.find(line)?.groupValues?.get(1)?.let { address ->
            // Classify by path only. Do not store the address or its query string.
            val path = address.substringBefore('?').lowercase(Locale.US)
            val track = when {
                "_audio_" in path || "/audio/" in path -> 1
                "_video_" in path || "/video/" in path -> 0
                else -> 2
            }
            when {
                path.endsWith(".m3u8") -> { playlists[track]++; lastPlaylistMs = now }
                path.endsWith(".m4s") || path.endsWith(".ts") || path.endsWith(".mp4") -> {
                    segments[track]++; lastSegmentMs = now
                }
            }
        }
        val lower = line.lowercase(Locale.US)
        val issue = when {
            "403" in lower && ("error" in lower || "forbidden" in lower) -> "HTTP 403"
            "404" in lower && ("error" in lower || "not found" in lower) -> "HTTP 404"
            "410" in lower && ("error" in lower || "gone" in lower) -> "HTTP 410"
            "429" in lower && ("error" in lower || "too many" in lower) -> "HTTP 429"
            "timed out" in lower || "timeout" in lower -> "request timeout"
            "name or service not known" in lower || "no address associated" in lower -> "DNS failure"
            "connection refused" in lower || "connection reset" in lower -> "connection interrupted"
            "failed" in lower && "keepalive" in lower -> "connection reuse failed"
            "error when loading first segment" in lower -> "first segment failed"
            "invalid data found" in lower -> "invalid media data"
            else -> null
        }
        if (issue != null) problems[issue] = (problems[issue] ?: 0) + 1
    }

    @Synchronized fun observeMedia(timeUs: Long, now: Long) {
        if (timeUs > lastMediaUs) { lastMediaUs = timeUs; lastMediaMs = now }
    }

    @Synchronized fun observeBytes(bytes: Long, now: Long) {
        if (bytes > lastBytes) { lastBytes = bytes; lastBytesMs = now }
    }

    @Synchronized fun summary(now: Long): String {
        fun counts(values: IntArray) = "video=${values[0]}, audio=${values[1]}, other=${values[2]}"
        fun age(value: Long) = if (value < 0L) "never" else "${(now - value) / 1_000L}s ago"
        return "Recorder fetch attempts (URL-free): playlists ${counts(playlists)}; " +
            "segments ${counts(segments)}.\n" +
            "Last playlist request ${age(lastPlaylistMs)}; last segment request ${age(lastSegmentMs)}; " +
            "last media progress ${age(lastMediaMs)}; last output growth ${age(lastBytesMs)}.\n" +
            "Request issues: ${problems.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "none observed" }}. " +
            "Trace duration ${(now - startedMs) / 1_000L}s."
    }
}

class RecordingService : MediaTaskService() {
    override fun createEngine(id: String): MediaTaskEngine = RecordingEngine(this,id)
    override fun isStart(action: String?) = action in setOf(ACTION_START,ACTION_BROWSER_START)
    companion object {
        const val ACTION_START = "com.linfranca.ytdlpmobile.RECORD_START"
        const val ACTION_BROWSER_START = "com.linfranca.ytdlpmobile.BROWSER_RECORD_START"
        const val ACTION_BROWSER_FINISH = "com.linfranca.ytdlpmobile.BROWSER_RECORD_FINISH"
        const val ACTION_BROWSER_STOP_REQUEST = "com.linfranca.ytdlpmobile.BROWSER_RECORD_STOP_REQUEST"
        const val ACTION_STOP_SAVE = "com.linfranca.ytdlpmobile.RECORD_STOP_SAVE"
        const val ACTION_CANCEL = "com.linfranca.ytdlpmobile.RECORD_CANCEL"
        const val ACTION_RENEW_STREAM = "com.linfranca.ytdlpmobile.RECORD_RENEW_STREAM"
        const val ACTION_BROWSER_EVENT = "com.linfranca.ytdlpmobile.RECORD_BROWSER_EVENT"
        const val ACTION_USER_INTERACTION = "com.linfranca.ytdlpmobile.RECORD_USER_INTERACTION"
        const val ACTION_BROWSER_REFRESH_REQUEST = "com.linfranca.ytdlpmobile.RECORD_BROWSER_REFRESH_REQUEST"
        const val ACTION_BROWSER_FINALIZING = "com.linfranca.ytdlpmobile.RECORD_BROWSER_FINALIZING"
        const val EXTRA_URL = "record_url"
        const val EXTRA_PAGE_URL = "record_page_url"
        const val EXTRA_PAGE_COOKIES = "record_page_cookies"
        const val EXTRA_PAGE_USER_AGENT = "record_page_user_agent"
        const val EXTRA_PAGE_HEADERS_JSON = "record_page_headers_json"
        const val EXTRA_PAGE_OBSERVATION = "record_page_observation"
        const val EXTRA_403_RECOVERY_AVAILABLE = "record_403_recovery_available"
        const val EXTRA_AUDIO_URL = "record_audio_url"
        const val EXTRA_AUDIO_DELAY_MS = "record_audio_delay_ms"
        const val EXTRA_REFERER = "record_referer"
        const val EXTRA_COOKIES = "record_cookies"
        const val EXTRA_AUDIO_COOKIES = "record_audio_cookies"
        const val EXTRA_USER_AGENT = "record_user_agent"
        const val EXTRA_HEADERS_JSON = "record_headers_json"
        const val EXTRA_AUDIO_HEADERS_JSON = "record_audio_headers_json"
        const val EXTRA_RENEW_KIND = "record_renew_kind"
        const val EXTRA_RENEW_FORCE = "renew_verified_retry"
        const val EXTRA_STREAM_DIAGNOSTICS = "stream_delivery_diagnostics"
        const val EXTRA_EVENT_DETAIL = "record_event_detail"
        const val EXTRA_EVENT_CODE = "record_event_code"

    }
}
