package com.linfranca.ytdlpmobile

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray

internal data class ComicFile(val uri: Uri, val name: String, val mime: String) {
    val image: Boolean get() = mime.lowercase() in setOf("image/jpeg", "image/png", "image/webp", "image/bmp") ||
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp")
    val video: Boolean get() = mime.startsWith("video/") ||
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "webm", "mov", "mkv", "avi")
}

/** SAF tree access only: no broad storage permission or raw filesystem paths. */
internal object ComicFiles {
    fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree))

    fun children(resolver: ContentResolver, tree: Uri, folder: Uri): List<ComicFile> {
        val result = mutableListOf<ComicFile>()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        resolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) result += ComicFile(
                DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(id)),
                cursor.getString(name).orEmpty(), cursor.getString(mime).orEmpty())
        }
        return result
    }

    private val parts = Regex("\\d+|\\D+")
    private val pagePattern = Regex("^(\\d+)[-_. ]")

    fun naturalCompare(a: String, b: String): Int {
        val first = parts.findAll(a.lowercase()).map { it.value }.toList()
        val second = parts.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(first.size, second.size)) {
            val x = first[i]; val y = second[i]
            val number = x.first().isDigit() && y.first().isDigit()
            val c = if (number) {
                val u = x.trimStart('0').ifEmpty { "0" }
                val v = y.trimStart('0').ifEmpty { "0" }
                u.length.compareTo(v.length).takeIf { it != 0 } ?: u.compareTo(v)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return first.size.compareTo(second.size).takeIf { it != 0 } ?: a.compareTo(b)
    }

    fun sorted(files: List<ComicFile>): List<ComicFile> {
        NativeMedia.order(files.map { it.name })?.let { order -> return order.map { files[it] } }
        // Even on other ABIs, lowercase/tokenize each name only once per sort.
        data class Key(val file: ComicFile, val tokens: List<String>)
        val keys = files.map { Key(it, parts.findAll(it.name.lowercase()).map { m -> m.value }.toList()) }
        return keys.sortedWith { a, b -> compareTokens(a.tokens, b.tokens, a.file.name, b.file.name) }.map { it.file }
    }

    private fun compareTokens(first: List<String>, second: List<String>, a: String, b: String): Int {
        for (i in 0 until minOf(first.size, second.size)) {
            val x = first[i]; val y = second[i]
            val c = if (x.first().isDigit() && y.first().isDigit()) {
                val u = x.trimStart('0').ifEmpty { "0" }; val v = y.trimStart('0').ifEmpty { "0" }
                u.length.compareTo(v.length).takeIf { it != 0 } ?: u.compareTo(v)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return first.size.compareTo(second.size).takeIf { it != 0 } ?: a.compareTo(b)
    }

    fun diagnostic(files: List<ComicFile>): String {
        val pageNumbers = files.mapNotNull { pagePattern.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
        if (pageNumbers.size != files.size) return "Order inferred from filenames. Check the preview before converting."
        val duplicates = pageNumbers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val present = pageNumbers.toHashSet()
        val missing = if (pageNumbers.isNotEmpty() && pageNumbers.max() - pageNumbers.min() < 10_000)
            (pageNumbers.min()..pageNumbers.max()).asSequence().filterNot { it in present }.take(12).toList() else emptyList()
        return buildString {
            append("Numbered pages: ${files.size}.")
            if (duplicates.isNotEmpty()) append(" Duplicate page numbers: ${duplicates.sorted().joinToString()}.")
            if (missing.isNotEmpty()) append(" Missing page numbers: ${missing.joinToString()}.")
        }
    }

    fun saveOrder(folder: Uri?, files: List<ComicFile>, preferences: android.content.SharedPreferences) {
        if (folder == null) return
        val ordered = JSONArray()
        files.forEach { ordered.put(it.uri.toString()) }
        preferences.edit().putString("pages:$folder", ordered.toString()).apply()
    }

    fun restoreOrder(folder: Uri?, files: List<ComicFile>, preferences: android.content.SharedPreferences): List<ComicFile> {
        val sorted = sorted(files)
        if (folder == null) return sorted
        val saved = runCatching { JSONArray(preferences.getString("pages:$folder", "[]")) }.getOrNull() ?: return sorted
        val positions = (0 until saved.length()).associate { saved.optString(it) to it }
        return sorted.sortedBy { positions[it.uri.toString()] ?: Int.MAX_VALUE }
    }
}
