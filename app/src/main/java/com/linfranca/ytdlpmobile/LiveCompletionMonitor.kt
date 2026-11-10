package com.linfranca.ytdlpmobile

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent positive-evidence observer. Never replaces capture URLs or interprets HTTP refusal as EOF. */
internal class LiveCompletionMonitor(
    private val c: Context,private val manifest: String,private val access: LiveHlsRelay.Access,
    private val page: String,private val pageAgent: String,private val pageCookies: String,
    private val log: (String)->Unit,private val stalled: ()->Boolean
): Closeable {
    private val executor=Executors.newSingleThreadScheduledExecutor()
    private val killer=Executors.newSingleThreadScheduledExecutor()
    private val evidence=LiveEndEvidence()
    private val process="end-check-${java.util.UUID.randomUUID()}"
    @Volatile private var closed=false
    @Volatile var reason: String?=null;private set
    @Volatile var broadcastChanged=false;private set
    private var broadcastId=""
    private var lastPlatform=0L
    private var failureDiagnostics=0
    fun start() { executor.scheduleWithFixedDelay({ if(!closed) runCatching { tick() }.onFailure {
        if(!closed) {
            log("completion_check_inconclusive; ${it.javaClass.simpleName}; recording/recovery continues")
            if(failureDiagnostics++<3) RecorderErrorDiagnostic.describe(it).take(6).forEach { detail -> log("completion_check_detail; $detail") }
        }
    } },0,15,TimeUnit.SECONDS) }
    private fun tick() {
        if(reason!=null) return
        // Observe only the exact original manifest. Redirects are inconclusive, and no credentials cross origins.
        val path=runCatching { URI(manifest).path.lowercase() }.getOrDefault("")
        if(path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            val conn=java.net.URL(manifest).openConnection() as HttpURLConnection
            conn.connectTimeout=5000;conn.readTimeout=5000;conn.instanceFollowRedirects=false
            if(access.agent.isNotBlank()) conn.setRequestProperty("User-Agent",access.agent)
            if(access.referer.isNotBlank()) conn.setRequestProperty("Referer",access.referer)
            if(access.cookie.isNotBlank()) conn.setRequestProperty("Cookie",access.cookie)
            access.extra.filterKeys { it.lowercase() in setOf("origin","accept","accept-language") }.forEach { (k,v) -> conn.setRequestProperty(k,v) }
            try {
                val status=conn.responseCode
                if(status==200) {
                    val bytes=conn.inputStream.use { input ->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(out.size()<=524288) { val n=input.read(buffer,0,minOf(buffer.size,524289-out.size()));if(n<0) break;out.write(buffer,0,n) }
                        out.toByteArray()
                    }
                    if(bytes.size<=524288) evidence.observe(bytes.toString(Charsets.UTF_8))?.let { confirm(it) }
                } else log("completion_manifest_check; HTTP=$status; no definite end; no redirect followed")
            } finally { conn.disconnect() }
        }
        if(closed || reason!=null || !page.startsWith("https://")) return
        val now=System.currentTimeMillis()
        // Capture a live broadcast identity once. Repeat only after a stall, at most once a minute.
        if(lastPlatform!=0L && (!stalled() || now-lastPlatform<60000)) return
        lastPlatform=now
        var jar: File?=null
        val timeout=killer.schedule({ runCatching { YoutubeDL.destroyProcessById(process) } },25,TimeUnit.SECONDS)
        try {
            val request=YoutubeDLRequest(page).addOption("--skip-download").addOption("--dump-single-json")
                .addOption("--no-playlist").addOption("--socket-timeout",8).addOption("--retries",0).addOption("--extractor-retries",0)
            if(pageAgent.isNotBlank()) request.addOption("--user-agent",pageAgent)
            // Only webpage cookies, never CDN cookies/headers.
            if(pageCookies.isNotBlank()) {
                jar=File(c.cacheDir,"end-check-${java.util.UUID.randomUUID()}.txt")
                jar.writeText(WebpageAccessDiagnostic.cookieFile(page,pageCookies));request.addOption("--cookies",jar.absolutePath)
            }
            val j=JSONObject(YoutubeDL.execute(request,process,false).out.trim())
            if(closed) return
            val id=j.optString("id");val status=j.optString("live_status")
            if(id.isNotBlank() && (j.optBoolean("is_live") || status=="is_live")) {
                if(broadcastId.isBlank()) {
                    val formats=j.optJSONArray("formats")
                    val urls=(0 until (formats?.length() ?: 0)).flatMap { i ->
                        val f=formats!!.optJSONObject(i);listOf(f?.optString("url").orEmpty(),f?.optString("manifest_url").orEmpty())
                    }+listOf(j.optString("url"),j.optString("manifest_url"))
                    if(urls.any { candidate -> candidate.isNotBlank() && WebpageAccessDiagnostic.sameOrigin(candidate,manifest) && RecordingRenewalTarget.sameRoute(candidate,manifest) }) {
                        broadcastId=id;log("completion_platform_identity_learned; active manifest route matched; values omitted")
                    } else log("completion_platform_identity_not_bound_to_active_manifest; end remains unknown")
                }
                else if(broadcastId!=id) { broadcastChanged=true;log("completion_platform_broadcast_changed; not substituted; end unknown") }
            } else if(LiveEndEvidence.broadcastEnded(broadcastId,id,status)) confirm("Platform explicitly ended the same previously verified broadcast")
            else log("completion_platform_check_inconclusive; no matching explicit ended broadcast; recovery retained")
        } finally { timeout.cancel(true);jar?.delete() }
    }
    private fun confirm(value: String) { if(!closed) { reason=value;log("stream_end_confirmed; $value; waiting for recorder to drain buffered media") } }
    override fun close() {
        closed=true;executor.shutdownNow();killer.shutdownNow();runCatching { YoutubeDL.destroyProcessById(process) }
    }
}
