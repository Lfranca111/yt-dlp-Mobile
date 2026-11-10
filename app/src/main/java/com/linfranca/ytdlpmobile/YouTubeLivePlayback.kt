package com.linfranca.ytdlpmobile

import android.content.Context
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

/** Fresh page/master evidence only. Never stores signed media addresses in monitoring rules. */
internal object YouTubeLivePlayback {
    fun verify(c: Context,item: MonitoringItem,master: String,mode: String,provided: Map<String,String>,
               stopped: ()->Boolean,ruleId: String="none"): MonitoringResult {
        check(YouTubeLivePolicy.videoId(item.url)!=null) { "A specific YouTube live video is required" }
        val trace=MonitoringTrace(c,"youtube-live-hls").apply { rule(ruleId) }
        fun active() { if(stopped()) throw java.util.concurrent.CancellationException("YouTube live verification canceled") }
        fun headers(url: String): Map<String,String> {
            val h=provided.filterKeys { it.lowercase() in setOf("user-agent","referer","accept","accept-language") }
                .mapKeys { it.key.lowercase() }.toMutableMap()
            h.putIfAbsent("user-agent",MonitoringSession.requestAgent(c,mode))
            h["referer"]=item.url
            MonitoringAccess.requestCookie(c,url).takeIf { it.isNotBlank() }?.let { h["cookie"]=it }
            return h
        }
        fun read(url: String): String {
            active();check(YouTubeLivePolicy.manifest(url)) { "YouTube HLS address expired or changed; fresh detection needed" }
            val connection=URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout=8000;connection.readTimeout=8000;connection.instanceFollowRedirects=false
            headers(url).forEach { (key,value)->connection.setRequestProperty(key,value) }
            val started=SystemClock.elapsedRealtime()
            try {
                val status=connection.responseCode
                trace.event("manifest-response","HTTP=$status; elapsedMs=${SystemClock.elapsedRealtime()-started}; address/header values omitted")
                check(status==200) { "YouTube live manifest returned HTTP $status; access cause unconfirmed" }
                val bytes=java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val block=ByteArray(8192)
                    while(true) { active();val size=input.read(block);if(size<0) break;bytes.write(block,0,size);check(bytes.size()<=524288) { "YouTube manifest exceeds inspection limit" } }
                }
                return bytes.toString("UTF-8")
            } finally { connection.disconnect() }
        }
        val plan=trace.step("live-and-audio-verification","sameVideo=true; advancingTracksRequired=true") {
            YouTubeLiveHls.verify(master,::read) { seconds ->
                val until=SystemClock.elapsedRealtime()+seconds*1000
                while(SystemClock.elapsedRealtime()<until) { active();Thread.sleep(150) }
            }
        }
        trace.event("handoff","verifiedLive=true; audioConfirmed=true; separateAudio=${plan.audio.isNotBlank()}; addresses omitted")
        return MonitoringResult(live=item,detail="YouTube live HLS and audio verified",video=plan.video,audio=plan.audio,
            videoHeaders=headers(plan.video),audioHeaders=if(plan.audio.isBlank()) emptyMap() else headers(plan.audio))
    }
}
