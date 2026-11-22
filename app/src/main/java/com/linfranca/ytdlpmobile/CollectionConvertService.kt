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
        val sourcePdf = payload.optString("sourcePdf").takeIf { it.isNotBlank() }?.let(Uri::parse)
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
        if (images.isEmpty()) { request.delete(); stopSelf(); return }
        val suggestedName = if (sourcePdf == null) "$title.pdf" else "$title - updated.pdf"
        stopped.set(false)
        CollectionConvertState.running = true
        CollectionConvertState.total = images.size + if (embedVideos) videos.size else 0
        CollectionConvertState.done = 0
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, notification(if (sourcePdf == null) "Creating $suggestedName" else "Updating $title"))
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
            var stagedPdf: File? = null
            try {
                val inputs=images+if(embedVideos) (0 until (videoUris?.length() ?: 0)).map { Uri.parse(videoUris!!.getString(it)) } else emptyList()
                val knownBytes=(inputs + listOfNotNull(sourcePdf)).fold(0L) { total,uri ->
                    val bytes=runCatching { contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.SIZE),null,null,null)?.use { if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0)
                    if(bytes>0 && total<=Long.MAX_VALUE-bytes) total+bytes else total
                }
                StorageGuard.destination(this,knownBytes,destination)
                val totalPages: Int
                if (sourcePdf != null) {
                    StorageGuard.working(this, knownBytes, "updating PDF")
                    val temp = File.createTempFile("comic-updated-", ".pdf", cacheDir)
                    stagedPdf = temp
                    totalPages = ComicPdfAppender(this).write(sourcePdf, images, temp, { stopped.get() }, { completed ->
                        CollectionConvertState.done = completed
                        update("Preparing new image pages: $completed/${images.size}")
                    }, { update("Writing updated PDF…") })
                    if (stopped.get()) throw InterruptedException()
                    StorageGuard.destination(this, temp.length(), destination)
                    val copy = DocumentsContract.createDocument(contentResolver, destination, "application/pdf", suggestedName)
                        ?: error("Cannot create the updated PDF in the selected folder.")
                    val sameDocument = copy == sourcePdf || (copy.authority == sourcePdf.authority && runCatching {
                        DocumentsContract.getDocumentId(copy) == DocumentsContract.getDocumentId(sourcePdf)
                    }.getOrDefault(false))
                    check(!sameDocument) { "The destination did not create a separate PDF. Choose a different folder." }
                    outputUri = copy
                    update("Saving updated copy…")
                    contentResolver.openOutputStream(outputUri!!, "w")?.use { output ->
                        temp.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (stopped.get()) throw InterruptedException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Cannot open the updated copy for writing.")
                } else {
                    totalPages = images.size
                    outputUri = DocumentsContract.createDocument(contentResolver, destination,
                        "application/pdf", suggestedName) ?: error("Cannot create a PDF in the selected folder.")
                    contentResolver.openOutputStream(outputUri!!, "w")?.use { output ->
                        ComicPdfWriter(contentResolver).write(output, images, videoFiles, { stopped.get() }) { completed ->
                            if (stopped.get()) throw InterruptedException("PDF conversion cancelled.")
                            CollectionConvertState.done = completed
                            update(if (embedVideos && completed >= images.size)
                                "Embedding videos: ${completed - images.size}/${videos.size} files"
                                else "Creating PDF: $completed/${images.size} pages")
                        }
                    } ?: error("Cannot open the new PDF for writing.")
                }
                if (stopped.get()) throw InterruptedException("PDF conversion cancelled.")
                val name = runCatching {
                    contentResolver.query(outputUri!!, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                }.getOrNull() ?: suggestedName
                outputUri = null // Only a fully closed, valid PDF is retained.
                if (sourcePdf != null) {
                    update("Saved $name: appended ${images.size} image page(s), $totalPages pages total. Original PDF kept.")
                } else {
                    update("Saved $name: ${images.size} pages; ${videos.size} " +
                        (if (embedVideos) "embedded video attachments" else "video links") + ". Originals kept.")
                }
            } catch (_: InterruptedException) {
                update("Conversion cancelled; incomplete PDF removed. Originals kept.")
            } catch (error: Exception) {
                update(when {
                    stopped.get() -> "Conversion cancelled; incomplete PDF removed. Originals kept."
                    StorageGuard.failure(error) -> if (sourcePdf != null)
                        "Not enough storage space for the updated PDF. Free space and try again. Original PDF kept."
                    else "Not enough storage space for the PDF. Free space and convert again. Original images and videos are kept."
                    sourcePdf != null -> "Could not update PDF: ${error.message ?: "Unable to read or write the PDF."} Original PDF kept."
                    else -> "PDF conversion failed at page ${CollectionConvertState.done + 1}: ${error.message}"
                })
            } finally {
                request.delete()
                stagedPdf?.delete()
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
