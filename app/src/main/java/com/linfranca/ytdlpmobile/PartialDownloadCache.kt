package com.linfranca.ytdlpmobile

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Private per-job cache; normal downloads only. Browser Assist never enables this plugin. */
internal object PartialDownloadCache {
    const val DIRECTORY=".download-cache"
    fun privateFile(file: File)=generateSequence(file) { it.parentFile }.any { it.name==DIRECTORY }
    fun configure(c: Context,folder: File,url: String,preset: String,args: List<String>,request: YoutubeDLRequest,allowParallel: Boolean=false) {
        val root=File(folder,DIRECTORY).apply { mkdirs() }
        // The saved job settings include episode, server and SUB/DUB selection. Never share jobs.
        val binding=MessageDigest.getInstance("SHA-256").digest("$url\n$preset\n$args".toByteArray()).joinToString("") { "%02x".format(it) }
        val config=File(root,"config.json")
        if(config.isFile) check(JSONObject(config.readText()).optString("binding")==binding) { "Download selection changed; use a new job to keep retained media separate" }
        else config.writeText(JSONObject().put("binding",binding).toString())
        // yt-dlp removes an existing final filename before invoking its downloader.
        // Preserve an assembled previous attempt BEFORE starting that process.
        val existing=mediaFiles(folder)
        if(existing.isNotEmpty()) {
            val history=File(root,"recovery/"+java.util.UUID.randomUUID()).apply { check(mkdirs()) }
            File(folder,"Partial-download-report.txt").takeIf { it.isFile }?.let { check(it.renameTo(File(history,it.name))) }
            existing.forEach { check(it.renameTo(File(history,it.name))) { "Could not preserve previous partial media; retry stopped safely" } }
        }
        val plugins=File(root,"plugins")
        val module=File(plugins,"app/yt_dlp_plugins/postprocessor/app_partial_cache.py")
        module.parentFile!!.mkdirs()
        c.assets.open("partial_download_cache.py").use { input -> module.outputStream().use { input.copyTo(it) } }
        request.addCommands(listOf("--plugin-dirs",plugins.absolutePath,"--use-postprocessor","AppPartialCache","--force-overwrites","--fragment-retries","3","--skip-unavailable-fragments","--concurrent-fragments",if(allowParallel) "8" else "1"))
    }
    fun retainTrial(trial: File,destination: File,log: (String)->Unit) {
        val source=File(trial,"$DIRECTORY/objects")
        if(!source.isDirectory) return
        val target=File(destination,"$DIRECTORY/objects").apply { mkdirs() }
        var count=0
        source.listFiles().orEmpty().filter { it.extension=="bin" }.forEach { data ->
            val receipt=File(source,data.nameWithoutExtension+".json")
            if(receipt.isFile) {
                val info=runCatching { JSONObject(receipt.readText()) }.getOrNull()
                if(info!=null && info.optLong("bytes")==data.length() && info.optString("sha256")==fingerprint(data)) {
                    val out=File(target,data.name);val meta=File(target,receipt.name)
                    if(!out.exists()) { data.copyTo(out);receipt.copyTo(meta,overwrite=true);count++ }
                }
            }
        }
        log("h123 selected trial cache retained; verifiedRawFragments=$count; final assembly revalidates identities and checksums; sample output files not exported")
    }
    fun fingerprint(file: File): String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(262144);while(true) { val count=input.read(buffer);if(count<0) break;hash.update(buffer,0,count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun missing(folder: File): Int = File(folder,DIRECTORY).listFiles().orEmpty().filter { it.name.startsWith("track-") && it.extension=="json" }.sumOf {
        runCatching { JSONObject(it.readText()).optJSONArray("missing")?.length() ?: 0 }.getOrDefault(0)
    }
    fun gapReport(folder: File): String = buildString {
        append("Partial download: unavailable fragments were skipped after retries. Playback may contain gaps. Retained source data can be retried from Queue & history.\n")
        File(folder,DIRECTORY).listFiles().orEmpty().filter { it.name.startsWith("track-") && it.extension=="json" }.forEach { file ->
            runCatching { val state=JSONObject(file.readText());append("Track ${file.nameWithoutExtension.take(18)}; skipped fragment indices: ${state.optJSONArray("missing")}; reached end=${state.optBoolean("complete")}\n") }
        }
    }
    fun mediaFiles(folder: File): List<File> = folder.walkTopDown().filter { it.isFile && !privateFile(it) && !DownloadSessionFiles.privateFile(it) && !it.name.contains(Regex("-Frag\\d+")) && it.extension.lowercase() in setOf("mp4","mkv","webm","ts","m4a","mp3","opus","ogg") }.toList()
    fun recoverable(folder: File): List<File> = (folder.listFiles().orEmpty().asSequence() + File(folder,"$DIRECTORY/recovery").walkTopDown()).filter { it.isFile && it.length()>0 && !it.name.contains(Regex("-Frag\\d+")) && (it.extension.lowercase() in setOf("mp4","mkv","webm","ts","m4a","mp3","opus","ogg") || it.name.endsWith(".part") && it.name.removeSuffix(".part").substringAfterLast('.').lowercase() in setOf("mp4","mkv","webm","ts","m4a","mp3","opus","ogg")) }.toList()
}
