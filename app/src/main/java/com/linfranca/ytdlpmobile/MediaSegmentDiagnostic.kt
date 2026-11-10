package com.linfranca.ytdlpmobile

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

/** At most one small diagnostic fetch per job, after its first HTTP 429; never a second downloader. */
internal object MediaSegmentDiagnostic {
    fun inspect(info: String, cookieFile: String, failure: String, stopped: ()->Boolean, log: (String)->Unit): Long = try {
        inspectChecked(info,cookieFile,failure,stopped,log)
    } catch(e: Exception) {
        log("Media diagnostic unavailable; ${e.javaClass.simpleName}; URL/header values omitted")
        0L
    }
    private fun inspectChecked(info: String, cookieFile: String, failure: String, stopped: ()->Boolean, log: (String)->Unit): Long {
        val root=runCatching { JSONObject(info) }.getOrNull() ?: return 0
        val formats=root.optJSONArray("formats")
        val selected=Regex("format\\(s\\):\\s*([^\\r\\n]+)").find(failure)?.groupValues?.get(1).orEmpty().split('+').map(String::trim)
        val entries=if(formats==null) listOf(root) else (0 until formats.length()).map { formats.getJSONObject(it) }
        val f=entries.firstOrNull { it.optString("format_id") in selected } ?: entries.firstOrNull { it.optString("vcodec")!="none" } ?: entries.firstOrNull() ?: return 0
        val kind=if(f.optString("_app_first_segment_url").isNotBlank()) "first-segment" else "media-entry"
        var target=f.optString("_app_first_segment_url").ifBlank { f.optString("url") }
        if(target.isBlank()) return 0
        val original=URI(target)
        val h=f.optJSONObject("_app_first_segment_headers") ?: f.optJSONObject("http_headers") ?: JSONObject()
        val headers=h.keys().asSequence().associateWith { h.optString(it) }.filterKeys { it.lowercase() !in setOf("host","cookie","range","content-length","connection","accept-encoding") }
        val jar=MediaCookieJar();File(cookieFile).takeIf { cookieFile.isNotBlank() && it.isFile }?.let { jar.load(it.readText()) }
        if(!root.has("_app_cookie_jar")) h.keys().asSequence().firstOrNull { it.equals("cookie",true) }?.let { name ->
            f.optString("url").takeIf(String::isNotBlank)?.let { jar.observe(it,h.optString(name)) }
        }
        var retryAt=0L
        val deadline=System.nanoTime()+20_000_000_000L
        try {
            repeat(4) {
                if(stopped() || System.nanoTime()>deadline) return retryAt
                val u=URI(target);require(u.scheme in setOf("https","http") && u.host!=null && u.userInfo==null)
                val conn=u.toURL().openConnection() as HttpURLConnection
                conn.instanceFollowRedirects=false;conn.connectTimeout=5000;conn.readTimeout=5000
                headers.filterKeys { u.host==original.host || it.lowercase() !in setOf("authorization","proxy-authorization") }.forEach { (k,v)->conn.setRequestProperty(k,v) }
                val cookie=jar.header(target);if(cookie.isNotBlank()) conn.setRequestProperty("Cookie",cookie)
                try {
                    val code=conn.responseCode
                    jar.accept(target,conn.headerFields.entries.filter { it.key!=null }.associate { it.key!! to it.value })
                    retryAt=maxOf(retryAt,MediaRequestPolicy.retryAfter(conn.getHeaderField("Retry-After")))
                    val ref=headers.entries.firstOrNull { it.key.equals("referer",true) }?.value.orEmpty()
                    val refStyle=runCatching { URI(ref).let { r -> if(r.host==null) "none" else if(r.path in listOf("","/") && r.query==null) "origin" else "page" } }.getOrDefault("none")
                    val wait=if(retryAt>0) ((retryAt-System.currentTimeMillis()).coerceAtLeast(0)+999)/1000 else 0
                    val stream=if(code>=400) conn.errorStream else conn.inputStream
                    val bytes=stream?.use { input ->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                        while(out.size()<4096 && !stopped() && System.nanoTime()<deadline) { val n=input.read(buffer,0,minOf(buffer.size,4096-out.size()));if(n<0) break;out.write(buffer,0,n) }
                        out.toByteArray()
                    } ?: byteArrayOf()
                    val text=bytes.toString(Charsets.UTF_8).lowercase()
                    val markers=listOf("too many requests","rate limit","access denied","captcha","cloudflare").filter { text.contains(it) }.joinToString(",").ifBlank { "none" }
                    val type=conn.contentType.orEmpty().substringBefore(';').replace(Regex("[^A-Za-z0-9.+/-]"),"").take(80)
                    log("$kind diagnostic; host=${u.host}; HTTP=$code; type=$type; transport=Android-HTTP; selectionMatched=${f.optString("format_id") in selected}; referrer=$refStyle; cookiesPresent=${cookie.isNotBlank()}; originPresent=${headers.keys.any { it.equals("origin",true) }}; retryAfterSeconds=$wait; bodyMarkers=$markers; bytesInspected=${bytes.size}; URL/header/body values omitted")
                    if(code in 300..399) {
                        val next=u.resolve(conn.getHeaderField("Location") ?: return retryAt)
                        require(u.scheme!="https" || next.scheme=="https")
                        target=next.toString()
                    } else return retryAt
                } finally { conn.disconnect() }
            }
            return retryAt
        } catch(e: Exception) {
            log("first-segment diagnostic unavailable; ${e.javaClass.simpleName}; URL/header values omitted")
            return retryAt
        } finally {
            if(cookieFile.isNotBlank()) File(cookieFile).takeIf { it.isFile && DownloadSessionFiles.privateFile(it) }?.let { file ->
                runCatching { file.writeText(jar.netscape()) }.onFailure { log("Diagnostic cookie persistence unavailable; ${it.javaClass.simpleName}") }
            }
        }
    }
}
