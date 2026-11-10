package com.linfranca.ytdlpmobile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal class MonitoringRenderDeferred(message: String): IllegalStateException(message)

/** A bounded, unattached WebView owned only by a monitoring check. Never starts an Activity. */
internal object MonitoringPageRenderer {
    private val gate=ReentrantLock()
    data class Captured(val url: String,val headers: Map<String,String>,val at: Long)
    data class Snapshot(val html: String,val url: String,val title: String,val visibleText: String="",val media: List<Captured> = emptyList(),val player: String="",val interaction: String="",val room: String="")
    @SuppressLint("SetJavaScriptEnabled")
    fun render(c: Context,j: JSONObject,stopped: ()->Boolean,livePlayback: Boolean=false,followSelector: String?=null,validation: ((Snapshot)->List<String>)?=null): Snapshot {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Rendering must be requested from a worker thread" }
        if(!gate.tryLock(5,TimeUnit.SECONDS)) throw MonitoringRenderDeferred("Another monitoring browser check is finishing; will retry on schedule")
        val main=Handler(Looper.getMainLooper())
        val view=AtomicReference<WebView?>();val failure=AtomicReference<Throwable?>();val canceled=java.util.concurrent.atomic.AtomicBoolean(false)
        val deadline=SystemClock.elapsedRealtime()+45000
        val media=java.util.concurrent.ConcurrentHashMap<String,Captured>()
        val interactionScript=c.assets.open("monitoring/interaction-state.js").bufferedReader().use { it.readText() }.trim().removeSuffix(";")
        val roomScript=if(j.optString("builtinRoomSlug").isNotBlank())
            c.assets.open("monitoring/room-status.js").bufferedReader().use { it.readText() }
                .replace("__ROOM_SLUG__",JSONObject.quote(j.getString("builtinRoomSlug"))) else ""
        val trace=MonitoringTrace(c,"browser-render")
        trace.rule(j.optString("diagnosticRule").ifBlank { j.getString("id") })
        val liveScript=if(livePlayback) c.assets.open("monitoring/live-learning.js").bufferedReader().use { it.readText() }+
            (if(YouTubeLivePolicy.videoId(j.getString("url"))!=null) c.assets.open("monitoring/youtube-live-evidence.js").bufferedReader().use { it.readText() } else "") else ""
        fun stopCheck() { if(stopped() || canceled.get()) throw CancellationException("Monitoring check canceled") }
        fun <T> ui(action: ()->T): T {
            val result=CompletableFuture<T>()
            main.post { if(!canceled.get()) try { result.complete(action()) } catch(t: Throwable) { result.completeExceptionally(t) } }
            while(true) {
                stopCheck()
                if(SystemClock.elapsedRealtime()>=deadline) throw MonitoringRenderDeferred("Background browser timed out; will retry or use Open browser to check")
                try { return result.get(150,TimeUnit.MILLISECONDS) } catch(_: TimeoutException) { }
            }
        }
        try {
            MonitoringAccess.observe(c,j.getString("url"),"browser-before-load",j.optString("browserMode","mobile"))
            MonitoringSession.flush(c,"before monitoring browser load")
            trace.event("load","sharedProfile=true; livePlayback=$livePlayback; roomStatusProbe=${roomScript.isNotBlank()}; noActivity=true")
            MonitoringStore.log(c,"custom browser render started; source=${j.getString("id")}; limit=45s; separate monitoring WebView; no Activity launched")
            ui {
                val w=WebView(c.applicationContext);view.set(w)
                w.settings.apply {
                    javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=!livePlayback
                    allowFileAccess=false;allowContentAccess=false;javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(w,true)
                MonitoringSession.apply(w,j.optString("browserMode","mobile"))
                MonitoringDiscoveryScripts.install(c,w,j.getString("url"),j)
                w.webViewClient=object: WebViewClient() {
                    override fun onPageStarted(v: WebView?,url: String?,icon: android.graphics.Bitmap?) {
                        // A new document needs new observations; never carry a previous player into verification.
                        if(livePlayback) media.clear()
                        trace.event("page-started","livePlayback=$livePlayback; observationsReset=true; URL values omitted")
                    }
                    override fun onPageFinished(v: WebView?,url: String?) {
                        MonitoringSession.flush(c,"monitoring page finished")
                        trace.event("page-finished","livePlayback=$livePlayback; sharedProfile=true; URL values omitted")
                        if(roomScript.isNotBlank()) v?.evaluateJavascript("(function(){if(!( "+interactionScript+" )){"+roomScript+"}})()",null)
                        v?.evaluateJavascript(MonitoringDiscoveryScripts.script(c)+";window.__channelLearning&&window.__channelLearning.configure("+j.toString()+")",null)
                    }
                    override fun shouldOverrideUrlLoading(v: WebView?,r: WebResourceRequest?)=r?.url?.scheme!="https"
                    override fun shouldInterceptRequest(v: WebView?,r: WebResourceRequest?): WebResourceResponse? {
                        if(livePlayback) {
                            if(r!=null && MediaUrlClassifier.isContinuousManifest(r.url.toString())) {
                                val headers=r.requestHeaders.filterKeys { it.lowercase() in setOf("user-agent","referer","origin","accept","accept-language","cookie") }
                                media[r.url.toString()]=Captured(r.url.toString(),headers,System.currentTimeMillis())
                            }
                            return null
                        }
                        if(r?.isForMainFrame==false && Regex("(?i)\\.(mp4|webm|m3u8|mpd|m4s|ts|aac|mp3)$").containsMatchIn(r.url.path.orEmpty()))
                            return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                        return null
                    }
                    override fun onReceivedError(v: WebView?,r: WebResourceRequest?,e: WebResourceError?) {
                        if(r?.isForMainFrame==true) failure.set(MonitoringRenderDeferred("Browser channel request failed; code=${e?.errorCode}"))
                    }
                    override fun onReceivedHttpError(v: WebView?,r: WebResourceRequest?,response: WebResourceResponse?) {
                        if(r?.isForMainFrame==true) {
                            val status=response?.statusCode ?: 0
                            trace.event("main-http","status=$status; mainFrame=true")
                            failure.set(if(status in setOf(401,403)) BrowserCheckRequired("Channel browser returned HTTP $status; sign in or verify using Open browser to check")
                                else MonitoringRenderDeferred("Browser channel returned HTTP $status; will retry on schedule"))
                        }
                    }
                    override fun onRenderProcessGone(v: WebView?,detail: RenderProcessGoneDetail?): Boolean {
                        failure.set(MonitoringRenderDeferred("Monitoring WebView renderer exited; retry required"));return true
                    }
                }
                val metrics=c.resources.displayMetrics
                val width=metrics.widthPixels.coerceAtLeast(360);val height=metrics.heightPixels.coerceAtLeast(800)
                w.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));w.layout(0,0,width,height)
                w.onResume();w.loadUrl(j.getString("url"))
            }
            val progress=RenderedCheckProgress();var lastError="No matching loaded items"
            var followed=followSelector==null
            var lastRoomEvidence=""
            var lastPlaybackEvidence=""
            var youtubeAdvertised=false
            while(SystemClock.elapsedRealtime()<deadline) {
                stopCheck();failure.get()?.let { throw it }
                if(!followed) {
                    media.clear()
                    val clicked=ui {
                        val result=CompletableFuture<String>()
                        view.get()!!.evaluateJavascript(liveScript+";(function(){var gate="+interactionScript+";return gate?'gate:'+gate:window.__customLive.follow("+JSONObject.quote(followSelector)+")})()") { result.complete(it) }
                        result
                    }
                    var rawClick: String?=null
                    while(rawClick==null && SystemClock.elapsedRealtime()<deadline) {
                        stopCheck();try { rawClick=clicked.get(150,TimeUnit.MILLISECONDS) } catch(_: TimeoutException) {}
                    }
                    val answer=rawClick?.let { JSONTokener(it).nextValue() as? String }
                    if(answer?.startsWith("gate:")==true) {
                        val kind=answer.substringAfter("gate:")
                        trace.event("page-gate","kind=$kind; beforeFollow=true; state=unknown")
                        throw BrowserCheckRequired(MonitoringInteractionPolicy.message(kind))
                    }
                    if(answer=="clicked") { followed=true;Thread.sleep(1000) }
                    else { lastError="Live button selection must match one actionable element; refine the rule";Thread.sleep(500);continue }
                }
                val json=ui {
                    val result=CompletableFuture<String>()
                    val baseScript=if(!livePlayback) "(function(){document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;v.pause()});return JSON.stringify({url:location.href,title:document.title,visibleText:(document.body?document.body.innerText:'').slice(0,200000),html:"+MonitoringDiscoveryScripts.snapshot()+"})})()"
                    else "JSON.stringify(window.__customLive.capture())"
                    val script=(if(livePlayback) liveScript+";" else "")+"(function(){var gate="+interactionScript+";var s=gate?{url:location.href,title:document.title,html:"+MonitoringDiscoveryScripts.snapshot()+",visibleText:'',player:''}:JSON.parse("+baseScript.trim().removeSuffix(";")+");s.interaction=gate;var r=window.__appRoomStatus;s.room=JSON.stringify(r?{pending:r.pending,status:r.status,state:r.state,gate:r.gate}:{});return JSON.stringify(s)})()"
                    view.get()!!.evaluateJavascript(script) { result.complete(it) }
                    result
                }
                var raw: String?=null
                while(raw==null && SystemClock.elapsedRealtime()<deadline) {
                    stopCheck();try { raw=json.get(150,TimeUnit.MILLISECONDS) } catch(_: TimeoutException) { }
                }
                if(raw==null) break
                val data=JSONObject(JSONTokener(raw).nextValue() as String)
                if(livePlayback) {
                    val player=runCatching { JSONObject(data.optString("player")) }.getOrDefault(JSONObject())
                    val now=System.currentTimeMillis()
                    YouTubeLivePolicy.advertised(j.getString("url"),player)?.let { manifest ->
                        // Advertised player-data is not a captured request. It still requires fresh playlist/audio verification.
                        media.putIfAbsent(manifest,Captured(manifest,emptyMap(),now))
                        if(!youtubeAdvertised) {
                            youtubeAdvertised=true
                            trace.event("youtube-player-data","sameVideo=true; liveNow=true; HLSAdvertised=true; requestNotRequired=true; URL omitted")
                        }
                    }
                    val resources=MonitoringPlaybackEvidence.resources(player,now)
                    resources.forEach { resource ->
                        media.compute(resource.url) { _,existing ->
                            if(existing==null || resource.at>existing.at) Captured(resource.url,existing?.headers.orEmpty(),resource.at) else existing
                        }
                    }
                    val fresh=media.values.count { now-it.at in 0..30_000L }
                    val evidence=MonitoringPlaybackEvidence.summary(player,fresh)
                    if(evidence!=lastPlaybackEvidence) {
                        lastPlaybackEvidence=evidence
                        trace.event("player-evidence",evidence+"; resourceHistoryStreams=${resources.size}; addresses omitted")
                    }
                    lastError=MonitoringPlaybackEvidence.waiting(MonitoringPlaybackEvidence.stage(player,fresh>0))
                }
                val snapshot=Snapshot(data.getString("html"),data.getString("url"),data.optString("title"),data.optString("visibleText"),
                    if(livePlayback) media.values.filter { System.currentTimeMillis()-it.at in 0..30000 } else emptyList(),data.optString("player"),data.optString("interaction"),data.optString("room"))
                check(snapshot.html.length<=3000000) { "Rendered channel exceeds 3 MB; narrow the source" }
                if(snapshot.url=="about:blank") { Thread.sleep(500);continue }
                val sameSite=if(j.optBoolean("builtinBrowser")) MonitoringBuiltinBrowser.samePage(j.getString("url"),snapshot.url) else runCatching { java.net.URI(snapshot.url).host==java.net.URI(j.getString("url")).host }.getOrDefault(false)
                if(!sameSite) throw BrowserCheckRequired("Channel redirected to another site; open browser to sign in or update the saved channel address")
                CustomBackgroundPolicy.interaction(snapshot.url,snapshot.title,"")?.let { throw BrowserCheckRequired(it+"; use Open browser to check") }
                if(followSelector!=null && CustomLivePolicy.safeTarget(j.getString("url"),snapshot.url)==null) throw BrowserCheckRequired("Live button opened an unsupported destination; update its rule")
                if(followSelector==null && !(if(j.optBoolean("builtinBrowser")) MonitoringBuiltinBrowser.samePage(j.getString("url"),snapshot.url) else CustomBackgroundPolicy.channelCompatible(j.getString("url"),snapshot.url))) throw BrowserCheckRequired("Browser left the configured channel; sign in or update the channel address using Open browser to check")
                if(snapshot.interaction.isNotBlank()) {
                    trace.event("page-gate","kind=${snapshot.interaction}; interactionRequired=true; state=unknown")
                    throw BrowserCheckRequired(MonitoringInteractionPolicy.message(snapshot.interaction))
                }
                if(j.optBoolean("builtinRequirePublic")) {
                    val room=runCatching { JSONObject(snapshot.room) }.getOrDefault(JSONObject())
                    if(room.optBoolean("pending",true)) { Thread.sleep(500);continue }
                    val roomEvidence="${room.optInt("status")}:${room.optString("state")}:${room.optString("gate")}"
                    if(roomEvidence!=lastRoomEvidence) {
                        lastRoomEvidence=roomEvidence
                        trace.event("room-api","status=${room.optInt("status")}; state=${room.optString("state").ifBlank { "unknown" }}; gate=${room.optString("gate").ifBlank { "not-identified" }}")
                    }
                    if(room.optString("gate").isNotBlank()) throw BrowserCheckRequired(MonitoringInteractionPolicy.message(room.getString("gate")))
                    if(room.optInt("status") in setOf(401,403))
                        throw BrowserCheckRequired("Room status request denied (HTTP ${room.optInt("status")}); cause unconfirmed; live state unknown. Review the session or website prompt in Browser Assist.")
                    if(room.optString("state")!="public")
                        throw BrowserCheckRequired("Fresh room status did not confirm public playback; no recording started. Open Browser Assist or check the channel again.")
                }
                val parsed=runCatching {
                    if(validation!=null) return@runCatching validation(snapshot)
                    val result=CustomSources.parse(j,snapshot.html,j.getString("mode")=="live",snapshot.url)
                    if(j.getString("mode")=="both") CustomSources.parse(j,snapshot.html,true,snapshot.url)
                    result.items.take(40).map { it.id }+if(result.offline) listOf("offline") else if(result.live!=null) listOf("live") else emptyList()
                }
                if(parsed.isSuccess) {
                    val keys=parsed.getOrThrow()
                    if(progress.accept(keys)) {
                        MonitoringStore.log(c,"custom browser render completed; source=${j.getString("id")}; recentMatches=${keys.size}; stableSamples=4; historical coverage not claimed")
                        trace.event("validation","outcome=verified; matches=${keys.size}; mediaCandidates=${snapshot.media.size}; stableSamples=4")
                        return snapshot
                    }
                } else {
                    progress.accept(emptyList())
                    parsed.exceptionOrNull()?.let { if(it is BrowserCheckRequired) throw it }
                    CustomBackgroundPolicy.interaction(snapshot.url,snapshot.title,"")?.let { throw BrowserCheckRequired(it+"; use Open browser to check") }
                    lastError=MonitoringStore.error(parsed.exceptionOrNull()!!)
                }
                Thread.sleep(1000)
            }
            trace.event("validation","outcome=unsettled; state=unknown; timeout=true")
            throw MonitoringRenderDeferred((if(livePlayback) "Background live playback could not be verified. " else "Background browser did not settle into a verified channel view. ")+"$lastError. Retry or use Open browser to check")
        } catch(e: Exception) {
            trace.failure("render",e)
            throw e
        } finally {
            trace.event("cleanup","temporaryViewOnly=true; sharedSessionRetained=true")
            canceled.set(true)
            // Never clear cookies/storage, or call pauseTimers/resumeTimers (those affect other WebViews).
            main.post { view.getAndSet(null)?.let { w -> runCatching { MonitoringSession.flush(c,"temporary monitoring view cleanup");w.stopLoading();w.onPause();w.removeAllViews();w.destroy() } } }
            gate.unlock()
            MonitoringStore.log(c,"custom browser cleanup requested; source=${j.getString("id")}; monitoring view only; shared session retained")
        }
    }
}
