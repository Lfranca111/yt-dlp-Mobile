package com.linfranca.ytdlpmobile

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One-use activity result. Keep full capture plans and cookies off the Binder result. */
internal object BrowserDownloadBatch {
    const val EXTRA_TOKEN = "browser_download_batch_token"
    const val EXTRA_SOURCE_ID = "browser_download_batch_source_id"
    private const val MAX_BYTES = 24L * 1024 * 1024
    private const val MAX_AGE_MS = 60L * 60 * 1000
    private val tokenPattern = Regex("[0-9a-f-]{36}")
    private val sourcePattern = Regex("[0-9a-f]{64}")

    data class Source(val url: String, val info: JSONObject, val diagnostics: String)
    data class Batch(val page: String, val agent: String, val sources: List<Source>)
    data class Request(val intent: Intent, val title: String)

    private fun directory(activity: Activity) = File(activity.cacheDir, "browser-download-batches")
    private fun file(activity: Activity, token: String): File {
        require(tokenPattern.matches(token))
        return File(directory(activity), "$token.json")
    }

    fun write(activity: Activity, batch: Batch): Intent {
        require(batch.sources.size in 2..10)
        val dir = directory(activity)
        dir.mkdirs()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
        val entries = JSONArray()
        batch.sources.forEach {
            require(it.info.toString().length < 1_000_000) { "A source's media information is too large." }
            entries.put(JSONObject().put("url", it.url).put("info", it.info).put("diagnostics", it.diagnostics))
        }
        val bytes = JSONObject().put("page", batch.page).put("agent", batch.agent)
            .put("sources", entries).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_BYTES)
        val token = UUID.randomUUID().toString()
        val destination = file(activity, token)
        try { destination.writeBytes(bytes) } catch (e: Exception) { destination.delete(); throw e }
        return Intent().putExtra(EXTRA_TOKEN, token)
    }

    fun discard(activity: Activity, data: Intent) {
        data.getStringExtra(EXTRA_TOKEN)?.takeIf(tokenPattern::matches)?.let { file(activity, it).delete() }
    }

    fun consume(activity: Activity, data: Intent, accept: (Batch) -> Unit): Boolean {
        val token = data.getStringExtra(EXTRA_TOKEN) ?: return false
        Thread {
            val result = runCatching {
                val staged = file(activity, token)
                val json = synchronized(this) {
                    require(staged.length() in 1L..MAX_BYTES &&
                        System.currentTimeMillis() - staged.lastModified() <= MAX_AGE_MS)
                    try { JSONObject(staged.readText(Charsets.UTF_8)) } finally { staged.delete() }
                }
                val entries = json.getJSONArray("sources")
                require(entries.length() in 2..10)
                val sources = (0 until entries.length()).map { index ->
                    val item = entries.getJSONObject(index)
                    Source(item.getString("url"), item.getJSONObject("info"), item.getString("diagnostics"))
                }
                Batch(json.getString("page"), json.getString("agent"), sources)
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    result.onSuccess(accept).onFailure {
                        Toast.makeText(activity, "Could not open the selected sources. Detect them again.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
        return true
    }

    fun sourceId(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun identitySuffix(request: Intent): String {
        val value = request.getStringExtra(EXTRA_SOURCE_ID).orEmpty()
        return if (sourcePattern.matches(value)) ":browser-source:$value" else ""
    }

    fun request(activity: Activity, batch: Batch, source: Source): Intent =
        Intent(activity, DownloadService::class.java).setAction(DownloadService.ACTION_DOWNLOAD)
            .putExtra(DownloadService.EXTRA_URL, source.url)
            .putExtra(DownloadService.EXTRA_PRESET, DownloadService.PRESET_BEST)
            .putExtra(DownloadService.EXTRA_BROWSER_CAPTURED, true)
            .putExtra(DownloadService.EXTRA_REFERER, batch.page)
            .putExtra(DownloadService.EXTRA_USER_AGENT, batch.agent)
            .putExtra(DownloadService.EXTRA_DOWNLOAD_INFO_JSON, source.info.toString())
            .putExtra(DownloadService.EXTRA_DOWNLOAD_DIAGNOSTICS, source.diagnostics)

    fun enqueue(activity: Activity, batch: Batch, monitor: String = "",
                build: (Source) -> Intent? = { request(activity, batch, it) }) {
        // Build intents on the UI thread so Main can read the selected download settings.
        val requests = batch.sources.mapNotNull { source ->
            build(source)?.let {
                it.putExtra(EXTRA_SOURCE_ID, sourceId(source.url))
                Request(it, source.info.optString("title").ifBlank { "Browser-assisted download" })
            }
        }
        if (requests.isEmpty()) return
        val context = activity.applicationContext
        Thread {
            var queued = 0
            var existing = 0
            var failed = batch.sources.size - requests.size
            for (request in requests) {
                try {
                    if (TransferQueue.submitBrowserBatchSource(context, request.intent,
                            request.title, if (queued == 0) monitor else "")) queued++
                    else existing++
                } catch (_: Exception) { failed++ }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val summary = buildString {
                    append("Added $queued ${if (queued == 1) "source" else "sources"} to Queue & history.")
                    if (existing > 0) append(" $existing already queued or saved.")
                    if (failed > 0) append(" $failed could not be added.")
                }
                Toast.makeText(activity, summary, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
