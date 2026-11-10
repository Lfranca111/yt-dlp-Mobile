package com.linfranca.ytdlpmobile

import android.content.Context
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

data class MonitoringItem(val id: String,val url: String,val title: String = "",val publishedAt: Long = 0,val thumbnail: String = "")
data class MonitoringResult(val items: List<MonitoringItem> = emptyList(), val live: MonitoringItem? = null,
    val offline: Boolean = false, val detail: String, val video: String = "",val audio: String = "", val videoHeaders: Map<String,String> = emptyMap(), val audioHeaders: Map<String,String> = emptyMap(), val previewOnly: Boolean = false)

class MonitoringProbe(private val c: Context, private val stopped: ()->Boolean = { false }) {
    private var requestMode="mobile"
    var diagnosticRule="none"
    var beforeBrowser: ()->Unit = {}
    private val trace=MonitoringTrace(c,"probe")
    private fun log(s: String) = MonitoringStore.log(c,s)
    fun check(r: MonitoringRule,live: Boolean): MonitoringResult {
        diagnosticRule=r.id
        trace.rule(r.id)
        return trace.step(if(live) "live-detection" else "upload-detection","handler=${MonitoringPolicy.handler(r.url)}; custom=${r.customSource.isNotBlank()}") { checkSource(r,live) }
    }
    private fun checkSource(r: MonitoringRule,live: Boolean): MonitoringResult {
        check(!stopped()) { "Check canceled" }
        if(r.customSource.isNotBlank()) return CustomSources.check(c,r,live,stopped)
        requestMode=MonitoringSession.mode(c,r.url)
        MonitoringAccess.observe(c,r.url,"detection",requestMode)
        // Both VK channel tabs represent the same creator. Select the URL
        // for this check without changing the rule the user saved.
        val vkRoot=CaptureSiteRoutes.vkLiveRoot(r.url)
        if(vkRoot!=null) {
            if(!live) return CaptureSiteHandlers.vkReplays(r.url,
                MonitoringSession.requestAgent(c,requestMode),{ page -> MonitoringAccess.requestCookie(c,page) },stopped,::log)
            captureAttempt("VK channel probe") { CaptureSiteHandlers.live(vkRoot,stopped,::log) }?.let { return it }
            log("VK Video Live: public DASH not verified; trying the normal extractor on the channel root")
        }
        val resolved=MonitoringLinks.resolve(c,vkRoot ?: r.url,stopped)
        val handler=MonitoringPolicy.handler(resolved)
        log("probe started; rule=${r.id}; handler=$handler; kind=${if(live) "live" else "uploads"}; imported cookie file present=${cookieFile().isFile}")
        if(WebsiteSourceResolver.site(resolved)!=null) {
            try { return WebsiteHandlerBridge.monitoring(c,r.copy(url=resolved),live,stopped) }
            catch(e: java.util.concurrent.CancellationException) { throw e }
            catch(e: Exception) {
                if(stopped()) throw java.util.concurrent.CancellationException("Monitoring check canceled")
                log("dedicated website listing unavailable; handler=$handler; category=${MonitoringFailure.kind(e.message)}; trying yt-dlp fallback; baseline retained until success")
            }
        }
        if(live && handler in setOf("Chaturbate","Stripchat","XHamsterLive","Picarto")) return room(r.copy(url=resolved),handler)
        if(CaptureMonitoringPolicy.useCapturedRoutes(
                r.customState.optString(CaptureMonitoringPolicy.RULE_BINDING),r.url,r.customSource)) {
            val captureSite=CaptureSiteRoutes.site(resolved)
            if(captureSite!=null && captureSite==CaptureSiteRoutes.site(r.url)) {
                val livePage=if(live) CaptureSiteRoutes.livePage(resolved) else null
                val uploads=if(!live) CaptureSiteRoutes.uploads(resolved) else null
                if(livePage!=null && vkRoot==null) return capturedLive(livePage,captureSite)
                if(uploads!=null) return capturedUploads(uploads,captureSite)
            }
        }
        try {
            val target=MonitoringPolicy.target(resolved,live)
            log("extractor request policy; handler=$handler; target host=${URI(target).host}; section=${if(live) "live" else "uploads"}; default extractor headers; URL values omitted")
            val info=extractListing(target)
            val entries=info.optJSONArray("entries")
            if(entries!=null) {
                val all=(0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
                if(live) {
                    val active=all.firstOrNull { it.optBoolean("is_live") || it.optString("live_status")=="is_live" }
                    if(active!=null) return MonitoringResult(live=item(active,target),detail="Live broadcast reported by extractor")
                    // Only an explicit upcoming/ended state is conclusive. Flat metadata can omit live flags.
                    if(all.isNotEmpty() && all.all { it.optString("live_status") in setOf("not_live","was_live","post_live","is_upcoming") || it.opt("is_live") is Boolean })
                        return MonitoringResult(offline=true,detail="No active live broadcast reported")
                    if(handler=="YouTube") return MonitoringResult(detail="No confirmed live broadcast in the latest 40 stream entries")
                } else {
                    val items=all.filter { it.optString("live_status") !in setOf("is_live","is_upcoming") && !it.optBoolean("is_live") }
                        .mapNotNull { item(it,target) }.distinctBy { it.id }
                    check(items.isNotEmpty()) { "No usable upload entries; baseline retained. Sign in or check channel link." }
                    return MonitoringResult(items=items,detail="${items.size} upload entries found; checking latest 40 entries")
                }
            }
            if(live) {
                val full=if(info.has("formats")) info else extract(target,false)
                if(full.optBoolean("is_live") || full.optString("live_status")=="is_live")
                    return MonitoringResult(live=item(full,target),detail="Live status confirmed by extractor")
                if(full.optString("live_status") in setOf("not_live","was_live","post_live","is_upcoming"))
                    return MonitoringResult(offline=true,detail="Extractor reports ${full.optString("live_status")}")
                return MonitoringResult(detail="Page extracted, but live state could not be determined")
            }
            // A single extracted video is not a channel listing; do not silently establish a false baseline.
            throw IllegalStateException("This link did not expose an upload listing. Try the creator's videos/profile page.")
        } catch(e: Exception) {
            if(live && MonitoringOfflinePolicy.confirmed(e.message.orEmpty()))
                return MonitoringResult(offline=true,detail="Extractor confirms channel is offline")
            log("extractor attempt failed; category=${MonitoringFailure.kind(e.message)}; ${MonitoringStore.error(e)}")
            if(!live && handler=="General fallback") return feed(resolved)
            throw e
        }
    }
    private fun <T> captureAttempt(stage: String, block: ()->T?): T? =
        try { block() } catch(e: java.util.concurrent.CancellationException) { throw e }
        catch(e: Exception) {
            if(stopped()) throw java.util.concurrent.CancellationException("Monitoring check canceled")
            log("Captured-site $stage unavailable; category=${e.javaClass.simpleName}; request values omitted")
            null
        }

    private fun capturedLive(url: String,site: CaptureSiteRoutes.Site): MonitoringResult {
        captureAttempt("live probe") { CaptureSiteHandlers.live(url,stopped,::log) }?.let { return it }
        val rumbleOwner=if(site==CaptureSiteRoutes.Site.RUMBLE) CaptureSiteRoutes.live(url)?.id?.takeIf { '/' in it } else null
        if(rumbleOwner!=null) {
            val entries=captureAttempt("Rumble creator live listing") { extractListing(url).optJSONArray("entries") }
            val candidates=(0 until (entries?.length() ?: 0)).mapNotNull { entries?.optJSONObject(it) }
                .filter { it.optString("live_status") !in setOf("not_live","was_live","post_live","is_upcoming") }
                .sortedByDescending { it.optBoolean("is_live") || it.optString("live_status")=="is_live" }
                .mapNotNull { row ->
                    val raw=row.optString("webpage_url").ifBlank { row.optString("url") }
                    if(raw.isBlank()) return@mapNotNull null
                    val link=runCatching { URI(url).resolve(raw).toString() }.getOrNull() ?: return@mapNotNull null
                    link.takeIf { CaptureSiteRoutes.uploadId(it,site)!=null }
                }.distinct().take(3)
            for(link in candidates) captureAttempt("Rumble creator broadcast") {
                CaptureSiteHandlers.live(link,stopped,::log,rumbleOwner)
            }?.let { return it }
            return MonitoringResult(detail="Rumble: no verified live broadcast for this creator; a direct live post may work")
        }
        if(site==CaptureSiteRoutes.Site.VK || site==CaptureSiteRoutes.Site.ODYSEE)
            return MonitoringResult(detail="${site.label}: live state unknown; no verified public stream")
        return try {
            val j=extract(url,false)
            if(j.optBoolean("is_live") || j.optString("live_status")=="is_live") {
                val id=if(site==CaptureSiteRoutes.Site.RUMBLE) CaptureSiteRoutes.uploadId(url,site).orEmpty() else j.optString("id")
                check(id.isNotBlank()) { "Live broadcast has no stable ID" }
                MonitoringResult(live=MonitoringItem(CaptureSiteHandlers.itemId(site,id),url,j.optString("title")),
                    detail="${site.label}: live broadcast confirmed by yt-dlp")
            } else if(site!=CaptureSiteRoutes.Site.RUMBLE && j.optString("live_status")=="not_live")
                MonitoringResult(offline=true,detail="${site.label}: extractor reports no active stream")
            else MonitoringResult(detail="${site.label}: no confirmed live stream")
        } catch(e: Exception) {
            if(stopped()) throw java.util.concurrent.CancellationException("Monitoring check canceled")
            if(site!=CaptureSiteRoutes.Site.RUMBLE && MonitoringOfflinePolicy.confirmed(e.message.orEmpty()))
                MonitoringResult(offline=true,detail="${site.label}: extractor confirms the channel is offline")
            else MonitoringResult(detail="${site.label}: live state unknown; check the channel or use Browser Assist")
        }
    }

    private fun capturedUploads(target: String,site: CaptureSiteRoutes.Site): MonitoringResult {
        captureAttempt("upload listing") { CaptureSiteHandlers.uploads(target,stopped,::log) }?.let { return it }
        val info=captureAttempt("extractor listing") { extractListing(target) }
        if(site==CaptureSiteRoutes.Site.RUMBLE) {
            val scoped=info?.let { RumbleMonitoring.fromExtractor(target,it) }.orEmpty()
            val visible=captureAttempt("Rumble creator cards") {
                CaptureSiteHandlers.rumbleUploads(target,info,MonitoringAccess.requestCookie(c,target),stopped,::log)
            }.orEmpty()
            val owned=(visible+scoped).distinctBy { it.id }.take(40)
            if(owned.isNotEmpty()) return MonitoringResult(items=owned,detail="Rumble: ${owned.size} verified creator uploads")
        }
        val entries=info?.optJSONArray("entries")
        val rows=(0 until (entries?.length() ?: 0)).mapNotNull { entries?.optJSONObject(it) }
        val candidates=rows.filter { it.optString("live_status") !in setOf("is_live","is_upcoming","post_live") && !it.optBoolean("is_live") }
            .mapNotNull { entry ->
                val raw=entry.optString("webpage_url").ifBlank { entry.optString("url") }
                if(raw.isBlank()) return@mapNotNull null
                val link=runCatching { URI(target).resolve(raw).toString() }.getOrNull() ?: return@mapNotNull null
                val id=CaptureSiteRoutes.uploadId(link,site) ?: return@mapNotNull null
                MonitoringItem(CaptureSiteHandlers.itemId(site,id),link,entry.optString("title"))
            }.distinctBy { it.id }.take(40)
        // Kick's channel extractor, plus the explicit owner path on every row, proves scope.
        // The other unverified listings may include recommendations or videos by a different creator.
        if(site==CaptureSiteRoutes.Site.KICK && info?.optString("extractor_key")=="KickVideos") {
            val owner=CaptureSiteRoutes.live(target)?.id.orEmpty()
            val owned=candidates.filter { URI(it.url).path.substringAfter('/').substringBefore('/').equals(owner,true) }
            if(owned.isNotEmpty()) return MonitoringResult(items=owned,detail="Kick: ${owned.size} verified channel recordings")
        }
        if(site==CaptureSiteRoutes.Site.NICO && info?.optString("extractor_key")=="NiconicoUser" &&
            info.optString("id")==URI(target).path.split('/').getOrNull(2) && candidates.isNotEmpty())
            return MonitoringResult(items=candidates,detail="Niconico: ${candidates.size} verified user uploads")
        if(site==CaptureSiteRoutes.Site.NG && info?.optString("extractor_key")=="NewgroundsUser" &&
            info.optString("id").equals(URI(target).host.substringBefore('.'),true) && candidates.isNotEmpty())
            return MonitoringResult(items=candidates,detail="Newgrounds: ${candidates.size} verified creator movies")
        val visible=if(candidates.isNotEmpty()) candidates else
            captureAttempt("page listing preview") { CaptureSiteHandlers.preview(target,site,stopped,::log) }.orEmpty()
        val titles=visible.take(3).map { it.title.take(100) }.filter { it.isNotBlank() }
        val preview=if(titles.isEmpty()) "No verified creator upload listing found."
            else "${visible.size} candidate videos: ${titles.joinToString("; ")}."
        return MonitoringResult(items=visible,detail="${site.label}: $preview Automatic queueing is paused for this listing.",previewOnly=true)
    }

    fun verify(item: MonitoringItem,browserMode: String=MonitoringSession.mode(c,item.url),browserFallback: Boolean=true): MonitoringResult {
        trace.rule(diagnosticRule)
        requestMode=browserMode
        MonitoringAccess.observe(c,item.url,"live-verification",browserMode)
        try { return trace.step("fresh-extractor-verification") { verifyExtracted(item) } }
        catch(e: Exception) {
            if(stopped()) throw java.util.concurrent.CancellationException("Live verification canceled")
            val kind=MonitoringFailure.kind(MonitoringStore.error(e))
            if(!browserFallback || !MonitoringBuiltinBrowser.eligible(item.url) || kind in setOf("dns","network","canceled","quota","background","rate") || MonitoringOfflinePolicy.confirmed(e.message.orEmpty())) throw e
            beforeBrowser()
            return trace.step("fresh-browser-verification","fallbackFrom=$kind; advancingManifestRequired=true; manualAgreements=true") {
                MonitoringBuiltinBrowser.verify(c,item,browserMode,stopped,diagnosticRule)
            }
        }
    }
    private fun verifyExtracted(item: MonitoringItem): MonitoringResult {
        val site=CaptureSiteRoutes.live(item.url)?.site
        val captured=item.id.startsWith("Capture:")
        if(captured) {
            check(CaptureMonitoringPolicy.isCaptureId(item.id,site)) { "Queued live source no longer matches the verified site" }
            val fresh=captureAttempt("fresh live source") { CaptureSiteHandlers.live(item.url,stopped,::log) }
            if(fresh!=null) {
                check(CaptureMonitoringPolicy.matchesQueuedSource(fresh.live?.id,item.id,site) && fresh.video.isNotBlank()) { "Queued broadcast has ended or changed; run a fresh monitoring check" }
                return if(site==CaptureSiteRoutes.Site.RUMBLE || site==CaptureSiteRoutes.Site.ODYSEE)
                    CaptureSiteHandlers.verifyLiveHls(fresh,item.url,site,stopped,::log) else fresh
            }
            check(site!=CaptureSiteRoutes.Site.VK && site!=CaptureSiteRoutes.Site.ODYSEE) { "Live source could not be reverified; try Browser Assist" }
        }
        val full=extract(item.url,false)
        if(site!=null && captured) {
            val id=if(site==CaptureSiteRoutes.Site.RUMBLE) CaptureSiteRoutes.uploadId(item.url,site).orEmpty() else full.optString("id")
            check(id.isNotBlank() && CaptureMonitoringPolicy.matchesQueuedSource(CaptureSiteHandlers.itemId(site,id),item.id,site)) { "Queued broadcast has ended or changed; run a fresh monitoring check" }
        }
        check(full.optBoolean("is_live") || full.optString("live_status")=="is_live") { "Fresh extraction did not confirm live playback" }
        val a=full.optJSONArray("formats") ?: error("No playable formats; Browser Assist needed")
        val formats=(0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("url").startsWith("https://") || it.optString("url").startsWith("http://") }
        YouTubeLivePolicy.videoId(item.url)?.let { expected ->
            check(full.optString("id")==expected) { "YouTube extraction returned another video; fresh monitoring required" }
            val complete=YouTubeLivePolicy.complete(formats)
            if(complete!=null) {
                log("YouTube live selection; completeHLS=true; videoAndAudioDeclared=true; incompleteHigherResolutionSkipped=true; values omitted")
                return MonitoringResult(live=item,detail="Fresh YouTube live HLS video/audio found",video=complete.getString("url"),videoHeaders=headers(complete,full))
            }
            val masters=YouTubeLivePolicy.masters(full,formats)
            trace.event("youtube-audio-recovery","completeHLS=false; masterCandidates=${masters.size}; manifestAudioGroupsRequired=true")
            for(master in masters) {
                try {
                    return YouTubeLivePlayback.verify(c,item,master,requestMode,headers(formats.firstOrNull { it.optString("manifest_url")==master } ?: full,full),stopped,diagnosticRule)
                } catch(e: java.util.concurrent.CancellationException) { throw e }
                catch(e: Exception) {
                    trace.failure("youtube-master",e)
                    if(stopped() || MonitoringFailure.kind(MonitoringStore.error(e)) in setOf("dns","network","rate")) throw e
                }
            }
            error("YouTube extractor has no complete HLS audio/video selection; checking fresh player-data manifest in browser")
        }
        val videos=formats.filter { it.optString("vcodec") !in setOf("none","") &&
            it.optString("protocol").startsWith("m3u8") }
        val video=videos.maxByOrNull { it.optInt("height")*1000+it.optDouble("tbr",0.0).toInt() }
            ?: error("No verified HLS video track; Browser Assist needed")
        val audioFormat=if(video.optString("acodec") !in setOf("none","")) null else
            formats.filter { it.optString("vcodec")=="none" && it.optString("acodec") !in setOf("none","") && it.optString("protocol").startsWith("m3u8") }
                .maxByOrNull { it.optDouble("abr",0.0) }
        val audio=audioFormat?.optString("url").orEmpty()
        check(video.optString("acodec") !in setOf("none","") || audio.isNotBlank()) { "No matching audio track; Browser Assist needed" }
        log("fresh live verification succeeded; extractor=${full.optString("extractor_key")}; paired=${audio.isNotBlank()}; media URLs omitted")
        return MonitoringResult(live=item,detail="Fresh HLS video/audio found",video=video.getString("url"),audio=audio,videoHeaders=headers(video,full),audioHeaders=audioFormat?.let { headers(it,full) }.orEmpty())
    }
    private fun headers(format: JSONObject,info: JSONObject): Map<String,String> {
        val j=format.optJSONObject("http_headers") ?: info.optJSONObject("http_headers") ?: return emptyMap()
        val allowed=setOf("user-agent","referer","origin","accept","accept-language","cookie")
        return j.keys().asSequence().filter { it.lowercase() in allowed }.associateWith { j.getString(it) }
    }
    private fun bounded(stream: java.io.InputStream,max: Int): ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(out.size()<max) { val n=stream.read(buffer,0,minOf(buffer.size,max-out.size()));if(n<0) break;out.write(buffer,0,n) }
        return out.toByteArray()
    }
    private fun cookieFile()=File(c.filesDir,"cookies/imported-cookies.txt")
    private fun extractListing(url: String): JSONObject {
        try { return extract(url,true) }
        catch(e: Exception) {
            if(stopped()) throw java.util.concurrent.CancellationException("Monitoring check canceled")
            val alternative=if(MonitoringFailure.kind(e.message)=="recognition") MonitoringLinkFallback.alternative(url) else null
            if(alternative==null) throw e
            log("extractor recovery attempt; verified host equivalent; path/query unchanged; default headers retained; values omitted")
            return try { extract(alternative,true).also { log("extractor recovery succeeded") } }
            catch(retry: Exception) { log("extractor recovery failed; category=${MonitoringFailure.kind(retry.message)}");throw retry }
        }
    }
    private fun extract(url: String,flat: Boolean): JSONObject {
        YoutubeDL.init(c.applicationContext)
        val process="monitor-probe-${UUID.randomUUID()}"
        val request=YoutubeDLRequest(url).addOption("--skip-download").addOption("--dump-single-json")
            .addOption("--socket-timeout",15).addOption("--retries",1)
            .addOption("--extractor-retries",1).addOption("--playlist-end",40)
        // Browser Mobile/Desktop mode never overrides a site extractor's own client identity.
        if(flat) request.addOption("--flat-playlist") else request.addOption("--no-playlist")
        val jar=MonitoringAccess.cookieJar(c,url)
        if(jar!=null) request.addOption("--cookies",jar.absolutePath)
        // The subprocess receives only a process-scoped cookie jar. No Cookie header is forwarded across domains.
        val deadline=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val timeout=deadline.schedule({ runCatching { YoutubeDL.destroyProcessById(process) } },90,java.util.concurrent.TimeUnit.SECONDS)
        val cancelWatch=deadline.scheduleAtFixedRate({
            if(stopped()) runCatching { YoutubeDL.destroyProcessById(process) }
        },250,250,java.util.concurrent.TimeUnit.MILLISECONDS)
        var lastError=""
        trace.event("extractor","event=start; flat=$flat; stderrSeparate=true; cookieJarAvailable=${jar!=null}")
        val extractionStarted=android.os.SystemClock.elapsedRealtime()
        try {
            val response=YoutubeDL.execute(request,process,false) { _,_,line ->
                if(stopped()) runCatching { YoutubeDL.destroyProcessById(process) }
                if(line.startsWith("ERROR:") || line.startsWith("WARNING:")) {
                    if(line.startsWith("ERROR:")) lastError=MonitoringStore.safe(line).take(1800)
                    log("extractor observation: $line")
                }
            }
            check(!stopped()) { "Check canceled" }
            response.err.lineSequence().filter { it.startsWith("WARNING:") || it.startsWith("ERROR:") }.take(8)
                .forEach { line -> RecorderErrorDiagnostic.safeLine(line,1200)?.let { trace.event("extractor-stderr",it) } }
            trace.event("extractor","event=complete; flat=$flat; extractionElapsedMs=${android.os.SystemClock.elapsedRealtime()-extractionStarted}")
            return JSONObject(response.out.trim())
        } catch(e: Exception) {
            trace.failure("extractor-stderr",e)
            if(stopped()) throw java.util.concurrent.CancellationException("Monitoring extraction canceled")
            throw IllegalStateException(lastError.ifBlank { MonitoringStore.error(e) },e)
        } finally { cancelWatch.cancel(true);timeout.cancel(true);deadline.shutdownNow();jar?.delete() }
    }
    private fun item(j: JSONObject,fallback: String): MonitoringItem? {
        val id=j.optString("id");if(id.isBlank()) return null
        val raw=j.optString("webpage_url").ifBlank { j.optString("url") }
        val url=when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            MonitoringPolicy.handler(fallback)=="YouTube" -> "https://www.youtube.com/watch?v=$id"
            else -> runCatching { URI(fallback).resolve(raw).toString() }.getOrNull()?.takeIf { raw.isNotBlank() && it.startsWith("http") } ?: return null
        }
        return MonitoringItem(j.optString("extractor_key").ifBlank { MonitoringPolicy.handler(fallback) }+":"+id,url,j.optString("title"))
    }
    private fun room(r: MonitoringRule,handler: String): MonitoringResult {
        val uri=URI(r.url);val name=uri.path.trim('/').substringBefore('/')
        require(name.isNotBlank()) { "Use a performer page, not the site home page" }
        if(handler=="Chaturbate") {
            val endpoint="${uri.scheme}://${uri.host}/get_edge_hls_url_ajax/"
            val state=try {
                JSONObject(read(endpoint,"POST","room_slug="+java.net.URLEncoder.encode(name,"UTF-8"),r.url)).optString("room_status")
            } catch(e: Exception) {
                if(stopped()) throw java.util.concurrent.CancellationException("Room check canceled")
                val kind=MonitoringFailure.kind(MonitoringStore.error(e))
                if(kind !in setOf("access","verification","authentication","interaction","recognition","extractor")) throw e
                trace.failure("direct-room-status",e)
                beforeBrowser()
                trace.step("browser-room-status","fallbackFrom=$kind; sharedSession=true; explicitRoomStateRequired=true") {
                    MonitoringBuiltinBrowser.roomState(c,r.url,requestMode,stopped,r.id)
                }
            }
            log("room check; rule=${r.id}; room status=$state")
            return when(state) {
                "public" -> MonitoringResult(live=MonitoringItem("Chaturbate:$name",r.url),detail="Live public room detected")
                "offline" -> MonitoringResult(offline=true,detail="Performer is offline")
                "private","group","away","hidden" -> MonitoringResult(detail="Room state: $state; no public recording started")
                else -> error("Room state unavailable; login or Browser Assist may be needed")
            }
        }
        if(handler=="Picarto") {
            val j=JSONObject(read("https://api.picarto.tv/api/v1/channel/name/"+java.net.URLEncoder.encode(name,"UTF-8")))
            if(!j.has("online")) error("API did not expose online state")
            return if(j.optBoolean("online")) MonitoringResult(live=MonitoringItem("Picarto:${j.optString("id",name)}",r.url),detail="Channel is online")
                else MonitoringResult(offline=true,detail="Channel is offline")
        }
        // Read the same public-state object the existing app adapter verifies, on the original site origin.
        val html=read(r.url)
        val marker=Regex("window\\.__PRELOADED_STATE__\\s*=").find(html)
            ?: error("Performer state not exposed; Browser Assist or login needed")
        val raw=html.substring(marker.range.last+1).trimStart()
        val j=org.json.JSONTokener(raw).nextValue() as? JSONObject ?: error("Invalid performer state")
        val view=j.optJSONObject("viewCam") ?: error("Performer state missing")
        val model=view.optJSONObject("model") ?: error("Performer identity missing")
        val actual=model.optString("username").ifBlank { view.optString("modelUsername") }
        require(actual.equals(name.removeSuffix("@xh"),true)) { "Performer identity mismatch" }
        if(!model.has("isLive")) error("Live state unavailable")
        val status=model.optString("status")
        log("public-state probe; handler=$handler; isLive=${model.optBoolean("isLive")}; status=$status; model identity matched")
        if(!model.optBoolean("isLive")) return MonitoringResult(offline=true,detail="Performer is offline")
        if(status!="public") return MonitoringResult(detail="Performer live; public playback not confirmed ($status)")
        return MonitoringResult(live=MonitoringItem("$handler:${model.get("id")}",r.url),detail="Live public room detected; custom stream may need Browser Assist")
    }
    private fun read(url: String,method: String="GET",body: String="",page: String=""): String {
        var target=url
        val requestStarted=android.os.SystemClock.elapsedRealtime()
        repeat(5) {
            check(!stopped()) { "Check canceled" }
            val connection=URL(target).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=15000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
                connection.requestMethod=method
                connection.setRequestProperty("User-Agent",MonitoringSession.requestAgent(c,requestMode))
                val cookie=MonitoringAccess.requestCookie(c,target)
                if(cookie.isNotBlank()) connection.setRequestProperty("Cookie",cookie)
                if(page.isNotBlank() && runCatching { val a=URI(page);val b=URI(target);a.scheme==b.scheme && a.authority==b.authority }.getOrDefault(false)) {
                    connection.setRequestProperty("Referer",page)
                    connection.setRequestProperty("Origin",URI(page).let { "${it.scheme}://${it.authority}" })
                }
                if(method=="POST") {
                    connection.setRequestProperty("X-Requested-With","XMLHttpRequest")
                    connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                    connection.doOutput=true;connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val code=connection.responseCode
                if(URI(target).scheme=="https") {
                    connection.headerFields.entries.filter { it.key?.equals("Set-Cookie",true)==true }.flatMap { it.value.orEmpty() }
                        .forEach { CookieManager.getInstance().setCookie(target,it) }
                    MonitoringSession.flush(c,"built-in monitoring response")
                }
                log("webpage/API request; host=${URI(target).host}; HTTP=$code; type=${connection.contentType}; cookie scoped lookup=${cookie.isNotBlank()}; directAgentPolicy=shared-browser-mode")
                if(code in 300..399) {
                    target=URI(target).resolve(connection.getHeaderField("Location") ?: error("Redirect location missing")).toString()
                    require(URI(target).scheme in setOf("http","https"))
                } else {
                    if(code !in 200..299) {
                        val excerpt=connection.errorStream?.use { bounded(it,2048).toString(Charsets.UTF_8) }.orEmpty()
                        val gate=MonitoringInteractionPolicy.responseKind(target,excerpt,code)
                        trace.event("http-response","method=$method; status=$code; responseType=${connection.contentType?.substringBefore(';')}; gate=${gate ?: "not-identified"}; requestElapsedMs=${android.os.SystemClock.elapsedRealtime()-requestStarted}; scopedCookiesAvailable=${cookie.isNotBlank()}")
                        if(gate!=null) throw BrowserCheckRequired(MonitoringInteractionPolicy.message(gate))
                        error("Website returned HTTP $code; state unknown")
                    }
                    val bytes=connection.inputStream.use { bounded(it,3_000_001) }
                    check(bytes.size<=3_000_000) { "Page exceeded monitoring size limit" }
                    val content=bytes.toString(Charsets.UTF_8)
                    MonitoringInteractionPolicy.responseKind(target,content,code)?.let { kind ->
                        trace.event("page-gate","kind=$kind; responseStatus=$code; state=unknown")
                        throw BrowserCheckRequired(MonitoringInteractionPolicy.message(kind))
                    }
                    trace.event("http-response","method=$method; status=$code; responseBytes=${bytes.size}; requestElapsedMs=${android.os.SystemClock.elapsedRealtime()-requestStarted}; scopedCookiesAvailable=${cookie.isNotBlank()}")
                    return content
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }
    private fun feed(url: String,depth: Int=0): MonitoringResult {
        require(depth<4) { "Too many feed discovery redirects" }
        val text=read(url)
        if(!text.trimStart().startsWith("<?xml") && !text.contains("<rss") && !text.contains("<feed")) {
            val feed=Regex("<link\\b[^>]*>",RegexOption.IGNORE_CASE).findAll(text).map { it.value }
                .firstOrNull { it.contains("application/rss+xml",true) || it.contains("application/atom+xml",true) }
            val href=feed?.let { Regex("href\\s*=\\s*['\"]([^'\"]+)",RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            if(href!=null) return feed(URI(url).resolve(href.replace("&amp;","&")).toString(),depth+1)
            error("General fallback found no supported channel listing or RSS/Atom feed. Browser Assist may be needed.")
        }
        val factory=org.xmlpull.v1.XmlPullParserFactory.newInstance();factory.isNamespaceAware=true
        val parser=factory.newPullParser();parser.setInput(text.reader())
        val items=mutableListOf<MonitoringItem>();var inside=false;var link="";var id="";var title="";var video=false
        var event=parser.eventType
        while(event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if(event==org.xmlpull.v1.XmlPullParser.START_TAG) when(parser.name) {
                "item","entry" -> { inside=true;link="";id="";title="";video=false }
                "enclosure","content" -> if(inside) { if(parser.getAttributeValue(null,"type").orEmpty().startsWith("video/")) video=true }
                "link" -> if(inside) {
                    val href=parser.getAttributeValue(null,"href");val rel=parser.getAttributeValue(null,"rel").orEmpty()
                    if(href!=null && rel in setOf("","alternate")) link=href
                    else if(href==null) link=parser.nextText()
                    if(rel=="enclosure" && parser.getAttributeValue(null,"type").orEmpty().startsWith("video/")) video=true
                }
                "guid","id" -> if(inside) id=parser.nextText()
                "title" -> if(inside) title=parser.nextText()
            }
            if(event==org.xmlpull.v1.XmlPullParser.END_TAG && parser.name in setOf("item","entry")) {
                // Generic feeds may contain ordinary articles. Require explicit video metadata.
                if(video && link.isNotBlank()) {
                    val resolved=URI(url).resolve(link).toString()
                    if(URI(resolved).scheme in setOf("https","http")) items.add(MonitoringItem(id.ifBlank { resolved },resolved,title))
                }
                inside=false
            }
            event=parser.next()
        }
        log("general fallback feed parsed; video entries=${items.size}")
        return MonitoringResult(items=items.distinctBy { it.id },detail="RSS/Atom: ${items.size} explicitly marked video entries")
    }
}
