package com.linfranca.ytdlpmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal object CollectionState {
    @Volatile var running = false
    @Volatile var status = "Ready to download a collection"
    @Volatile var log = ""
    @Volatile var found = 0
    @Volatile var saved = 0
    @Volatile var skipped = 0
    @Volatile var failed = 0
    @Volatile var pageTotal = 0
    @Volatile var pageProcessed = 0
}

/** Independent of yt-dlp's DownloadService and the stream recorder. */
class CollectionDownloadService : Service() {
    private val stopping = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var activeMediaConnection: HttpURLConnection? = null
    @Volatile private var activeSourceConnection: HttpURLConnection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping.set(true)
                activeMediaConnection?.disconnect()
                activeSourceConnection?.disconnect()
                worker?.interrupt()
                update("Stopping collection download…", "Stop requested.")
            }
            ACTION_START -> if (!CollectionState.running) start(intent, startId)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent, startId: Int) {
        val link = intent.getStringExtra(EXTRA_LINK).orEmpty()
        val route = try { CollectionRoute.from(link) }
        catch (error: Exception) {
            update("Collection link error: ${error.message}", error.message.orEmpty())
            stopSelfResult(startId)
            return
        }
        val credentials = CollectionCredentials(
            intent.getStringExtra(EXTRA_USER).orEmpty(),
            intent.getStringExtra(EXTRA_KEY).orEmpty(),
            intent.getStringExtra(EXTRA_FURBOORU_KEY).orEmpty(),
            intent.getStringExtra(EXTRA_RULE34_USER).orEmpty(),
            intent.getStringExtra(EXTRA_RULE34_KEY).orEmpty()
        )
        stopping.set(false)
        CollectionState.running = true
        CollectionState.found = 0
        CollectionState.saved = 0
        CollectionState.skipped = 0
        CollectionState.failed = 0
        CollectionState.pageTotal = 0
        CollectionState.pageProcessed = 0
        CollectionState.log = ""
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing ${route.site} collection…"))
        update("Finding files from ${route.site}…", "Collection started: ${route.site}, ${route.title}")

        worker = Thread({
            try {
                val folder = "yt-dlp Mobile/Collections/${safe(route.site)}/${safe(route.title)}"
                CollectionSources(credentials, { stopping.get() }, { activeSourceConnection = it },
                    { event -> update("Finding collection files…", event) }) { page, items ->
                    if (stopping.get()) throw InterruptedException()
                    CollectionState.found += items.size
                    CollectionState.pageTotal = items.size
                    CollectionState.pageProcessed = 0
                    update("Page $page • ${CollectionState.saved} saved, ${CollectionState.skipped} already saved",
                        "Page $page: found ${items.size} files.")
                    for (item in items) {
                        if (stopping.get()) break
                        val targetFolder = if (item.folder.isBlank()) folder else "$folder/${safe(item.folder)}"
                        val name = fileName(item)
                        if (existsInDownloads(targetFolder, name)) {
                            CollectionState.skipped++
                            CollectionState.pageProcessed++
                            update("Page $page: ${CollectionState.pageProcessed}/${CollectionState.pageTotal} • ${CollectionState.skipped} already saved",
                                "Already saved: $name")
                            continue
                        }
                        try {
                            downloadWithRetry(item, name, targetFolder)
                            CollectionState.pageProcessed++
                            CollectionState.saved++
                            update("Page $page: ${CollectionState.pageProcessed}/${CollectionState.pageTotal} • ${CollectionState.saved} saved",
                                "Saved $name")
                        } catch (error: InterruptedException) {
                            throw error
                        } catch (error: Exception) {
                            CollectionState.pageProcessed++
                            CollectionState.failed++
                            update("Page $page: ${CollectionState.pageProcessed}/${CollectionState.pageTotal} • ${CollectionState.failed} failed",
                                "Failed $name: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }.discover(route)
                val message = when {
                    stopping.get() -> "Stopped. ${CollectionState.saved} files saved."
                    CollectionState.found == 0 -> "No downloadable files found for this link."
                    else -> "Collection finished: ${CollectionState.saved} saved, ${CollectionState.skipped} already saved, ${CollectionState.failed} failed."
                }
                update(message, message)
            } catch (error: InterruptedException) {
                update("Stopped. ${CollectionState.saved} files saved.", "Collection stopped by user.")
            } catch (error: Exception) {
                // Never log API keys, authorization headers or full media URLs.
                update("Collection failed after ${CollectionState.saved} saved: ${error.message ?: "unknown error"}",
                    "Error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                activeMediaConnection?.disconnect()
                activeMediaConnection = null
                activeSourceConnection?.disconnect()
                activeSourceConnection = null
                CollectionState.running = false
                publish()
                stopForeground(STOP_FOREGROUND_REMOVE)
                // A later Stop intent has a newer startId. End the service after the worker exits.
                stopSelf()
                worker = null
            }
        }, "collection-download").apply { start() }
    }

    private fun downloadWithRetry(item: CollectionItem, name: String, folder: String) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            if (stopping.get()) throw InterruptedException()
            try {
                try { download(item, name, folder) }
                catch (error: MediaHttpError) {
                    if (error.status == 404 && item.fallbackUrl.isNotBlank()) {
                        update("Trying displayed image for $name…", "Original image unavailable; trying displayed image for $name.")
                        download(item.copy(url = item.fallbackUrl), name, folder)
                    } else throw error
                }
                return
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                if (stopping.get()) throw InterruptedException()
                if (error is MediaHttpError && error.status in 400..499 && error.status != 429)
                    throw error
                lastError = error
                update("Retrying $name (${attempt + 1}/3)…",
                    "Attempt ${attempt + 1} failed for $name: ${error.message}")
                if (attempt < 2) Thread.sleep(
                    if (error is MediaHttpError && error.retryAfter in 1..60) error.retryAfter * 1000L
                    else (attempt + 1) * 2000L)
            }
        }
        throw IllegalStateException("$name failed after three attempts: ${lastError?.message}")
    }

    private class MediaHttpError(val status: Int, val retryAfter: Int) :
        IllegalStateException("Media server returned HTTP $status.")

    private fun download(item: CollectionItem, name: String, folder: String) {
        val url = URL(item.url)
        require(url.protocol == "https") { "The source returned an unsupported media URL." }
        val connection = url.openConnection() as HttpURLConnection
        activeMediaConnection = connection
        var uri: Uri? = null
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "yt-dlp Mobile/0.6.21 (Android; collection downloader)")
            val code = connection.responseCode
            if (code !in 200..299) throw MediaHttpError(
                code, connection.getHeaderField("Retry-After")?.toIntOrNull() ?: 0)
            val contentType = connection.contentType?.substringBefore(';')?.lowercase(Locale.ROOT).orEmpty()
            if (contentType.startsWith("text/") || contentType == "application/json")
                throw IllegalStateException("Media server returned a webpage instead of a file.")
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime(item.extension))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val outputUri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("Android could not create $name in Downloads.")
            uri = outputUri
            var total = 0L
            connection.inputStream.use { input ->
                contentResolver.openOutputStream(outputUri, "w")!!.use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        if (stopping.get()) throw InterruptedException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
            }
            if (total == 0L) throw IllegalStateException("Media server returned an empty file.")
            val published = contentResolver.update(
                outputUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            if (published < 1) throw IllegalStateException("Android could not publish $name in Downloads.")
            uri = null
        } finally {
            if (uri != null) contentResolver.delete(uri, null, null)
            connection.disconnect()
            activeMediaConnection = null
        }
    }

    private fun fileName(item: CollectionItem): String {
        val ext = safe(item.extension.lowercase(Locale.ROOT)).take(8)
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        val prefix = if (item.order > 0) "${item.order.toString().padStart(5, '0')}-" else ""
        return "$prefix${safe(item.id).take(90)}.$ext"
    }

    private fun existsInDownloads(folder: String, name: String): Boolean =
        contentResolver.query(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/$folder/"), null
        )?.use { it.moveToFirst() } ?: false

    private fun safe(text: String) = text.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
        .trim('.', ' ').take(90).ifBlank { "collection" }

    private fun mime(ext: String) = when (ext.lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private fun update(status: String, logLine: String) {
        CollectionState.status = status
        CollectionState.log = (CollectionState.log + logLine + "\n").takeLast(14_000)
        publish()
        if (CollectionState.running) getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(status))
    }

    private fun publish() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Collection downloads", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, CollectionDownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Collection download")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping.set(true)
        activeMediaConnection?.disconnect()
        activeSourceConnection?.disconnect()
        worker?.interrupt()
        update("Android's background transfer time limit was reached. Saved files remain available.",
            "Android ended this foreground transfer after its time limit.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping.set(true)
        activeMediaConnection?.disconnect()
        activeSourceConnection?.disconnect()
        worker?.interrupt()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.linfranca.ytdlpmobile.COLLECTION_START"
        const val ACTION_STOP = "com.linfranca.ytdlpmobile.COLLECTION_STOP"
        const val ACTION_STATE = "com.linfranca.ytdlpmobile.COLLECTION_STATE"
        const val EXTRA_LINK = "collection_link"
        const val EXTRA_USER = "collection_e6_user"
        const val EXTRA_KEY = "collection_e6_key"
        const val EXTRA_FURBOORU_KEY = "collection_furbooru_key"
        const val EXTRA_RULE34_USER = "collection_rule34_user"
        const val EXTRA_RULE34_KEY = "collection_rule34_key"
        private const val CHANNEL_ID = "collection_download"
        private const val NOTIFICATION_ID = 4218
    }
}
