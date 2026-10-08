package com.linfranca.ytdlpmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var processId: String? = null
    private val networkChanged = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> cancelDownload()
            ACTION_DOWNLOAD -> if (!DownloadState.running) startDownload(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startDownload(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val preset = intent.getStringExtra(EXTRA_PRESET).orEmpty()
        val customArgs = intent.getStringArrayListExtra(EXTRA_ARGS).orEmpty()
        val playlist = intent.getBooleanExtra(EXTRA_PLAYLIST, false)
        val speedBoost = intent.getBooleanExtra(EXTRA_SPEED_BOOST, false)
        val subtitles = intent.getBooleanExtra(EXTRA_SUBTITLES, false)
        val autoSubtitles = intent.getBooleanExtra(EXTRA_AUTO_SUBTITLES, false)
        val subtitleLanguages = intent.getStringExtra(EXTRA_SUBTITLE_LANGUAGES).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val cookies = intent.getStringExtra(EXTRA_COOKIES).orEmpty()
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        val capturedHeaders = parseCapturedHeaders(intent.getStringExtra(EXTRA_HEADERS_JSON).orEmpty())
        val browserCaptured = intent.getBooleanExtra(EXTRA_BROWSER_CAPTURED, false)
        val cookieFile = intent.getStringExtra(EXTRA_COOKIE_FILE).orEmpty()

        DownloadState.running = true
        DownloadState.operation = DownloadState.OPERATION_DOWNLOAD
        DownloadState.progress = 0
        DownloadState.log = "Starting yt-dlp…"
        updateState("Preparing downloader…", 0, DownloadState.log)
        startForeground(NOTIFICATION_ID, buildNotification("Preparing downloader…", 0))
        watchNetwork()

        executor.execute {
            val sessionDir = File(cacheDir, "downloads/${UUID.randomUUID()}")
            try {
                sessionDir.mkdirs()
                YoutubeDL.init(applicationContext)
                FFmpeg.init(applicationContext)

                var responseOutput = ""
                var attempt = 0
                var useParallel = speedBoost
                while (true) {
                    try {
                        processId = "download-${UUID.randomUUID()}"
                        val request = createRequest(
                            url, preset, customArgs, playlist, useParallel, subtitles,
                            autoSubtitles, subtitleLanguages, sessionDir,
                            referer, cookies, userAgent, capturedHeaders, browserCaptured, cookieFile
                        )
                        val etaEstimator = CumulativeEtaEstimator()
                        val response = YoutubeDL.execute(request, processId, true) { progress, _, line ->
                            val safeProgress = progress.coerceIn(0f, 100f).toInt()
                            val stableEta = etaEstimator.update(progress, SystemClock.elapsedRealtime())
                            val etaText = stableEta?.let { " • ETA ${formatEta(it)}" } ?: " • Calculating ETA…"
                            appendLog(replaceInstantEta(line, stableEta))
                            updateState("Downloading $safeProgress%$etaText", safeProgress, DownloadState.log)
                        }
                        responseOutput = response.out
                        break
                    } catch (error: Exception) {
                        val details = DownloadState.log + "\n" + error.message.orEmpty()
                        val parallelFailure = useParallel && (
                            details.contains("403") || details.contains("429") ||
                                details.contains("fragment", true)
                            )
                        val changedNetwork = networkChanged.get() && !browserCaptured
                        if (attempt == 0 && DownloadState.running && (parallelFailure || changedNetwork)) {
                            attempt++
                            useParallel = false
                            networkChanged.set(false)
                            val reason = if (changedNetwork) {
                                "Network changed — waiting briefly, then refreshing the media link…"
                            } else {
                                "Server rejected parallel fragments — retrying once with one connection…"
                            }
                            appendLog(reason)
                            updateState(reason, DownloadState.progress, DownloadState.log)
                            Thread.sleep(2_000)
                        } else throw error
                    }
                }

                updateState("Saving files to Downloads…", 99, DownloadState.log)
                if (browserCaptured) {
                    check(sessionDir.walkTopDown().any { it.isFile && CompletedMediaValidator.looksLikeMedia(it) }) {
                        "The browser-selected URL did not produce a playable media file. Reopen the page and detect the actual video."
                    }
                }
                val exported = exportCompletedFiles(sessionDir)
                check(exported.isNotEmpty()) { "yt-dlp finished but no completed media file was found." }
                val summary = "Saved ${exported.size} file${if (exported.size == 1) "" else "s"} to Downloads/yt-dlp Mobile"
                val finalLog = listOf(DownloadState.log, responseOutput.takeLast(6_000), summary)
                    .filter { it.isNotBlank() }.joinToString("\n").takeLast(MAX_LOG_CHARS)
                finish(summary, 100, finalLog)
            } catch (e: Exception) {
                val canceled = !DownloadState.running || e.javaClass.simpleName.contains("Canceled")
                val details = "${DownloadState.log}\n${e.message.orEmpty()}"
                val message = when {
                    canceled -> "Download stopped"
                    details.contains("no impersonate target", true) ->
                        "This site requires browser protection. Use Browser-assisted download."
                    details.contains("confirm you’re not a bot", true) || details.contains("confirm you're not a bot", true) ->
                        "YouTube does not trust this network/IP. Import cookies.txt from your own session or change networks, then retry."
                    details.contains("Usage: yt-dlp", true) || details.contains("optparse", true) ->
                        "Invalid Extra arguments. Correct or clear them, then retry."
                    browserCaptured && details.contains("403") ->
                        "The captured stream was still protected or expired. Reopen Browser Assist and play the video again."
                    browserCaptured && details.contains("410") ->
                        "The captured stream expired or rejected its browser session. Reopen Browser Assist and play the video again."
                    details.contains("403") ->
                        "Website rejected the request (403). Try Browser-assisted download."
                    else -> "Download failed: ${e.message ?: e.javaClass.simpleName}"
                }
                finish(message, DownloadState.progress, "$details\n$message".takeLast(MAX_LOG_CHARS))
            } finally {
                processId = null
                stopWatchingNetwork()
                sessionDir.deleteRecursively()
            }
        }
    }

    private fun createRequest(
        url: String,
        preset: String,
        customArgs: List<String>,
        playlist: Boolean,
        speedBoost: Boolean,
        subtitles: Boolean,
        autoSubtitles: Boolean,
        subtitleLanguages: String,
        sessionDir: File,
        referer: String,
        cookies: String,
        userAgent: String,
        capturedHeaders: Map<String, String>,
        browserCaptured: Boolean,
        cookieFile: String
    ): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
            .addOption("--newline")
            .addOption("--no-mtime")
            .addOption("--progress")
            .addOption("--continue")
            .addOption("--remote-components", "ejs:github")
            .addOption("--retries", 10)
            .addOption("--fragment-retries", 10)
            .addOption("-o", File(sessionDir, if (playlist) "%(playlist_index)03d - %(title)s.%(ext)s" else "%(title)s.%(ext)s").absolutePath)

        if (speedBoost) request.addOption("--concurrent-fragments", MAX_CONCURRENT_FRAGMENTS)

        if (referer.isNotBlank()) request.addOption("--referer", referer)
        if (userAgent.isNotBlank()) request.addOption("--user-agent", userAgent)
        if (cookies.isNotBlank()) request.addOption("--add-headers", "Cookie:$cookies")
        if (cookieFile.isNotBlank() && File(cookieFile).isFile) request.addOption("--cookies", cookieFile)
        capturedHeaders
            .filterKeys { it.lowercase() !in setOf("cookie", "referer", "user-agent") }
            .forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) {
                    request.addOption("--add-headers", "$name:$value")
                }
            }
        if (browserCaptured) request.addOption("--force-generic-extractor")

        if (playlist) request.addOption("--yes-playlist") else request.addOption("--no-playlist")

        when (preset) {
            PRESET_MP4 -> request
                .addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                .addOption("--merge-output-format", "mp4")
            PRESET_MP3 -> request.addOption("-x").addOption("--audio-format", "mp3").addOption("--audio-quality", "0")
            PRESET_M4A -> request.addOption("-x").addOption("--audio-format", "m4a").addOption("--audio-quality", "0")
            PRESET_ORIGINAL -> Unit
            else -> request.addOption("-f", "bestvideo*+bestaudio/best")
        }

        if (subtitles) {
            request.addOption("--write-subs")
            if (autoSubtitles) request.addOption("--write-auto-subs")
            if (subtitleLanguages.isNotBlank()) request.addOption("--sub-langs", subtitleLanguages)
            request.addOption("--convert-subs", "vtt")
        }

        request.addCommands(customArgs)
        return request
    }

    private fun parseCapturedHeaders(json: String): Map<String, String> = runCatching {
        val objectValue = JSONObject(json)
        buildMap {
            objectValue.keys().forEach { key ->
                objectValue.optString(key).takeIf(String::isNotBlank)?.let { value -> put(key, value) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun exportCompletedFiles(sessionDir: File): List<Uri> {
        val files = sessionDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .toList()
        return files.map { exportToDownloads(it) }
    }

    private fun exportToDownloads(source: File): Uri {
        val displayName = sanitizeFilename(source.name)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(displayName))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/yt-dlp Mobile")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: error("Android could not create $displayName in Downloads.")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun sanitizeFilename(name: String): String = name
        .replace(Regex("[\\u0000-\\u001f\\u007f/\\\\]"), "_")
        .take(220)
        .ifBlank { "download-${System.currentTimeMillis()}" }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/opus"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "json", "info.json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun cancelDownload() {
        DownloadState.running = false
        processId?.let(YoutubeDL::destroyProcessById)
        updateState("Stopping…", DownloadState.progress, DownloadState.log)
    }

    private fun watchNetwork() {
        networkChanged.set(false)
        val manager = getSystemService(ConnectivityManager::class.java)
        var firstAvailable = true
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (firstAvailable) firstAvailable = false else networkChanged.set(true)
            }
            override fun onLost(network: Network) { networkChanged.set(true) }
        }.also { runCatching { manager.registerDefaultNetworkCallback(it) } }
    }

    private fun stopWatchingNetwork() {
        val callback = networkCallback ?: return
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        DownloadState.log = (DownloadState.log + "\n" + line).takeLast(MAX_LOG_CHARS)
    }

    private fun finish(status: String, progress: Int, log: String) {
        DownloadState.running = false
        DownloadState.operation = DownloadState.OPERATION_IDLE
        DownloadState.status = status
        DownloadState.progress = progress
        DownloadState.log = log
        sendState()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status, progress, finished = true))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun updateState(status: String, progress: Int, log: String) {
        DownloadState.status = status
        DownloadState.progress = progress
        DownloadState.log = log
        sendState()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status, progress))
    }

    private fun sendState() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_RUNNING, DownloadState.running)
            putExtra(EXTRA_OPERATION, DownloadState.operation)
            putExtra(EXTRA_PROGRESS, DownloadState.progress)
            putExtra(EXTRA_STATUS, DownloadState.status)
            putExtra(EXTRA_LOG, DownloadState.log)
        })
    }

    private fun buildNotification(status: String, progress: Int, finished: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("yt-dlp Mobile")
            .setContentText(status)
            .setOnlyAlertOnce(!finished)
            .setOngoing(!finished)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .apply {
                if (!finished) {
                    setProgress(100, progress, progress == 0)
                    addAction(0, "Stop", PendingIntent.getService(
                        this@DownloadService, 1,
                        Intent(this@DownloadService, DownloadService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ))
                }
            }
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun formatEta(seconds: Long): String {
        val minutes = seconds / 60
        val remaining = seconds % 60
        return if (minutes > 0) "${minutes}m ${remaining}s" else "${remaining}s"
    }

    private fun replaceInstantEta(line: String, stableEtaSeconds: Long?): String {
        if (!line.contains("ETA", ignoreCase = false)) return line
        val replacement = stableEtaSeconds?.let { "ETA ${formatEta(it)}" } ?: "ETA calculating…"
        return line.replace(Regex("\\bETA\\s+\\S+"), replacement)
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.linfranca.ytdlpmobile.DOWNLOAD"
        const val ACTION_STOP = "com.linfranca.ytdlpmobile.STOP"
        const val ACTION_STATE = "com.linfranca.ytdlpmobile.STATE"
        const val EXTRA_URL = "url"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_ARGS = "args"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_SPEED_BOOST = "speed_boost"
        const val EXTRA_SUBTITLES = "subtitles"
        const val EXTRA_AUTO_SUBTITLES = "auto_subtitles"
        const val EXTRA_SUBTITLE_LANGUAGES = "subtitle_languages"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_HEADERS_JSON = "headers_json"
        const val EXTRA_BROWSER_CAPTURED = "browser_captured"
        const val EXTRA_COOKIE_FILE = "cookie_file"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log"
        const val EXTRA_OPERATION = "operation"

        const val PRESET_BEST = "Best available quality"
        const val PRESET_MP4 = "MP4 compatibility"
        const val PRESET_MP3 = "MP3 audio"
        const val PRESET_M4A = "M4A audio"
        const val PRESET_ORIGINAL = "Original/site-selected format"
        val PRESETS = listOf(PRESET_BEST, PRESET_MP4, PRESET_MP3, PRESET_M4A, PRESET_ORIGINAL)

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 84
        private const val MAX_LOG_CHARS = 16_000
        private const val MAX_CONCURRENT_FRAGMENTS = 8
    }
}
