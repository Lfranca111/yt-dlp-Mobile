package com.linfranca.ytdlpmobile

import android.app.Activity
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Download-only observer; existing live recording discovery remains independent. */
internal class BrowserDownloadDiscovery(private val activity: Activity,private val web: WebView,private val initial: String,
    private val status: (String)->Unit,private val completed: (Intent)->Unit) {
    private data class Candidate(val url: String,val at: Long,val frame: String="",val mediaKind: String="",val headers: Map<String,String> = emptyMap())
    private data class Player(val frame: String,val at: Long,val duration: Double,val area: Double,val url: String,val title: String,val page: String,val drm: Boolean)
    private val vimeoInitial = VimeoBrowserVod.page(initial)
    private val vimeoHeaders = setOf("accept", "accept-language", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
        "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform")
    private val adFrames=ConcurrentHashMap<String,Long>()
    private val candidates=ConcurrentHashMap<String,Candidate>();private val players=ConcurrentHashMap<String,Player>()
    private var choice=PlaybackChoice.get(activity,initial);@Volatile private var generation=0L;private var busy=false;private var documentToken="";private var settleUntil=0L;@Volatile private var closed=false
    private val script=activity.assets.open("browser-download-discovery.js").bufferedReader().use { it.readText() }
    init {
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web,"YtdlpDownloadEvents",setOf("*")) { _,message,origin,isMainFrame,_->
                if(origin.scheme !in setOf("https","http") || closed) return@addWebMessageListener
                val raw=message.data ?: return@addWebMessageListener
                if(raw.length>16000) return@addWebMessageListener
                val j=runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
                val page=j.optString("page")
                if(runCatching { URI(page).host!=origin.host }.getOrDefault(true)) return@addWebMessageListener
                val frame=j.optString("frame").take(100);val now=System.currentTimeMillis()
                if(j.optString("kind")=="document" && isMainFrame) {
                    if(documentToken.isNotBlank() && documentToken!=frame) reset()
                    documentToken=frame
                    return@addWebMessageListener
                }
                if(documentToken.isBlank() || j.optString("session")!=documentToken) return@addWebMessageListener

                when(j.optString("kind")) {
                    "switch","choice" -> if(WebsiteSourceResolver.site(web.url ?: initial)!=null && (isMainFrame || (j.optBoolean("visible") && j.optBoolean("trusted")))) {
                        val a=j.optString("audio").lowercase();val s=j.optString("server").take(60)
                        val next=PlaybackChoice.Choice(if(a in setOf("sub","dub")) a else choice.audio,s.ifBlank { choice.server })
                        if(next!=choice) { choice=next;reset();settleUntil=now+2000;status("Playback choice changed; play the selected version, then detect.") }
                        PlaybackChoice.save(activity,web.url ?: initial,next.audio,next.server)
                    }
                    "ad" -> if(j.optBoolean("playing")) { adFrames[frame]=now;candidates.values.filter { it.frame==frame }.forEach { candidates.remove(it.url) } } else adFrames.remove(frame)
                    "network" -> {
                        val u=j.optString("url");val kind=j.optString("mediaKind")
                        if(allowed(u)) {
                            val old=candidates[u]
                            candidates[u]=Candidate(u,now,frame,kind,old?.headers.orEmpty().ifEmpty { mapOf("Referer" to page,"Origin" to "${origin.scheme}://${origin.authority}") })
                            trim()
                        }
                    }
                    "player" -> if(j.optBoolean("playing") && j.optBoolean("visible")) {
                        players[frame]=Player(frame,now,j.optDouble("duration",0.0),j.optDouble("area",0.0),j.optString("url"),j.optString("title").take(200),page,j.optBoolean("drm"))
                    } else players.remove(frame)
                }
            }
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(web,script,setOf("*"))
        else MonitoringStore.log(activity,"download discovery: document-start scripts unavailable; embedded-player coverage may be limited")
    }
    fun navigation() { documentToken="";settleUntil=System.currentTimeMillis()+2000;reset() }
    fun ready() { web.evaluateJavascript(script,null) }
    fun close() { closed=true;generation++;candidates.clear();players.clear() }
    private fun reset() { generation++;candidates.clear();players.clear();adFrames.clear() }
    private fun trim() { if(candidates.size>80) candidates.values.sortedBy { it.at }.take(candidates.size-80).forEach { candidates.remove(it.url) } }
    fun observe(url: String,headers: Map<String,String>) {
        if(!allowed(url) || closed) return
        val range=headers.keys.any { it.equals("range",true) }
        if(!range && !MediaUrlClassifier.isDirectMediaUrl(url)) return
        val old=candidates[url]
        candidates[url]=Candidate(url,System.currentTimeMillis(),old?.frame.orEmpty(),old?.mediaKind ?: if(range) "range" else "url",headers.filterKeys {
            val name = it.lowercase()
            name in setOf("referer","origin","user-agent","cookie","authorization") ||
                (vimeoInitial && name in vimeoHeaders && VimeoBrowserVod.media(url) && URI(url).path.orEmpty().endsWith(".m3u8", true))
        })
        trim()
    }
    private fun allowed(url: String)=MediaUrlClassifier.isPotentialMediaRequest(url)

    fun detect() {
        if(busy || closed) return
        web.evaluateJavascript("window.__ydlpDownloadDiscovery&&window.__ydlpDownloadDiscovery.report()",null)
        // Allow reports from child frames to arrive, including their visibility reply.
        web.postDelayed({ detectReady() },300)
    }
    private fun detectReady() {
        if(busy || closed) return
        val now=System.currentTimeMillis()
        if(now<settleUntil) { status("Wait for the newly selected server or audio version to start playing, then detect again.");return }
        if(adFrames[documentToken]?.let { now-it<5000 }==true) { status("An in-video ad appears to be playing. Wait for the intended video, then detect again.");return }
        val active=players.values.filter { now-it.at<5000 && adFrames[it.frame]?.let { time -> now-time<5000 }!=true }.sortedByDescending { it.area }
        val frameIds=active.map { it.frame }.toSet()
        val eligible=candidates.values.filter { adFrames[it.frame]?.let { time -> now-time<5000 }!=true && it.mediaKind in setOf("hls","dash","video","player","range","url") }
        val bound=eligible.filter { it.frame in frameIds }
        val pool=(if(bound.any { it.mediaKind in setOf("hls","video","player") }) bound else eligible)
            .sortedWith(compareByDescending<Candidate> { it.frame in frameIds }.thenByDescending { it.mediaKind in setOf("hls","video","player") }.thenByDescending { it.at }).take(10)
        if(pool.isEmpty()) { status("No verified playback requests yet. Play the intended video, then detect again.");MonitoringStore.log(activity,"download detection: candidates=0; activeFrames=${active.size}; content observer enabled");return }
        busy=true;val epoch=generation;val page=web.url ?: initial;val agent=web.settings.userAgentString;val pageTitle=web.title.orEmpty();val selectedChoice=choice
        status("Verifying the playing video's playlists and complete duration…")
        Thread {
            val results=mutableListOf<Pair<Candidate,JSONObject>>();val deadline=System.nanoTime()+75_000_000_000L
            if (VimeoBrowserVod.page(page)) {
                val player = active.singleOrNull()
                if (player != null && VimeoBrowserVod.page(player.page) && !player.drm) {
                    val playlists = bound.filter { it.frame == player.frame && VimeoBrowserVod.media(it.url) &&
                        (it.mediaKind == "hls" || URI(it.url).path.orEmpty().endsWith(".m3u8", true)) }
                        .sortedByDescending { it.at }
                    val byUrl = playlists.associateBy { it.url }
                    try {
                        val access = VerifiedMediaAccess(player.page, agent, { url ->
                            val observed = byUrl[url]?.headers.orEmpty()
                            val safe = observed.filterKeys { !it.equals("cookie", true) }
                            val cookie = observed.entries.firstOrNull { it.key.equals("cookie", true) }?.value
                                ?: CookieManager.getInstance().getCookie(url).orEmpty()
                            safe + mapOf("Cookie" to cookie)
                        }, { closed || generation != epoch }, budgetMs = 18_000, allowedUrl = VimeoBrowserVod::media)
                        val chosen = VimeoBrowserVod.select(page, pageTitle.ifBlank { player.title },
                            playlists.map { VimeoBrowserVod.Observed(it.url, it.frame) }, player.frame, player.duration,
                            { url -> access.read(url, useRange = false).also { check(it.hls) }
                                .let { VimeoBrowserVod.Manifest(it.url, it.text) } }, access::headers)
                        if (chosen != null) {
                            val candidate = byUrl.getValue(chosen.root)
                            access.active()
                            results += candidate to access.completePlan(chosen.info)
                            MonitoringStore.log(activity,"Vimeo browser download: verified matching complete video and audio playlists; segments=${chosen.info.optInt("_app_expected_segments")}; URL/header values omitted")
                        } else {
                            MonitoringStore.log(activity,"Vimeo browser download: no matching complete pair; playlistCandidates=${playlists.size}; standard detection retained")
                        }
                    } catch (e: Exception) {
                        MonitoringStore.log(activity,"Vimeo browser download: paired playlist check unavailable (${e.javaClass.simpleName}); URL/header values omitted")
                    }
                }
            }
            for(candidate in pool.takeIf { results.isEmpty() }.orEmpty()) {
                if(closed || generation!=epoch || System.nanoTime()>deadline) break
                val player=active.firstOrNull { it.frame==candidate.frame } ?: active.singleOrNull()
                try {
                    val access=VerifiedMediaAccess(page,agent,{ target ->
                        val h=if(URI(target).host==URI(candidate.url).host) candidate.headers else candidate.headers.filterKeys { it.lowercase() in setOf("referer","origin","user-agent") }
                        h.filterKeys { !it.equals("cookie",true) } + mapOf("Cookie" to (h.entries.firstOrNull { it.key.equals("cookie",true) }?.value ?: CookieManager.getInstance().getCookie(target).orEmpty()))
                    },{ closed || generation!=epoch || System.nanoTime()>deadline })
                    val info=access.plan(candidate.url,page,pageTitle.ifBlank { player?.title.orEmpty() },player?.duration ?: 0.0,DownloadMediaPolicy.audioOnly(candidate.url))
                    val duration=info.optDouble("_app_expected_duration",0.0);val expected=player?.duration ?: 0.0
                    if(expected>0 && duration>0 && kotlin.math.abs(expected-duration)>maxOf(10.0,expected*0.03)) {
                        MonitoringStore.log(activity,"download candidate rejected: duration mismatch; playerSeconds=${expected.toInt()}; playlistSeconds=${duration.toInt()}; values omitted");continue
                    }
                    val label=listOf(selectedChoice.audio.uppercase(),selectedChoice.server).filter(String::isNotBlank).joinToString(" · ")
                    if(label.isNotBlank()) info.put("title",info.optString("title")+" ["+label+"]")
                    info.put("_app_variant",selectedChoice.variant()).put("_app_audio",selectedChoice.audio).put("_app_server",selectedChoice.server)
                    results+=candidate to info
                    MonitoringStore.log(activity,"download candidate verified; kind=${candidate.mediaKind}; expectedSeconds=${duration.toInt()}; segments=${info.optInt("_app_expected_segments")}; activeFrame=${candidate.frame in frameIds}; URL values omitted")
                } catch(e: Exception) { MonitoringStore.log(activity,"download candidate rejected; ${MonitoringStore.error(e)}; URL/header values omitted") }
            }
            activity.runOnUiThread {
                busy=false
                if(closed || epoch!=generation) { status("Playback changed while checking. Play the new selection and detect again.");return@runOnUiThread }
                // Prefer the master over its inspected children, retaining all renditions for the preset.
                val children=results.flatMap { (_,info)->val f=info.optJSONArray("formats");(0 until (f?.length() ?: 0)).mapNotNull { f?.optJSONObject(it)?.optString("url") } }.toSet()
                val roots=results.filter { (candidate,info)->candidate.url !in children || (info.optJSONArray("formats")?.length() ?: 0)>1 }.ifEmpty { results }
                if(roots.isEmpty()) { status("No complete media could be verified. Keep the selected video playing and retry; detailed reasons are in the monitoring log.");return@runOnUiThread }
                fun choose(pair: Pair<Candidate,JSONObject>) {
                    val (candidate,info)=pair
                    status("Selected ${info.optDouble("_app_expected_duration").toInt()} seconds; preparing download…")
                    fun finishSelection() { completed(Intent().putExtra(BrowserSessionActivity.EXTRA_MEDIA_URL,candidate.url)
                        .putExtra(BrowserSessionActivity.EXTRA_REFERER,page).putExtra(BrowserSessionActivity.EXTRA_USER_AGENT,agent)
                        .putExtra(BrowserSessionActivity.EXTRA_DOWNLOAD_INFO_JSON,info.toString())
                        .putExtra(BrowserSessionActivity.EXTRA_DOWNLOAD_DIAGNOSTICS,(if (info.optBoolean("_app_vimeo_browser_pair")) "Vimeo: matched complete video and audio playlists. " else "") + "Verified content-based playback; audio=${selectedChoice.audio.ifBlank { "default" }}; server=${selectedChoice.server.ifBlank { "selected playback" }}; expectedSegments=${info.optInt("_app_expected_segments")}; URLs omitted")
                        .putExtra(BrowserSessionActivity.EXTRA_MODE,BrowserSessionActivity.MODE_DOWNLOAD)) }
                    if(info.optBoolean("_app_audio_only")) AlertDialog.Builder(activity).setTitle("Only audio selected")
                        .setMessage("This source is an audio rendition. Download audio only, or play the intended video and detect again.")
                        .setPositiveButton("Audio only") { _,_->finishSelection() }.setNegativeButton("Detect again",null).show()
                    else finishSelection()
                }
                if (roots.size == 1) {
                    choose(roots[0])
                } else {
                    fun submitAll() {
                        if (closed || busy) return
                        if (epoch != generation) {
                            status("Playback changed. Detect the sources again.")
                            return
                        }
                        busy = true
                        status("Preparing ${roots.size} downloads for the queue…")
                        Thread {
                            val staged = runCatching {
                                val sources = roots.mapIndexed { index, (candidate, originalInfo) ->
                                    val info = JSONObject(originalInfo.toString())
                                    val title = info.optString("title").ifBlank { "Browser selected media" }
                                    info.put("title", "Source ${index + 1} - ${title.take(100)}")
                                    val diagnostics = "Verified browser source ${index + 1} of ${roots.size}; " +
                                        "expectedSegments=${info.optInt("_app_expected_segments")}; URLs omitted"
                                    BrowserDownloadBatch.Source(candidate.url, info, diagnostics)
                                }
                                BrowserDownloadBatch.write(activity, BrowserDownloadBatch.Batch(page, agent, sources))
                            }
                            activity.runOnUiThread {
                                busy = false
                                if (closed || epoch != generation || activity.isFinishing || activity.isDestroyed) {
                                    staged.getOrNull()?.let { BrowserDownloadBatch.discard(activity, it) }
                                    if (!closed && !activity.isFinishing && !activity.isDestroyed)
                                        status("Playback changed. Detect the sources again.")
                                    return@runOnUiThread
                                }
                                staged.onSuccess(completed).onFailure {
                                    status("Could not prepare all sources. Detect again or select one source.")
                                }
                            }
                        }.start()
                    }
                    val hasAudioOnly = roots.any { (_, info) -> info.optBoolean("_app_audio_only") }
                    val labels = roots.mapIndexed { index, (_, info) ->
                        val seconds = info.optDouble("_app_expected_duration", 0.0).toInt()
                        val duration = if (seconds > 0) "$seconds seconds" else "Duration unknown"
                        val type = if (info.optBoolean("_app_audio_only")) "Audio only"
                            else "${info.optJSONArray("formats")?.length() ?: 1} format(s)"
                        "${index + 1}. ${info.optString("title").take(35)} · $duration · $type"
                    }.toTypedArray()
                    status("Choose a source or download all ${roots.size}.")
                    AlertDialog.Builder(activity).setTitle("Choose the intended video")
                        .setItems(labels) { _, which -> choose(roots[which]) }
                        .setPositiveButton("Download all") { _, _ ->
                            if (hasAudioOnly) AlertDialog.Builder(activity).setTitle("Include audio-only sources?")
                                .setMessage("Download all adds each listed source, including any marked Audio only.")
                                .setPositiveButton("Download all") { _, _ -> submitAll() }
                                .setNegativeButton("Cancel", null).show()
                            else submitAll()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }.start()
    }
}
