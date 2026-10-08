package com.linfranca.ytdlpmobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.max

/** Writes JPEG pages directly into the PDF. Other Android-readable still images become JPEG streams. */
internal class ComicPdfWriter(private val resolver: ContentResolver) {
    data class Video(val name: String, val uri: Uri?)
    private data class Image(val bytes: ByteArray, val width: Int, val height: Int,
                             val rotation: Int, val colorSpace: String)
    private class Counted(private val stream: OutputStream) {
        var count = 0L; private set
        fun bytes(bytes: ByteArray) { stream.write(bytes); count += bytes.size }
        fun bytes(bytes: ByteArray, length: Int) { stream.write(bytes, 0, length); count += length }
        fun flush() = stream.flush()
        fun text(value: String) = bytes(value.toByteArray(StandardCharsets.ISO_8859_1))
    }

    fun write(output: OutputStream, pages: List<Uri>, videos: List<Video>,
              stopped: () -> Boolean, progress: (Int) -> Unit) {
        require(pages.isNotEmpty()) { "No comic pages selected." }
        val videoPages = videos.chunked(25)
        val pageIds = pages.indices.map { 5 + 3 * it } +
            videoPages.indices.map { 3 + 3 * pages.size + it * 2 }
        val videoStart = 3 + pages.size * 3
        val annotationStart = videoStart + videoPages.size * 2
        val embedded = videos.any { it.uri != null }
        require(!embedded || videos.all { it.uri != null }) { "Every video needs a readable file to embed." }
        val iconStart = annotationStart + videos.size
        val embeddedStart = iconStart + if (embedded) videos.size else 0
        val nameTreeId = embeddedStart + videos.size * 3
        val textAppearanceId = nameTreeId + 1
        val offsets = LongArray(if (embedded) textAppearanceId + 1 else embeddedStart)
        val writer = Counted(BufferedOutputStream(output, 64 * 1024))
        val filenames = videos.map { pdfUtf16(it.name) }
        writer.text("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
        fun obj(id: Int, content: String) {
            offsets[id] = writer.count
            writer.text("$id 0 obj\n$content\nendobj\n")
        }
        fun stream(id: Int, dict: String, bytes: ByteArray) {
            offsets[id] = writer.count
            writer.text("$id 0 obj\n<< $dict /Length ${bytes.size} >>\nstream\n")
            writer.bytes(bytes)
            writer.text("\nendstream\nendobj\n")
        }
        obj(1, if (embedded) "<< /Type /Catalog /Pages 2 0 R " +
            "/Names << /EmbeddedFiles $nameTreeId 0 R >> /PageMode /UseAttachments >>"
            else "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>")
        pages.forEachIndexed { index, uri ->
            if (stopped()) throw InterruptedException("PDF conversion cancelled.")
            val image = readImage(uri)
            val dimensions = NativeMedia.pdfSize(image.width, image.height, image.rotation)
            val width = dimensions[0]; val height = dimensions[1]
            val imageId = 3 + index * 3; val contentsId = imageId + 1; val pageId = imageId + 2
            stream(imageId, "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                "/ColorSpace /${image.colorSpace} /BitsPerComponent 8 /Filter /DCTDecode", image.bytes)
            val matrix = when (image.rotation) {
                3 -> "-${width} 0 0 -${height} $width $height"
                6 -> "0 -$height $width 0 0 $height"
                8 -> "0 $height -$width 0 $width 0"
                else -> "$width 0 0 $height 0 0"
            }
            stream(contentsId, "", "q $matrix cm /Im Do Q\n".toByteArray(StandardCharsets.US_ASCII))
            obj(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                "/Resources << /XObject << /Im $imageId 0 R >> >> /Contents $contentsId 0 R >>")
            progress(index + 1)
        }
        videoPages.forEachIndexed { page, names ->
            if (stopped()) throw InterruptedException("PDF conversion cancelled.")
            val pageId = videoStart + 2 * page
            val contentId = pageId + 1
            val lines = buildString {
                append("BT /F1 18 Tf 48 770 Td (Companion videos) Tj ET\n")
                if (embedded) append("BT /F1 10 Tf 48 750 Td (Tap a filename or paperclip to open its video.) Tj ET\n0.2 0.35 0.8 rg\n")
                names.forEachIndexed { i, video ->
                    val clean = video.name.map { if (it.code in 32..126) it else '?' }.joinToString("")
                    append("BT /F1 11 Tf 52 ${728 - i * 26} Td (${pdfLiteral(clean)}) Tj ET\n")
                }
            }
            stream(contentId, "", lines.toByteArray(StandardCharsets.US_ASCII))
            val first = videoPages.take(page).sumOf { it.size }
            val annotations = names.indices.joinToString(" ") { index ->
                "${annotationStart + first + index} 0 R" +
                    if (embedded) " ${iconStart + first + index} 0 R" else ""
            }
            obj(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> " +
                "/Contents $contentId 0 R /Annots [$annotations] >>")
        }
        videos.forEachIndexed { index, video ->
            if (stopped()) throw InterruptedException("PDF conversion cancelled.")
            val line = index % 25; val filename = filenames[index]
            if (embedded) {
                val fileSpecId = embeddedStart + index * 3 + 2
                obj(annotationStart + index, "<< /Type /Annot /Subtype /FileAttachment " +
                    "/Rect [48 ${724 - line * 26} 525 ${742 - line * 26}] " +
                    "/AP << /N $textAppearanceId 0 R >> /FS $fileSpecId 0 R /Contents $filename >>")
                obj(iconStart + index, "<< /Type /Annot /Subtype /FileAttachment " +
                    "/Rect [530 ${724 - line * 26} 550 ${742 - line * 26}] /Name /Paperclip " +
                    "/FS $fileSpecId 0 R /Contents $filename >>")
            } else {
                obj(annotationStart + index, "<< /Type /Annot /Subtype /Link " +
                    "/Rect [48 ${724 - line * 26} 550 ${742 - line * 26}] /Border [0 0 0] " +
                    "/A << /S /Launch /F << /Type /Filespec /F $filename /UF $filename >> >> >>")
            }
        }
        if (embedded) {
            videos.forEachIndexed { index, video ->
                if (stopped()) throw InterruptedException("PDF conversion cancelled.")
                progress(pages.size + index)
                val streamId = embeddedStart + index * 3
                val lengthId = streamId + 1
                val fileSpecId = streamId + 2
                val filename = filenames[index]
                offsets[streamId] = writer.count
                writer.text("$streamId 0 obj\n<< /Type /EmbeddedFile " +
                    "/Length $lengthId 0 R >>\nstream\n")
                var length = 0L
                resolver.openInputStream(video.uri!!)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (stopped()) throw InterruptedException("PDF conversion cancelled.")
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (size == 0) continue
                        writer.bytes(buffer, size)
                        length += size
                    }
                } ?: error("Cannot open companion video ${video.name} for embedding.")
                writer.text("\nendstream\nendobj\n")
                obj(lengthId, length.toString())
                obj(fileSpecId, "<< /Type /Filespec /F $filename /UF $filename " +
                    "/EF << /F $streamId 0 R /UF $streamId 0 R >> >>")
                progress(pages.size + index + 1)
            }
            val entries = videos.indices.sortedBy { filenames[it] }.joinToString(" ") { index ->
                "${filenames[index]} ${embeddedStart + index * 3 + 2} 0 R"
            }
            obj(nameTreeId, "<< /Names [$entries] >>")
            // An empty annotation appearance leaves the printed filename visible while its
            // full row remains an active file attachment. The icon remains a second target.
            stream(textAppearanceId, "/Type /XObject /Subtype /Form /BBox [0 0 477 18] /Resources << >>",
                ByteArray(0))
        }
        val xref = writer.count
        writer.text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        writer.bytes(NativeMedia.xref(offsets))
        writer.text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        writer.flush()
    }

    private fun pdfLiteral(text: String) = NativeMedia.pdfLiteral(text)
    private fun pdfUtf16(text: String): String {
        // Charset encoder preserves its existing replacement for unpaired surrogates.
        return "<FEFF" + NativeMedia.hex(text.toByteArray(StandardCharsets.UTF_16BE)) + ">"
    }

    private fun readImage(uri: Uri): Image {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "One selected image cannot be decoded." }
        val orientation = runCatching { resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } ?: 1 }.getOrDefault(1)
        val jpeg = resolver.getType(uri)?.lowercase() in listOf("image/jpeg", "image/jpg") ||
            uri.toString().substringBefore('?').endsWith(".jpg", true) ||
            uri.toString().substringBefore('?').endsWith(".jpeg", true)
        if (jpeg && orientation in listOf(1, 3, 6, 8)) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open image.")
            // Direct JPEG streams retain their original resolution and encoding.
            val colors = when (jpegComponents(bytes)) {
                1 -> "DeviceGray"
                4 -> "DeviceCMYK"
                else -> "DeviceRGB"
            }
            return Image(bytes, options.outWidth, options.outHeight, orientation, colors)
        }
        val sample = max(1, ceil(max(options.outWidth, options.outHeight) / 2500.0).toInt())
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot decode an image.")
        try {
            val rgb = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
            try {
                Canvas(rgb).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                val out = ByteArrayOutputStream()
                check(rgb.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "Cannot encode a PDF image." }
                return Image(out.toByteArray(), rgb.width, rgb.height, 1, "DeviceRGB")
            } finally { rgb.recycle() }
        } finally { bitmap.recycle() }
    }

    private fun jpegComponents(data: ByteArray): Int {
        NativeMedia.jpegComponents(data)?.let { return it }
        if (data.size < 4 || data[0].toInt() and 255 != 255 || data[1].toInt() and 255 != 216) return 3
        var index = 2
        while (index + 9 < data.size) {
            if (data[index].toInt() and 255 != 255) break
            val marker = data[index + 1].toInt() and 255
            if (marker == 218 || marker == 217) break
            val length = ((data[index + 2].toInt() and 255) shl 8) or (data[index + 3].toInt() and 255)
            if (length < 2 || index + 2 + length > data.size) break
            if (marker in listOf(192, 193, 194, 195, 198, 199)) return data[index + 9].toInt() and 255
            index += 2 + length
        }
        return 3
    }
}
