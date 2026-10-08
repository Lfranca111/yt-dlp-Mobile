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

class RecordingService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var processId: String? = null
    @Volatile private var pairedProcess: Process? = null
    @Volatile private var activeFinalizer: Process? = null
    @Volatile private var finalizationSkipRequested = false
    @Volatile private var liveRelay: LiveHlsRelay? = null
    private var lastSessionRefreshRequestAt = 0L
    private val pendingSafeEvents = ConcurrentLinkedQueue<String>()
    @Volatile private var saveWhenStopped = false
    @Volatile private var discardWhenStopped = false
    @Volatile private var stopWasRequested = false
    private var sessionDir: File? = null
    private var detailedLogFile: File? = null
    private var startedAt = 0L
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var audioFinalizationCompleted = false
    private var audioFinalizationBypassed = false
    private var pairedRecording = false
    private var timingSnapshot: LiveHlsRelay.AudioTimeline? = null
    private var timingOutcome = "original_no_timeline"
    private var timingFailureReason = ""
    private var manualAudioDelayMs = 0
    @Volatile private var recordedMediaSeconds = 0L
    @Volatile private var mediaProgressSeen = false
    @Volatile private var outputStalledSince = 0L
    @Volatile private var stallWasDetected = false
    private var lastStallUserActionAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(STALL_NOTIFICATION_ID)
        recordingWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        recordingWakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!DownloadState.running) startRecording(intent)
            ACTION_BROWSER_START -> startBrowserCapture()
            ACTION_BROWSER_FINISH -> finishBrowserCapture()
            ACTION_STOP_SAVE -> if (browserCapture) requestBrowserStop() else stopRecording(save = true)
            ACTION_CANCEL -> if (browserCapture) cancelBrowserCapture() else stopRecording(save = false)
            ACTION_RENEW_STREAM -> renewStream(intent)
            ACTION_BROWSER_EVENT -> recordBrowserEvent(intent)
            ACTION_USER_INTERACTION -> if (DownloadState.running && !stopWasRequested &&
                outputStalledSince != 0L) {
                lastStallUserActionAt = SystemClock.elapsedRealtime()
                recordSafeEvent("stall_timeout_reset_by_user")
            }
        }
        return START_NOT_STICKY
    }

    private var browserCapture = false

    private fun startBrowserCapture() {
        if (DownloadState.running) return
        browserCapture = true
        DownloadState.running = true
        DownloadState.operation = DownloadState.OPERATION_RECORD
        DownloadState.progress = 0
        recordedMediaSeconds = 0L
        mediaProgressSeen = false
        DownloadState.log = "Capturing the video element and its available audio track…"
        startForeground(NOTIFICATION_ID, notification("Recording browser video…"))
        update("Recording browser video • use Stop & save when finished")
    }

    private fun requestBrowserStop() {
        update("Finishing browser recording…")
        sendBroadcast(Intent(ACTION_BROWSER_STOP_REQUEST).setPackage(packageName))
    }

    private fun finishBrowserCapture() {
        if (!browserCapture) return
        val source = BrowserCaptureStore.take()
        executor.execute {
            try {
                check(source != null && source.length() >= 1024L) { "No media data was recorded." }
                exportBrowserWebm(source)
                finish("Recording saved to Downloads/yt-dlp Mobile/Recordings", DownloadState.log)
            } catch (error: Exception) {
                finish("Recording could not be saved: ${error.message}", DownloadState.log + "\n" + error.message)
            } finally {
                source?.delete()
                browserCapture = false
            }
        }
    }

    private fun cancelBrowserCapture() {
        BrowserCaptureStore.discard()
        browserCapture = false
        finish("Browser recording discarded", DownloadState.log)
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
        timingOutcome = "original_no_timeline"
        timingFailureReason = "Live playlist relay did not provide a source timeline."
        finalizationSkipRequested = false
        saveWhenStopped = false
        discardWhenStopped = false
        stopWasRequested = false
        DownloadState.recording403RecoveryAvailable = false
        recordedMediaSeconds = 0L
        mediaProgressSeen = false
        outputStalledSince = 0L
        stallWasDetected = false
        lastStallUserActionAt = 0L
        liveRelay = null
        lastSessionRefreshRequestAt = 0L
        pendingSafeEvents.clear()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL).orEmpty()
        DownloadState.recordingPageUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
        DownloadState.recordingVideoUrl = url
        DownloadState.recordingAudioUrl = audioUrl
        pairedRecording = audioUrl.isNotBlank()
        val audioDelayMs = intent.getIntExtra(EXTRA_AUDIO_DELAY_MS, 0).coerceIn(-10_000, 10_000)
        manualAudioDelayMs = audioDelayMs
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val cookies = intent.getStringExtra(EXTRA_COOKIES).orEmpty()
        val audioCookies = intent.getStringExtra(EXTRA_AUDIO_COOKIES).orEmpty()
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        val headers = parseHeaders(intent.getStringExtra(EXTRA_HEADERS_JSON).orEmpty())
        val audioHeaders = parseHeaders(intent.getStringExtra(EXTRA_AUDIO_HEADERS_JSON).orEmpty())

        DownloadState.running = true
        DownloadState.operation = DownloadState.OPERATION_RECORD
        DownloadState.progress = 0
        DownloadState.log = if (audioUrl.isBlank()) {
            "Starting direct stream recorder…"
        } else {
            "Starting paired video + audio stream recorder…\nChecking live stream timing." +
                if (audioDelayMs != 0) "\nManual audio adjustment: " +
                    String.format(Locale.US, "%+.2f s", audioDelayMs / 1000.0) else ""
        }
        startedAt = SystemClock.elapsedRealtime()
        detailedLogFile = null
        update("Recording stream • 0s • 0 B")
        startForeground(NOTIFICATION_ID, notification("Recording stream…"))
        // A foreground notification does not guarantee continuous CPU time
        // while the screen is off. Release this as soon as capture/export ends.
        recordingWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:stream-recording")
            .also { it.acquire(4L * 60L * 60L * 1_000L) }

        executor.execute {
            val folder = File(cacheDir, "recordings/${UUID.randomUUID()}").also {
                it.mkdirs()
                sessionDir = it
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            // MPEG-TS remains readable if the live FFmpeg process is stopped mid-stream.
            val output = File(folder, "recording-$stamp.ts")
            try {
                YoutubeDL.init(applicationContext)
                FFmpeg.init(applicationContext)
                if (saveWhenStopped || discardWhenStopped) error("Recording stopped before capture began.")
                if (audioUrl.isNotBlank()) {
                    // A single yt-dlp "best" HLS format may select just the video
                    // rendition. Give FFmpeg two inputs and map both tracks.
                    recordPairedStreams(url, audioUrl, output, referer, userAgent,
                        cookies, audioCookies.ifBlank { cookies }, headers, audioHeaders, audioDelayMs)
                } else {
                    processId = "record-${UUID.randomUUID()}"
                    val request = YoutubeDLRequest(url)
                        .addOption("--newline")
                        .addOption("--hls-use-mpegts")
                        .addOption("--no-part")
                        .addOption("--no-mtime")
                        .addOption("--retries", 10)
                        .addOption("--fragment-retries", 10)
                        .addOption("-o", output.absolutePath)
                    if (MediaUrlClassifier.isContinuousManifest(url)) request.addOption("--force-generic-extractor")
                    if (referer.isNotBlank()) request.addOption("--referer", referer)
                    if (userAgent.isNotBlank()) request.addOption("--user-agent", userAgent)
                    val usableCookies = cookies.ifBlank { audioCookies }
                    if (usableCookies.isNotBlank()) request.addOption("--add-headers", "Cookie:$usableCookies")
                    (audioHeaders + headers).filterKeys { it.lowercase() !in setOf("cookie", "referer", "user-agent") }
                        .forEach { (name, value) -> request.addOption("--add-headers", "$name:$value") }

                    if (saveWhenStopped || discardWhenStopped) error("Recording stopped before capture began.")
                    YoutubeDL.execute(request, processId, true) { _, _, line ->
                        if (line.isNotBlank()) DownloadState.log = (DownloadState.log + "\n" + line).takeLast(MAX_LOG_CHARS)
                        update(recordingStatus(folder))
                    }
                }
                saveWhenStopped = true // A finite stream ended normally.
            } catch (error: Exception) {
                if (!saveWhenStopped && !discardWhenStopped) {
                    DownloadState.log = (DownloadState.log + "\n" + error.message.orEmpty()).takeLast(MAX_LOG_CHARS)
                }
            } finally {
                processId = null
                pairedProcess = null
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
                val timing = if (mediaProgressSeen) {
                    "\nCapture timing: ${recordedMediaSeconds}s media over ${elapsed}s elapsed."
                } else "\nCapture timing: ${elapsed}s elapsed; native media progress unavailable."
                DownloadState.log = (DownloadState.log + timing).takeLast(MAX_LOG_CHARS)
                detailedLogFile?.takeIf { it.isFile && it.length() > 0 }?.let { logFile ->
                    runCatching { exportDiagnosticLog(logFile) }
                        .onSuccess { DownloadState.log = (DownloadState.log +
                            "\nFull HTTP event log saved to Downloads/yt-dlp Mobile/Recordings/${logFile.name}.")
                            .takeLast(MAX_LOG_CHARS) }
                        .onFailure { DownloadState.log = (DownloadState.log +
                            "\nCould not export HTTP event log: ${it.message}").takeLast(MAX_LOG_CHARS) }
                }
                detailedLogFile = null
                val source = RecordingMedia.choose(folder, stopWasRequested)
                when {
                    discardWhenStopped -> finish("Recording discarded", DownloadState.log)
                    saveWhenStopped && source != null -> {
                        val audioPresent = recordedAudioPresent(source.file)
                        runCatching { export(source) }
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
                                        DownloadState.log = (DownloadState.log +
                                            "\nTiming report saved to Downloads/yt-dlp Mobile/Recordings/${report.name}" +
                                            " (video gaps=${timingSnapshot?.videoGaps?.size ?: 0}, " +
                                            "audio gaps=${timingSnapshot?.audioGaps?.size ?: 0}, " +
                                            "clock jumps=${(timingSnapshot?.videoClockJumps?.size ?: 0) + (timingSnapshot?.audioClockJumps?.size ?: 0)}).")
                                            .takeLast(MAX_LOG_CHARS)
                                    }.onFailure { error ->
                                        DownloadState.log = (DownloadState.log +
                                            "\nCould not save timing report: ${FfmpegDiagnostic.safeLine(error.message.orEmpty()) ?: error.javaClass.simpleName}")
                                            .takeLast(MAX_LOG_CHARS)
                                    }
                                }
                                finish(status + if (audioFinalizationBypassed) " • audio alignment incomplete" else "",
                                    DownloadState.log + "\nSaved ${source.extension} recording; " +
                                    when (audioPresent) {
                                        true -> "audio track detected"
                                        false -> "no audio track detected"
                                        null -> "audio could not be verified"
                                    })
                            }
                            .onFailure { finish("Could not save recording: ${it.message}", DownloadState.log + "\n" + it.message) }
                    }
                    else -> {
                        val files = folder.walkTopDown().filter(File::isFile)
                            .joinToString { "${it.name} (${it.length()} bytes)" }
                        finish(recordingFailureStatus(DownloadState.log),
                            DownloadState.log + "\nRecorder files after stop: " + files)
                    }
                }
                folder.deleteRecursively()
                sessionDir = null
                recordingWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
                recordingWakeLock = null
            }
        }
    }

    private fun recordPairedStreams(video: String, audio: String, output: File, referer: String,
                                    userAgent: String, videoCookie: String, audioCookie: String,
                                    videoHeaders: Map<String, String>, audioHeaders: Map<String, String>,
                                    audioDelayMs: Int, forceSeparateInputs: Boolean = false) {
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
        val useHlsMaster = isHlsPair && sameRequest && audioDelayMs == 0 &&
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
            DownloadState.log = (DownloadState.log +
                "\nUsing discontinuity correction for unstable live timestamps.")
                .takeLast(MAX_LOG_CHARS)
        }
        if (unstableLowLatencyHls) {
            DownloadState.log = (DownloadState.log +
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
            LiveHlsRelay(video, audio,
                LiveHlsRelay.Access(userAgent, referer, videoCookie, videoHeaders),
                LiveHlsRelay.Access(userAgent, referer, audioCookie, audioHeaders), relayLog)
        }.onFailure { error ->
            if (relayLog.isFile && relayLog.length() > 0) detailedLogFile = relayLog
            val cause = error.message?.takeIf { it.startsWith("Live stream could not start:") }
                ?: "initial fetch failed"
            DownloadState.log = (DownloadState.log + "\nLive playlist relay unavailable: " +
                cause + "; using direct recorder.")
                .takeLast(MAX_LOG_CHARS)
        }.getOrNull() else null
        if (relay != null) {
            liveRelay = relay
            while (true) {
                val pending = pendingSafeEvents.poll() ?: break
                relay.logDiagnosticEvent(pending)
            }
            detailedLogFile = relayLog
            DownloadState.log = (DownloadState.log +
                "\nFollowing live audio and video segments with verified HTTP responses.")
                .takeLast(MAX_LOG_CHARS)
        }
        val measuredOffset = relay?.initialAudioOffsetSeconds()?.takeIf {
            kotlin.math.abs(it) <= 30.0
        }
        if (relay != null) {
            val alignment = if (measuredOffset != null) {
                "\nLive audio start offset from playlist time: " +
                    String.format(Locale.US, "%+.3f s", measuredOffset) + "."
            } else "\nLive playlist wall-clock time unavailable; preserving source packet timing."
            DownloadState.log = (DownloadState.log + alignment).takeLast(MAX_LOG_CHARS)
        }
        if (saveWhenStopped || discardWhenStopped) {
            relay?.close()
            return
        }
        // The relay preserves the original CMAF packet timestamps. Feed both
        // renditions through one HLS demuxer so FFmpeg cannot rebase the two
        // input clocks independently (which loses their actual separation).
        val useRelayMaster = relay != null && audioDelayMs == 0 && !forceSeparateInputs
        val useSharedMaster = useHlsMaster || useRelayMaster
        val playlistServer = if (useSharedMaster) {
            PairedPlaylistServer(PairedHlsMaster.build(
                relay?.videoUrl() ?: video, relay?.audioUrl() ?: audio))
        } else null
        try {
            var firstMediaProgressUs = -1L
            if (useSharedMaster) {
                // A single HLS demuxer follows both renditions on one media timeline.
                // Serve the temporary master over HTTP so FFmpeg propagates cookies,
                // user agent and referer to both remote playlists and their segments.
                input(playlistServer!!.url,
                    if (useRelayMaster) "" else videoCookie,
                    if (useRelayMaster) emptyMap() else videoHeaders.ifEmpty { audioHeaders })
                options.addAll(listOf("-map", "0:v:0", "-map", "0:a:0"))
                DownloadState.log = (DownloadState.log +
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
            options.addAll(listOf("-c", "copy", "-f", "mpegts", output.absolutePath))
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
                        if (!stopWasRequested && relay?.hasRecentHttp403() == true &&
                            now - lastGrowthAt >= 10_000L &&
                            (lastSessionRefreshRequestAt == 0L ||
                                now - lastSessionRefreshRequestAt >= 60_000L)) {
                            lastSessionRefreshRequestAt = now
                            recordSafeEvent("recorder_requests_browser_session_refresh")
                            sendBroadcast(Intent(ACTION_BROWSER_REFRESH_REQUEST).setPackage(packageName))
                        }
                        if (stalled && outputStalledSince == 0L) {
                            outputStalledSince = if (noFileProgress) lastGrowthAt else lastMediaAt
                            lastStallUserActionAt = now
                            stallWasDetected = true
                            recordSafeEvent("stall_five_minute_auto_save_timer_started")
                            if (relay?.hasRecentHttp403() == true &&
                                DownloadState.recordingPageUrl.isNotBlank()) {
                                DownloadState.recording403RecoveryAvailable = true
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
                            DownloadState.log = (DownloadState.log + "\n" + warning + "\n" + snapshot)
                                .takeLast(MAX_LOG_CHARS)
                            relay?.logDiagnosticEvent(warning)
                            snapshot.lineSequence().forEach { line ->
                                relay?.logDiagnosticEvent(line)
                            }
                            showStallNotification(warning)
                        } else if (!stopWasRequested && stalled && relay?.hasRecentHttp403() == true &&
                            !DownloadState.recording403RecoveryAvailable &&
                            DownloadState.recordingPageUrl.isNotBlank()) {
                            DownloadState.recording403RecoveryAvailable = true
                            recordSafeEvent("manual_403_recovery_available_after_stall")
                            val warning = "Stream server returned 403. Automatic refresh continues. Reopen stalled stream or tap Stop & save. It will stop and save after 5 minutes without user activity."
                            DownloadState.log = (DownloadState.log + "\n" + warning).takeLast(MAX_LOG_CHARS)
                            showStallNotification(warning)
                        } else if (!stopWasRequested && !stalled && outputStalledSince != 0L) {
                            outputStalledSince = 0L
                            lastStallUserActionAt = 0L
                            if (DownloadState.recording403RecoveryAvailable) {
                                DownloadState.recording403RecoveryAvailable = false
                                recordSafeEvent("manual_403_recovery_cleared_after_resume")
                            }
                            getSystemService(NotificationManager::class.java).cancel(STALL_NOTIFICATION_ID)
                            DownloadState.log = (DownloadState.log +
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
                            DownloadState.log = (DownloadState.log + "\n" + detail).takeLast(MAX_LOG_CHARS)
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
                            }
                            update(recordingStatus(output.parentFile!!))
                        } else if (line.startsWith("frame=") || line.startsWith("size=")) {
                            update(recordingStatus(output.parentFile!!))
                        } else if (line.isNotBlank()) {
                            trace.observeLog(line)
                        }
                    }
                }
            } catch (error: IOException) {
                // Stop & save destroys FFmpeg while its output reader may still be
                // blocked. Preserve the trace even when that reader is closed.
                if (!stopWasRequested) readFailure = error
            } finally {
                trace.observeBytes(output.length(), SystemClock.elapsedRealtime())
                DownloadState.log = (DownloadState.log + "\n" +
                    trace.summary(SystemClock.elapsedRealtime())).takeLast(MAX_LOG_CHARS)
            }
            val exit = process.waitFor()
            relay?.logDiagnosticEvent("recorder exit=$exit; stop requested=$stopWasRequested; " +
                "media=${recordedMediaSeconds}s, output=${humanBytes(output.length())}")
            if (relay != null) {
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
                DownloadState.log = (DownloadState.log +
                    "\nFFmpeg exited with code $exit.").takeLast(MAX_LOG_CHARS)
                if (useSharedMaster && !saveWhenStopped && !discardWhenStopped) {
                    output.delete()
                    recordedMediaSeconds = 0L
                    mediaProgressSeen = false
                    DownloadState.log = (DownloadState.log +
                        "\nShared HLS capture failed; retrying separate streams with timestamp correction.")
                        .takeLast(MAX_LOG_CHARS)
                    recordPairedStreams(video, audio, output, referer, userAgent, videoCookie,
                        audioCookie, videoHeaders, audioHeaders, audioDelayMs, forceSeparateInputs = true)
                    return
                }
                error("Paired stream recorder exited with code $exit.")
            }
            if (relay != null && audioDelayMs == 0 && !forceSeparateInputs &&
                !discardWhenStopped && output.isFile && output.length() > 188L) {
                // The live recorder must finish writing before any audio is altered.
                // Use source playlist time for the initial offset and verified video
                // holes; never guess an offset from network arrival times.
                val timeline = relay.audioTimeline()
                timingSnapshot = timeline
                val filter = timeline.filter()
                if (filter == null) {
                    audioFinalizationBypassed = true
                    timingOutcome = "original_timeline_unavailable"
                    timingFailureReason = "Source playlist time was unavailable for one or both tracks."
                    relay.logDiagnosticEvent("audio timeline unavailable: source playlist wall-clock missing; original capture retained")
                } else {
                    val gapCount = timeline.videoGaps.size
                    relay.logDiagnosticEvent("audio timeline finalization started; video gaps=$gapCount, " +
                        "source start offset=${(timeline.audioStartMs - timeline.videoStartMs)}ms")
                    DownloadState.log = (DownloadState.log +
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
                            "-shortest", "-f", "mpegts", processed.absolutePath)
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
                        timingOutcome = "aligned"
                        relay.logDiagnosticEvent("audio timeline finalization completed; video gaps muted=$gapCount")
                        DownloadState.log = (DownloadState.log + "\nAudio timing finalized; source video gaps muted=$gapCount.")
                            .takeLast(MAX_LOG_CHARS)
                    } catch (failure: Exception) {
                        audioFinalizationBypassed = true
                        timingOutcome = "original_after_alignment_failure"
                        timingFailureReason = FfmpegDiagnostic.safeLine(failure.message.orEmpty())
                            ?: failure.javaClass.simpleName
                        relay.logDiagnosticEvent("audio finalization failed; keeping original recording: " +
                            failure.javaClass.simpleName)
                        DownloadState.log = (DownloadState.log +
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
            if (relay != null) DownloadState.log = (DownloadState.log +
                "\n" + relay.report()).takeLast(MAX_LOG_CHARS)
        }
    }

    private fun stopRecording(save: Boolean) {
        if (!DownloadState.running || DownloadState.operation != DownloadState.OPERATION_RECORD) return
        DownloadState.recording403RecoveryAvailable = false
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
        if (!DownloadState.running || DownloadState.operation != DownloadState.OPERATION_RECORD ||
            stopWasRequested) return
        val relay = liveRelay ?: return
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
        if (relay.renew(kind, url, access)) {
            DownloadState.log = (DownloadState.log +
                "\nBrowser supplied updated $kind stream credentials; checking for a successful new segment.")
                .takeLast(MAX_LOG_CHARS)
            sendState()
            recordSafeEvent(if (kind == "audio") "renewal_route_updated_audio" else "renewal_route_updated_video")
        } else {
            recordSafeEvent(if (kind == "audio") "renewal_not_applied_audio" else "renewal_not_applied_video")
        }
    }

    private fun recordBrowserEvent(intent: Intent) {
        if (!DownloadState.running || DownloadState.operation != DownloadState.OPERATION_RECORD) return
        val event = intent.getStringExtra(EXTRA_EVENT_CODE).orEmpty()
        if (event.length !in 3..64 || !event.all { it in 'a'..'z' || it == '_' }) return
        if (!stopWasRequested && outputStalledSince != 0L &&
            event in setOf("manual_403_browser_opened", "manual_403_stream_check_requested")) {
            lastStallUserActionAt = SystemClock.elapsedRealtime()
            recordSafeEvent("stall_timeout_reset_by_user")
        }
        recordSafeEvent(event)
    }

    private fun recordSafeEvent(event: String) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) / 1_000L
        DownloadState.log = (DownloadState.log + "\n+$elapsed s $event").takeLast(MAX_LOG_CHARS)
        val relay = liveRelay
        if (relay != null) relay.logDiagnosticEvent(event) else pendingSafeEvents.add(event)
        sendState()
    }

    private fun recordingStatus(folder: File): String {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        if (outputStalledSince != 0L) {
            val stalledSeconds = (SystemClock.elapsedRealtime() - outputStalledSince) / 1000
            val remaining = ((STALL_AUTO_SAVE_MS -
                (SystemClock.elapsedRealtime() - lastStallUserActionAt)).coerceAtLeast(0L) + 999L) / 1000L
            return if (DownloadState.recording403RecoveryAvailable)
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

    private fun export(candidate: RecordingMedia.Candidate): Uri {
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
                copyRecordingWithProgress(source, output)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
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
            put("schema_version", 1)
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
            put("planned_audio_filter", timeline?.filter() ?: JSONObject.NULL)
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

    private fun copyRecordingWithProgress(source: File, target: java.io.OutputStream) {
        val total = source.length().coerceAtLeast(1L)
        val base = if (audioFinalizationCompleted || DownloadState.progress > 1) 80 else 1
        val span = 99 - base
        var copied = 0L
        var lastPercent = -1
        var lastUpdateAt = 0L
        val started = SystemClock.elapsedRealtime()
        savingProgress(base, "Saving recording", null)
        FileInputStream(source).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
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
        savingProgress(99, "Finishing save", null)
    }

    private fun savingProgress(percent: Int, stage: String, remainingSeconds: Long?) {
        DownloadState.progress = percent.coerceIn(1, 99)
        val eta = remainingSeconds?.let { seconds ->
            val minutes = seconds / 60
            if (minutes > 0) " • about ${minutes}m ${seconds % 60}s left"
            else " • about ${seconds}s left"
        }.orEmpty()
        update("$stage • ${DownloadState.progress}%$eta")
    }

    private fun update(status: String) {
        DownloadState.status = status
        sendState()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
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
        val stop = PendingIntent.getService(this, STALL_NOTIFICATION_ID,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP_SAVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, STALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Stream recording stalled")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(PendingIntent.getActivity(this, STALL_NOTIFICATION_ID,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .apply {
                if (DownloadState.recording403RecoveryAvailable) {
                    addAction(0, "Reopen stream", PendingIntent.getActivity(this@RecordingService,
                        STALL_NOTIFICATION_ID + 1, Intent(this@RecordingService, BrowserSessionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(BrowserSessionActivity.EXTRA_PAGE_URL, DownloadState.recordingPageUrl)
                            .putExtra(BrowserSessionActivity.EXTRA_MODE, BrowserSessionActivity.MODE_RECORD)
                            .putExtra(BrowserSessionActivity.EXTRA_RECOVERY, true),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            .addAction(0, "Stop & save", stop)
            .build()
        getSystemService(NotificationManager::class.java).notify(STALL_NOTIFICATION_ID, notice)
    }

    private fun finish(status: String, log: String) {
        outputStalledSince = 0L
        lastStallUserActionAt = 0L
        DownloadState.recording403RecoveryAvailable = false
        DownloadState.recordingPageUrl = ""
        DownloadState.recordingVideoUrl = ""
        DownloadState.recordingAudioUrl = ""
        getSystemService(NotificationManager::class.java).cancel(STALL_NOTIFICATION_ID)
        DownloadState.running = false
        DownloadState.operation = DownloadState.OPERATION_IDLE
        DownloadState.progress = if (status.startsWith("Recording saved") ||
            status.startsWith("Partial recording saved")) 100 else 0
        DownloadState.status = status
        DownloadState.log = log.takeLast(MAX_LOG_CHARS)
        sendState()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status, true))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun sendState() {
        sendBroadcast(Intent(DownloadService.ACTION_STATE).setPackage(packageName).apply {
            putExtra(DownloadService.EXTRA_RUNNING, DownloadState.running)
            putExtra(DownloadService.EXTRA_OPERATION, DownloadState.operation)
            putExtra(DownloadService.EXTRA_PROGRESS, DownloadState.progress)
            putExtra(DownloadService.EXTRA_STATUS, DownloadState.status)
            putExtra(DownloadService.EXTRA_LOG, DownloadState.log)
            putExtra(EXTRA_403_RECOVERY_AVAILABLE, DownloadState.recording403RecoveryAvailable)
        })
    }

    private fun notification(status: String, finished: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("yt-dlp Mobile stream recorder")
            .setContentText(status)
            .setProgress(100, DownloadState.progress.coerceIn(0, 100),
                !finished && DownloadState.progress == 0)
            .setOnlyAlertOnce(!finished)
            .setOngoing(!finished)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (!finished) addAction(0, "Stop & save", PendingIntent.getService(
                    this@RecordingService, 2, Intent(this@RecordingService, RecordingService::class.java).setAction(ACTION_STOP_SAVE),
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
