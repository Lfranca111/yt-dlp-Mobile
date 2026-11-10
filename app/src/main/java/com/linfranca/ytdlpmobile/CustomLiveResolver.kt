package com.linfranca.ytdlpmobile

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Fresh verification only. Nothing from this playback inspection is exported into a recipe. */
internal object CustomLiveResolver {
    fun resolve(c: Context,j: JSONObject,html: String?,stopped: ()->Boolean,beforeRender: ()->Unit = {},requireAdvancing: Boolean=false,skipExtractor: Boolean=false): MonitoringResult {
        fun active() { if(stopped()) throw java.util.concurrent.CancellationException("Custom live check canceled") }
        active()
        val trace=MonitoringTrace(c,"live-playback")
        trace.rule(j.optString("diagnosticRule").ifBlank { j.getString("id") })
        trace.event("destination","location=${j.optString("liveLocation","page")}; requireAdvancing=$requireAdvancing; skipExtractor=$skipExtractor; sessionMode=${j.optString("browserMode","mobile")}")
        var index=html ?: runCatching { CustomSources.fetch(c,j,stopped) }.getOrDefault("")
        var state=CustomLivePolicy.state(j,index)
        if(state.offline) return MonitoringResult(offline=true,detail=state.detail)
        if(state.target==null && j.optString("liveLocation","page")=="linked" && !CustomLivePolicy.followRequired(j,index)) {
            beforeRender()
            val rendered=MonitoringPageRenderer.render(c,j,stopped,validation={ snapshot ->
                val result=CustomLivePolicy.state(j,snapshot.html,snapshot.url)
                if(result.offline) listOf("offline") else result.target?.let { listOf(it) } ?: if(CustomLivePolicy.followRequired(j,snapshot.html)) listOf("live-button") else emptyList()
            })
            index=rendered.html;state=CustomLivePolicy.state(j,index,rendered.url)
        }
        if(state.offline) return MonitoringResult(offline=true,detail=state.detail)
        val follow=CustomLivePolicy.followRequired(j,index)
        var page=state.target ?: if(follow) j.getString("url") else return MonitoringResult(detail=state.detail)
        var item=MonitoringItem(CustomLivePolicy.identity(page),page,j.getString("name"))
        // Reuse the existing extractor verifier when the website supplies explicit live metadata.
        val extracted=runCatching { check(!skipExtractor) { "Extractor already attempted; verifying browser playback" };check(!follow) { "Live button must first be followed" };MonitoringProbe(c,stopped).verify(item,j.optString("browserMode","mobile"),browserFallback=false) }
        active()
        extracted.exceptionOrNull()?.let { error ->
            if(MonitoringFailure.kind(MonitoringStore.error(error)) in setOf("dns","network","rate")) throw error
        }
        if(extracted.isSuccess) {
            MonitoringStore.log(c,"custom live verified; source=${j.getString("id")}; method=existing live extractor; destination selected; media values omitted")
            return extracted.getOrThrow()
        }
        trace.event("browser-fallback","extractorSucceeded=false; followButton=$follow; freshPlayerRequired=true")
        beforeRender();active()
        val target=JSONObject(j.toString()).put("url",page).put("mode","live")
        if(requireAdvancing) target.put("requireAdvancing",true)
        var lastBrowserValidation=""
        val rendered=MonitoringPageRenderer.render(c,target,stopped,validation={ snapshot ->
            val p=runCatching { JSONObject(snapshot.player) }.getOrDefault(JSONObject())
            if(p.optBoolean("drm")) throw BrowserCheckRequired("The player reports media keys; protected playback requires manual review")
            val liveIndicator=state.online || CustomLivePolicy.state(j,snapshot.html,snapshot.url).online || YouTubeLivePolicy.advertised(page,p)!=null
            val stage=MonitoringPlaybackEvidence.stage(p,snapshot.media.any { MediaUrlClassifier.isContinuousManifest(it.url) },liveIndicator)
            if(stage!=lastBrowserValidation) {
                lastBrowserValidation=stage
                trace.event("browser-validation","stage=$stage; explicitLiveIndicator=$liveIndicator; mediaCandidates=${snapshot.media.size}; advancingPlaylistAndAudioCheckedNext=true")
            }
            if(stage=="ready" && (!j.optBoolean("requireYouTubeIdentity") || YouTubeLivePolicy.advertised(page,p)!=null))
                listOf("playing-media") else emptyList()
        },livePlayback=true,followSelector=if(follow) j.getString("liveLinkSelector") else null)
        if(follow) {
            page=CustomLivePolicy.safeTarget(j.getString("url"),rendered.url) ?: throw BrowserCheckRequired("Live button left the configured site")
            item=item.copy(url=page,id=CustomLivePolicy.identity(page))
        }
        active()
        trace.event("stream-selection","freshCandidates=${rendered.media.size}; browserVerified=true")
        val player=JSONObject(rendered.player)
        check(!player.optBoolean("ad") && !player.optBoolean("drm")) { "Wait for ads or protected playback to finish; live state unknown" }
        val direct=player.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        val advertisedMaster=YouTubeLivePolicy.advertised(page,player)
        val primary=rendered.media.firstOrNull { it.url==advertisedMaster } ?: rendered.media.filter { MediaUrlClassifier.isContinuousManifest(it.url) && MediaUrlClassifier.streamKind(it.url)!=MediaUrlClassifier.StreamKind.AUDIO }
            .maxByOrNull { MediaUrlClassifier.score(it.url,it.url in direct,it.at) }
            ?: throw BrowserCheckRequired("No fresh continuous HLS/DASH source was found; use manual Browser Assist")
        if(j.optBoolean("requireYouTubeIdentity"))
            check(primary.url==advertisedMaster) { "Fresh player did not verify the exact YouTube broadcast" }
        if(advertisedMaster!=null && primary.url==advertisedMaster) {
            trace.event("youtube-hls","sameVideo=true; verifyingMasterAudioAndAdvancement=true; URL omitted")
            return YouTubeLivePlayback.verify(c,item,primary.url,j.optString("browserMode","mobile"),primary.headers,stopped,
                j.optString("diagnosticRule").ifBlank { j.getString("id") })
        }
        val access=access(c,j,page,primary.url,primary)
        val master=read(primary.url,access,stopped)
        val inspection=StreamManifestAudio.inspect(primary.url,master)
        val video=inspection.videoUrl ?: primary.url
        var audio=inspection.audioUrl.orEmpty()
        val videoAccess=access(c,j,page,video,rendered.media.find { it.url==video } ?: primary)
        var first=if(video==primary.url) master else read(video,videoAccess,stopped)
        if(first.trimStart().startsWith("#EXTM3U") && first.contains("#EXT-X-STREAM-INF:")) {
            // A master playlist is not live evidence. Inspect one actual rendition as well.
            val lines=first.lineSequence().map { it.trim() }.toList()
            val variant=lines.indices.firstOrNull { lines[it].startsWith("#EXT-X-STREAM-INF:") }?.let { n -> lines.drop(n+1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } }
                ?: error("The live master has no media rendition")
            val rendition=URI(video).resolve(variant).toString()
            first=read(rendition,access(c,j,page,rendition,primary),stopped)
            // Keep the master as the recording URL so its existing audio/rendition handling is retained.
            return verified(c,target,item,rendered,state,primary,video,audio,inspection,first,rendition,stopped)
        }
        return verified(c,target,item,rendered,state,primary,video,audio,inspection,first,video,stopped)
    }
    private fun verified(c: Context,j: JSONObject,item: MonitoringItem,rendered: MonitoringPageRenderer.Snapshot,state: CustomLivePolicy.State,
                         primary: MonitoringPageRenderer.Captured,video: String,initialAudio: String,inspection: StreamManifestAudio.Inspection,
                         first: String,evidenceUrl: String,stopped: ()->Boolean): MonitoringResult {
        val page=item.url
        val explicit=!j.optBoolean("requireAdvancing") && (state.online || CustomLivePolicy.state(JSONObject(j.toString()).put("liveLocation","page"),rendered.html,rendered.url).online)
        if(CustomLivePolicy.ended(first)) throw BrowserCheckRequired("Playlist reports completed/VOD media; no live recording was queued")
        var second=first
        if(!explicit) {
            val duration=Regex("#EXT-X-TARGETDURATION:(\\d+)").find(first)?.groupValues?.get(1)?.toLongOrNull() ?: 3L
            repeat(2) {
                val until=android.os.SystemClock.elapsedRealtime()+duration.coerceIn(2,8)*1000
                while(android.os.SystemClock.elapsedRealtime()<until) {
                    if(stopped()) throw java.util.concurrent.CancellationException("Custom live verification canceled")
                    Thread.sleep(150)
                }
                second=read(evidenceUrl,access(c,j,page,evidenceUrl,primary),stopped)
                if(CustomLivePolicy.manifestLive(first,second,false)) return finishVerification(c,j,item,rendered,primary,video,initialAudio,inspection,first,stopped)
            }
        }
        check(CustomLivePolicy.manifestLive(first,second,explicit)) { "Live state unknown: no advancing live playlist or matching live indicator; no recording queued" }
        return finishVerification(c,j,item,rendered,primary,video,initialAudio,inspection,first,stopped)
    }
    private fun finishVerification(c: Context,j: JSONObject,item: MonitoringItem,snapshot: MonitoringPageRenderer.Snapshot,
                                   primary: MonitoringPageRenderer.Captured,video: String,initialAudio: String,inspection: StreamManifestAudio.Inspection,
                                   body: String,stopped: ()->Boolean): MonitoringResult {
        var audio=initialAudio
        if(audio.isBlank() && inspection.status!=StreamManifestAudio.Status.INCLUDED) {
            audio=snapshot.media.filter { MediaUrlClassifier.streamKind(it.url)==MediaUrlClassifier.StreamKind.AUDIO && DownloadMediaPolicy.matching(video,it.url) }
                .maxByOrNull { it.at }?.url.orEmpty()
        }
        val confirmed=inspection.status==StreamManifestAudio.Status.INCLUDED || audio.isNotBlank() ||
            (CustomLivePolicy.dynamicDash(body) && Regex("(?i)(?:contentType|mimeType)\\s*=\\s*['\"]audio(?:/[^'\"]+)?['\"]").containsMatchIn(body))
        check(confirmed || j.optBoolean("liveAllowUnconfirmedAudio")) { "Live video found, but matching audio is unconfirmed. Test with manual recording or explicitly allow unconfirmed audio in live setup." }
        if(audio.isNotBlank()) read(audio,access(c,j,item.url,audio,snapshot.media.find { it.url==audio } ?: primary),stopped)
        MonitoringStore.log(c,"custom live verified; source=${j.getString("id")}; method=browser HLS/DASH; paired=${audio.isNotBlank()}; audioConfirmed=$confirmed; addresses and headers omitted")
        return MonitoringResult(live=item,detail="Live destination and fresh HLS/DASH playback verified; audio ${if(confirmed) "confirmed" else "unconfirmed (explicitly allowed)"}",
            video=video,audio=audio,videoHeaders=access(c,j,item.url,video,snapshot.media.find { it.url==video } ?: primary),
            audioHeaders=if(audio.isBlank()) emptyMap() else access(c,j,item.url,audio,snapshot.media.find { it.url==audio } ?: primary))
    }
    private fun access(c: Context,j: JSONObject,page: String,url: String,captured: MonitoringPageRenderer.Captured): Map<String,String> {
        val sameOrigin=runCatching { URI(captured.url).authority==URI(url).authority && URI(captured.url).scheme==URI(url).scheme }.getOrDefault(false)
        val headers=captured.headers.entries.filter { it.key.lowercase() !in setOf("cookie","authorization","host","range") }.associate { it.key.lowercase() to it.value }.toMutableMap()
        headers["user-agent"]=headers.entries.find { it.key.equals("user-agent",true) }?.value ?: MonitoringSession.agent(j.optString("browserMode","mobile"),"Mozilla/5.0")
        headers.putIfAbsent("referer",page)
        val cookie=CookieManager.getInstance().getCookie(url).orEmpty().ifBlank { if(sameOrigin) captured.headers.entries.find { it.key.equals("cookie",true) }?.value.orEmpty() else "" }
        if(cookie.isNotBlank()) headers["cookie"]=cookie
        return headers
    }
    private fun read(url: String,headers: Map<String,String>,stopped: ()->Boolean): String {
        if(stopped()) throw java.util.concurrent.CancellationException("Live request canceled")
        val u=URI(url);require(u.scheme in setOf("https","http") && u.userInfo==null) { "Unsupported stream address" }
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=8000;connection.readTimeout=8000;connection.instanceFollowRedirects=false
        headers.forEach { (k,v) -> connection.setRequestProperty(k,v) }
        try {
            check(connection.responseCode==200) { "Live manifest returned HTTP ${connection.responseCode}; access failure cause unconfirmed; fresh detection or browser review may be required" }
            val bytes=java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer=ByteArray(8192)
                while(true) {
                    if(stopped()) throw java.util.concurrent.CancellationException("Live request canceled")
                    val count=input.read(buffer);if(count<0) break;bytes.write(buffer,0,count)
                    check(bytes.size()<=262144) { "Live manifest exceeds inspection limit" }
                }
            }
            return bytes.toString("UTF-8")
        } finally { connection.disconnect() }
    }
}
