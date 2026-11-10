package com.linfranca.ytdlpmobile

import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException

/** Bounded content inspection shared by browser and dedicated download preparation. */
internal class VerifiedMediaAccess(private val referer: String,private val agent: String,
    private val extra: (String)->Map<String,String> = { emptyMap() },private val stopped: ()->Boolean = { false },
    private val session: MediaCookieJar = MediaCookieJar(),budgetMs: Long=90000,
    private val allowedUrl: (String)->Boolean = { true }) {
    data class Content(val url: String,val text: String,val hls: Boolean,val video: Boolean,val ext: String="")
    private val deadline=System.nanoTime()+budgetMs.coerceIn(1,90000)*1_000_000L
    private var requests=0
    fun active() { if(stopped()) throw CancellationException("Media inspection canceled");check(System.nanoTime()<deadline) { "Media inspection timed out" } }
    fun headers(url: String): Map<String,String> {
        check(allowedUrl(url)) { "Media address outside this provider scope" }
        val h=extra(url).filterKeys { it.lowercase() !in setOf("range","host","content-length","accept-encoding","connection") }.toMutableMap()
        if(h.keys.none { it.equals("user-agent",true) }) h["User-Agent"]=agent
        if(h.keys.none { it.equals("referer",true) }) MediaRequestPolicy.referrer(referer,url).takeIf(String::isNotBlank)?.let { h["Referer"]=it }
        if(h.keys.none { it.equals("cookie",true) }) CookieManager.getInstance().getCookie(url)?.takeIf(String::isNotBlank)?.let { h["Cookie"]=it }
        session.observe(url,h.entries.firstOrNull { it.key.equals("cookie",true) }?.value.orEmpty())
        h.keys.filter { it.equals("cookie",true) }.toList().forEach { h.remove(it) }
        session.header(url).takeIf(String::isNotBlank)?.let { h["Cookie"]=it }
        return h
    }
    fun read(raw: String, useRange: Boolean = true): Content {
        check(++requests<=32) { "Media inspection request limit reached" }
        var target=raw;val firstHost=URI(raw).host
        repeat(5) {
            active();check(allowedUrl(target)) { "Media redirect outside this provider scope" };val uri=URI(target);require(uri.scheme in setOf("https","http") && !uri.host.isNullOrBlank() && uri.userInfo==null) { "Invalid media address" }
            val conn=URI(target).toURL().openConnection() as HttpURLConnection
            val timeout=((deadline-System.nanoTime())/1_000_000L).coerceIn(1,8000).toInt()
            conn.instanceFollowRedirects=false;conn.connectTimeout=timeout;conn.readTimeout=timeout
            headers(target).filterKeys { URI(target).host==firstHost || it.lowercase() !in setOf("authorization","proxy-authorization") }.forEach { (k,v)->conn.setRequestProperty(k,v) }
            if (useRange) conn.setRequestProperty("Range","bytes=0-2097151")
            try {
                val code=conn.responseCode
                session.accept(target,conn.headerFields.entries.filter { it.key!=null }.associate { it.key!! to it.value })
                if(code in 300..399) {
                    val next=URI(target).resolve(conn.getHeaderField("Location") ?: error("Media redirect has no destination")).toString()
                    check(URI(target).scheme!="https" || URI(next).scheme=="https") { "Insecure media redirect" };target=next
                } else {
                    check(code in setOf(200,206)) { "Media inspection HTTP $code" }
                    val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    conn.inputStream.use { input ->
                        while(bytes.size()<4096) { active();val n=input.read(buffer,0,minOf(4096-bytes.size(),buffer.size));if(n<0) break;bytes.write(buffer,0,n) }
                        val prefix=bytes.toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
                        val hls=prefix.startsWith("#EXTM3U")
                        if(hls) {
                            while(true) { active();val n=input.read(buffer);if(n<0) break;bytes.write(buffer,0,n);check(bytes.size()<=2_000_000) { "Playlist exceeds inspection limit" } }
                            val range=conn.getHeaderField("Content-Range").orEmpty()
                            val total=range.substringAfterLast('/').toLongOrNull()
                            check(total==null || total<=bytes.size()) { "Playlist response was truncated" }
                            return Content(target,bytes.toString("UTF-8"),true,false)
                        }
                        val b=bytes.toByteArray()
                        val mp4=b.size>=8 && String(b,4,4,Charsets.US_ASCII)=="ftyp"
                        val webm=b.size>=4 && b[0]==0x1a.toByte() && b[1]==0x45.toByte() && b[2]==0xdf.toByte() && b[3]==0xa3.toByte()
                        val type=conn.contentType.orEmpty().substringBefore(';').lowercase()
                        val video=(mp4 || webm || type in setOf("video/mp4","video/webm")) && b.isNotEmpty() && !prefix.startsWith('<') && !prefix.startsWith('{')
                        return Content(target,"",false,video,if(webm || type=="video/webm") "webm" else "mp4")
                    }
                }
            } finally { conn.disconnect() }
        }
        error("Too many media redirects")
    }
    fun plan(raw: String,page: String,title: String,expected: Double=0.0,allowAudioOnly: Boolean=false): JSONObject {
        val response=read(raw)
        val info=if(response.hls) HlsDownloadPlan.build(response.url,response.text,page,title,::headers,allowAudioOnly) { read(it).also { r -> check(r.hls) { "A playlist rendition returned non-HLS content" } }.text }
        else {
        check(response.video) { "Selected response is not a verified playlist or video" }
        JSONObject().put("_app_audio_only",allowAudioOnly).put("id","verified-video").put("title",title.ifBlank { "Browser selected video" }).put("webpage_url",page)
            .put("url",response.url).put("ext",response.ext)
            .put("protocol",URI(response.url).scheme).put("http_headers",JSONObject(headers(response.url)))
            .put("_app_expected_duration",if(expected.isFinite() && expected>0) expected else 0.0)
        }
        return completePlan(info)
    }
    fun completePlan(info: JSONObject): JSONObject {
        val formats=info.optJSONArray("formats")
        val entries=if(formats==null) listOf(info) else (0 until formats.length()).map { formats.getJSONObject(it) }
        for(f in entries) {
            val first=f.optString("_app_first_segment_url").ifBlank { f.optString("url") }
            if(first.isNotBlank()) f.put("_app_first_segment_headers",JSONObject(headers(first)).apply { keys().asSequence().filter { it.equals("cookie",true) }.toList().forEach { remove(it) } })
            f.optJSONObject("http_headers")?.let { h -> h.keys().asSequence().filter { it.equals("cookie",true) }.toList().forEach { h.remove(it) } }
        }
        return info.put("_app_cookie_jar",session.netscape())
    }
}
