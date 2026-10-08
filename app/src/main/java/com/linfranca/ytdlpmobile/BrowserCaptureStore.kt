package com.linfranca.ytdlpmobile

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Local, bounded handoff from the WebView recorder to the foreground service. */
object BrowserCaptureStore {
    private var file: File? = null
    private var output: FileOutputStream? = null
    private var bytes = 0L

    @Synchronized fun begin(context: Context) {
        check(output == null) { "Another browser recording is already active." }
        val folder = File(context.cacheDir, "browser-recordings").apply { mkdirs() }
        file = File(folder, "media-${UUID.randomUUID()}.webm")
        output = FileOutputStream(file)
        bytes = 0L
    }

    @Synchronized fun append(encoded: String): Long {
        require(encoded.length <= 2_000_000) { "A recording chunk was too large to transfer safely." }
        val data = Base64.decode(encoded, Base64.DEFAULT)
        check(bytes + data.size <= 4_000_000_000L) { "Recording exceeded the temporary-file size limit." }
        (output ?: error("Recording is no longer active.")).write(data)
        bytes += data.size
        return bytes
    }

    @Synchronized fun take(): File? {
        output?.close(); output = null
        return file.also { file = null; bytes = 0L }
    }

    @Synchronized fun discard() {
        output?.close(); output = null
        file?.delete(); file = null; bytes = 0L
    }
}
