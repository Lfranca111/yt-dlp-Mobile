package com.linfranca.ytdlpmobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

internal object PostTextExport {
    enum class Format(val extension: String, val mime: String) {
        TXT("txt", "text/plain"), HTML("html", "text/html")
    }

    data class SavedPost(val uri: Uri, val name: String, val mime: String, val savedAt: Long) {
        val historyId: String get() = "post:$uri"
    }

    private const val HISTORY_LIMIT = 100
    private const val HISTORY_KEY = "recent"
    private fun historyPrefs(context: Context) =
        context.getSharedPreferences("post_export_history", Context.MODE_PRIVATE)

    @Synchronized
    fun history(context: Context): List<SavedPost> {
        val entries = runCatching { JSONArray(historyPrefs(context).getString(HISTORY_KEY, "[]").orEmpty()) }
            .getOrElse { JSONArray() }
        var migrated = false
        val posts = (0 until minOf(entries.length(), HISTORY_LIMIT)).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val uri = Uri.parse(entry.optString("uri"))
            val name = entry.optString("name")
            val mime = entry.optString("mime")
            if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY || name.isBlank() ||
                Format.entries.none { it.mime == mime }
            ) return@mapNotNull null
            val savedAt = if (entry.has("saved_at")) entry.optLong("saved_at") else {
                migrated = true
                // Version 1.0.79 recorded the URI but not the save date.
                runCatching {
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_ADDED), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getLong(0) * 1000L else 0L }
                }.getOrNull() ?: 0L
            }
            SavedPost(uri, name, mime, savedAt)
        }
        if (migrated) writeHistory(context, posts)
        return posts
    }

    @Synchronized
    private fun recordSaved(context: Context, post: SavedPost) {
        writeHistory(context, listOf(post) + history(context).filterNot { it.uri == post.uri })
    }

    private fun writeHistory(context: Context, posts: List<SavedPost>) {
        val entries = JSONArray()
        posts.take(HISTORY_LIMIT).forEach {
            entries.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name)
                .put("mime", it.mime).put("saved_at", it.savedAt))
        }
        historyPrefs(context).edit().putString(HISTORY_KEY, entries.toString()).apply()
    }

    fun save(context: Context, post: PostTextContent.Post, format: Format): String {
        val stem = post.title.replace(Regex("""[\p{Cntrl}/\\:*?"<>|]"""), "_").trim(' ', '.')
            .take(80).ifBlank { "Saved post" }
        val name = "$stem.${format.extension}"
        val bytes = (if (format == Format.TXT) post.text() else post.html()).toByteArray(Charsets.UTF_8)
        StorageGuard.destination(context, bytes.size.toLong())
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, format.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/yt-dlp Mobile")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Could not create the file.")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("Could not write the file.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0)
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        val savedName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: name
        recordSaved(context, SavedPost(uri, savedName, format.mime, System.currentTimeMillis()))
        return savedName
    }
}
