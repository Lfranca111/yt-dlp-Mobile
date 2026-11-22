package com.linfranca.ytdlpmobile

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.FilterOutputStream

/** Uses the original document as the base so existing pages are not rendered or recompressed. */
internal class ComicPdfAppender(private val context: Context) {
    fun write(source: Uri, images: List<Uri>, result: File, stopped: () -> Boolean,
              progress: (Int) -> Unit, finalizing: () -> Unit): Int {
        fun checkCancelled() {
            if (stopped() || Thread.currentThread().isInterrupted) throw InterruptedException()
        }
        fun scratch() = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        val resolver = context.contentResolver
        PDFBoxResourceLoader.init(context.applicationContext)
        val newPages = File.createTempFile("comic-new-pages-", ".pdf", context.cacheDir)
        try {
            val original = try {
                resolver.openInputStream(source)?.use { PDDocument.load(it, scratch()) }
                    ?: error("Cannot open the selected PDF.")
            } catch (error: InvalidPasswordException) {
                throw IllegalArgumentException("Password-protected PDFs cannot be updated.", error)
            }
            original.use { document ->
                checkCancelled()
                require(!document.isEncrypted) { "Encrypted PDFs cannot be updated." }
                require(document.signatureDictionaries.isEmpty()) { "Digitally signed PDFs cannot be updated." }
                val oldPageCount = document.numberOfPages
                require(oldPageCount > 0) { "The selected PDF has no pages." }
                newPages.outputStream().use { output ->
                    ComicPdfWriter(resolver).write(output, images, emptyList(), stopped) {
                        checkCancelled()
                        progress(it)
                    }
                }
                PDDocument.load(newPages, scratch()).use { additions ->
                    // ComicPdfWriter puts resources directly on every image page.
                    for (page in additions.pages) {
                        checkCancelled()
                        document.importPage(page)
                    }
                    check(document.numberOfPages == oldPageCount + images.size)
                    finalizing()
                    result.outputStream().buffered(64 * 1024).use { output ->
                        document.save(object : FilterOutputStream(output) {
                            override fun write(b: Int) { checkCancelled(); out.write(b) }
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                checkCancelled()
                                out.write(b, off, len)
                            }
                        })
                    }
                }
                checkCancelled()
                return oldPageCount + images.size
            }
        } finally {
            newPages.delete()
        }
    }
}
