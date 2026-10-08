package com.linfranca.ytdlpmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object CollectionConvertState {
    @Volatile var running = false
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var message = "Choose a folder or images to begin."
}

class CollectionConvertService : Service() {
    private val stopped = AtomicBoolean(false)
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> if (CollectionConvertState.running) {
                stopped.set(true); worker?.interrupt()
                update("Cancelling PDF conversion…")
            } else stopSelf()
            ACTION_START -> if (!CollectionConvertState.running) begin(intent)
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        val requestName = intent.getStringExtra(EXTRA_REQUEST)
        if (requestName == null || !requestName.matches(Regex("comic-convert-[a-f0-9-]+\\.json"))) return
        val request = File(filesDir, requestName)
        val payload = try { JSONObject(request.readText()) }
        catch (error: Exception) { request.delete(); return }
        val destination = Uri.parse(payload.getString("folder"))
        val imageArray = payload.getJSONArray("images")
        val images = (0 until imageArray.length()).map { Uri.parse(imageArray.getString(it)) }
        val videoArray = payload.getJSONArray("videos")
        val videos = (0 until videoArray.length()).map { videoArray.getString(it) }
        val embedVideos = payload.optBoolean("embedVideos", false)
        val videoUris = payload.optJSONArray("videoUris")
        if (embedVideos && (videoUris == null || videoUris.length() != videos.size)) {
            request.delete()
            CollectionConvertState.message = "Video attachments could not start: missing video file references."
            return
        }
        val videoFiles = videos.mapIndexed { index, name -> ComicPdfWriter.Video(name,
            if (embedVideos) Uri.parse(videoUris!!.getString(index)) else null) }
        val title = payload.optString("title")
            .replace(Regex("[\\x00-\\x1F/\\\\]"), "_").trim().take(90).ifEmpty { "Comic" }
        if (images.isEmpty()) { request.delete(); return }
        stopped.set(false)
        CollectionConvertState.running = true
        CollectionConvertState.total = images.size + if (embedVideos) videos.size else 0
        CollectionConvertState.done = 0
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, notification("Creating $title.pdf"))
        } catch (error: Exception) {
            request.delete()
            CollectionConvertState.running = false
            CollectionConvertState.message = "Could not start PDF conversion: ${error.message}"
            broadcast()
            stopSelf()
            return
        }
        update("Preparing PDF: 0/${images.size} pages")
        worker = Thread({
            var outputUri: Uri? = null
            try {
                outputUri = DocumentsContract.createDocument(contentResolver, destination,
                    "application/pdf", "$title.pdf") ?: error("Cannot create a PDF in the selected folder.")
                contentResolver.openOutputStream(outputUri!!, "w")?.use { output ->
                    ComicPdfWriter(contentResolver).write(output, images, videoFiles, { stopped.get() }) { completed ->
                        if (stopped.get()) throw InterruptedException("PDF conversion cancelled.")
                        CollectionConvertState.done = completed
                        update(if (embedVideos && completed >= images.size)
                            "Embedding videos: ${completed - images.size}/${videos.size} files"
                            else "Creating PDF: $completed/${images.size} pages")
                    }
                } ?: error("Cannot open the new PDF for writing.")
                if (stopped.get()) throw InterruptedException("PDF conversion cancelled.")
                val name = runCatching {
                    contentResolver.query(outputUri!!, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                }.getOrNull() ?: "$title.pdf"
                outputUri = null // Only a fully closed, valid PDF is retained.
                update("Saved $name: ${images.size} pages; ${videos.size} " +
                    (if (embedVideos) "embedded video attachments" else "video links") + ". Originals kept.")
            } catch (_: InterruptedException) {
                update("Conversion cancelled; incomplete PDF removed. Originals kept.")
            } catch (error: Exception) {
                update("PDF conversion failed at page ${CollectionConvertState.done + 1}: ${error.message}")
            } finally {
                request.delete()
                outputUri?.let { runCatching { DocumentsContract.deleteDocument(contentResolver, it) } }
                CollectionConvertState.running = false
                broadcast()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                worker = null
            }
        }, "comic-pdf").apply { start() }
    }

    private fun update(message: String) {
        CollectionConvertState.message = message
        broadcast()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }
    private fun broadcast() = sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    private fun createChannel() = getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Comic PDF conversion", NotificationManager.IMPORTANCE_LOW))
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, CollectionConvertActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download).setContentTitle("Comic PDF conversion")
            .setContentText(message).setContentIntent(open).setOngoing(CollectionConvertState.running)
            .setProgress(CollectionConvertState.total, CollectionConvertState.done, false).build()
    }

    companion object {
        const val ACTION_START = "com.linfranca.ytdlpmobile.CONVERT_START"
        const val ACTION_CANCEL = "com.linfranca.ytdlpmobile.CONVERT_CANCEL"
        const val ACTION_STATE = "com.linfranca.ytdlpmobile.CONVERT_STATE"
        const val EXTRA_REQUEST = "request"
        private const val CHANNEL_ID = "comic_pdf_conversion"
        private const val NOTIFICATION_ID = 12542
    }
}
