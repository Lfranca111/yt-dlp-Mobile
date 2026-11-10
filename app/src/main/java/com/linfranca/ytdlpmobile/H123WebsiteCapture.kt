package com.linfranca.ytdlpmobile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Bounded independent player session for the website's actual selected provider. */
internal object H123WebsiteCapture {
    private val gate=ReentrantLock()
    data class Result(val url: String,val headers: Map<String,String>,val duration: Double)
    @SuppressLint("SetJavaScriptEnabled")
    fun capture(c: Context,seed: WebsiteSourceResolver.Media,agent: String,cookies: List<String>,stopped: ()->Boolean,log: (String)->Unit,preferAudioMaster: Boolean=false,
        validate: (String,Map<String,String>,Double)->Boolean): Result {
        check(Looper.myLooper()!=Looper.getMainLooper())
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "Background player features unavailable" }
        val page=URI(seed.playerPage);val origin="${page.scheme}://${page.rawAuthority}"
        check(page.scheme=="https" && page.userInfo==null && seed.playerServer in 1..6)
        val allowedOrigins=if(page.host=="moviesapi.to") setOf(origin,"https://vidspark.to","https://cdn.vidspark.to") else setOf(origin)
        val main=Handler(Looper.getMainLooper());val web=AtomicReference<WebView?>();val closed=AtomicBoolean(false)
        val error=AtomicReference<String?>();val duration=AtomicReference(0.0);val media=ConcurrentHashMap<String,Long>();val requests=ConcurrentHashMap<String,Map<String,String>>()
        val audioMasters=ConcurrentHashMap.newKeySet<String>()
        val attempted=mutableSetOf<String>();val deadline=SystemClock.elapsedRealtime()+22000
        check(gate.tryLock(2,TimeUnit.SECONDS)) { "Background player busy" }
        try {
            log("h123 website background started; websiteServer=${seed.playerServer}; providerHost=${page.host}; limitMs=22000; exact player/content route; no Activity launched")
            main.post {
                if(!closed.get()) try {
                    val w=WebView(c.applicationContext);web.set(w)
                    w.settings.apply { javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=false;allowFileAccess=false;allowContentAccess=false;setSupportMultipleWindows(true);userAgentString=agent }
                    val cm=CookieManager.getInstance();cm.setAcceptThirdPartyCookies(w,true)
                    WebsiteHandlerBridge.cookieHeader(cookies,seed.playerPage).split(';').filter { it.contains('=') }.forEach { cm.setCookie(seed.playerPage,"${it.trim()}; Path=/; Secure") }
                    w.webChromeClient=object: WebChromeClient() {
                        override fun onCreateWindow(v: WebView?,d: Boolean,g: Boolean,m: android.os.Message?)=false
                        override fun onPermissionRequest(r: PermissionRequest?) { r?.deny() }
                        override fun onJsAlert(v: WebView?,u: String?,m: String?,r: JsResult?): Boolean { r?.cancel();return true }
                        override fun onJsConfirm(v: WebView?,u: String?,m: String?,r: JsResult?): Boolean { r?.cancel();return true }
                        override fun onJsPrompt(v: WebView?,u: String?,m: String?,d: String?,r: JsPromptResult?): Boolean { r?.cancel();return true }
                    }
                    w.webViewClient=object: WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?,r: WebResourceRequest?): Boolean = r?.isForMainFrame==true && !("${r.url.scheme}://${r.url.authority}" in allowedOrigins && r.url.path==page.path)
                        override fun shouldInterceptRequest(v: WebView?,r: WebResourceRequest?): WebResourceResponse? {
                            if(r!=null && r.url.scheme=="https" && requests.size<512) requests[r.url.toString().substringBefore('#')]=r.requestHeaders.filterKeys { it.lowercase() in setOf("referer","origin","user-agent","accept","accept-language") }
                            return null
                        }
                        override fun onReceivedHttpError(v: WebView?,r: WebResourceRequest?,response: WebResourceResponse?) {
                            if(r!=null && (r.isForMainFrame || r.url.path.orEmpty().contains("/api/") || MediaUrlClassifier.isContinuousManifest(r.url.toString()))) log("h123 website background HTTP response; server=${seed.playerServer}; HTTP=${response?.statusCode}; host=${r.url.host}")
                        }
                        override fun onReceivedError(v: WebView?,r: WebResourceRequest?,e: WebResourceError?) { if(r?.isForMainFrame==true) error.set("Background player network error; code=${e?.errorCode}") }
                        override fun onRenderProcessGone(v: WebView?,d: RenderProcessGoneDetail?): Boolean { error.set("Background player renderer unavailable");return true }
                    }
                    WebViewCompat.addWebMessageListener(w,"H123Website",allowedOrigins) { _,m,o,isMain,_ ->
                        if(isMain && o.toString().trimEnd('/') in allowedOrigins && !closed.get() && (m.data?.length ?: 0)<12000) runCatching {
                            val j=JSONObject(m.data.orEmpty())
                            if(j.optString("kind")=="player") duration.set(j.optDouble("duration",0.0))
                            if(j.optString("kind")=="media" && media.size<80) {
                                val u=URI(j.getString("url"));if(u.scheme=="https" && u.userInfo==null) {
                                    val key=u.toString().substringBefore('#');media[key]=SystemClock.elapsedRealtime()
                                    if(j.optBoolean("audioMaster")) audioMasters.add(key)
                                }
                            }
                        }
                    }
                    WebViewCompat.addDocumentStartJavaScript(w,script(),allowedOrigins)
                    val d=c.resources.displayMetrics;w.layout(0,0,d.widthPixels.coerceAtLeast(360),d.heightPixels.coerceAtLeast(800));w.onResume();w.loadUrl(seed.playerPage,mapOf("Referer" to MediaRequestPolicy.referrer(seed.page,seed.playerPage)))
                } catch(t: Exception) { error.set("Background player initialization failed; type=${t.javaClass.simpleName}") }
            }
            while(SystemClock.elapsedRealtime()<deadline) {
                if(stopped()) throw CancellationException("Download canceled")
                error.get()?.let { error(it) }
                val seconds=duration.get()
                if(seconds.isFinite() && seconds>0) {
                    val candidates=media.entries.filter { requests.containsKey(it.key) && !attempted.contains(it.key) }.sortedWith(compareByDescending<Map.Entry<String,Long>> { preferAudioMaster && it.key in audioMasters }.thenByDescending { it.value }).take(3)
                    for(candidate in candidates) {
                        if(SystemClock.elapsedRealtime()>=deadline) break
                        attempted.add(candidate.key)
                        val headers=requests[candidate.key].orEmpty()
                        log("h123 website background candidate; websiteServer=${seed.playerServer}; mediaHost=${URI(candidate.key).host}; observedPlayingSeconds=${seconds.toLong()}; headers=${headers.size}; URL/header values omitted")
                        if(validate(candidate.key,headers,seconds)) return Result(candidate.key,headers,seconds)
                    }
                }
                Thread.sleep(100)
            }
            log("h123 website background unavailable; websiteServer=${seed.playerServer}; networkRequests=${requests.size}; mediaCandidates=${media.size}; playingDurationObserved=${duration.get()>0}; attempted=${attempted.size}; nested players or user interaction may require visible Browser Assist")
            error("Background playback timed out; use Browser Assist")
        } finally {
            closed.set(true);main.post { web.getAndSet(null)?.let { w -> runCatching { WebViewCompat.removeWebMessageListener(w,"H123Website");w.stopLoading();w.onPause();w.removeAllViews();w.destroy() } } };gate.unlock()
        }
    }
    internal fun script(): String = """
    (function(){
      function send(j){try{H123Website.postMessage(JSON.stringify(j))}catch(e){}}
      function media(raw){try{const u=new URL(raw,location.href);if(u.protocol==='https:'&&/\.(m3u8|mp4|webm)(?:$|[?#])/i.test(u.href))send({kind:'media',url:u.href})}catch(e){}}
      function playlist(url,text){if(typeof text==='string'&&/#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO/i.test(text.slice(0,200000)))send({kind:'media',url:url,audioMaster:true})}
      async function inspectPlaylist(response){let reader;try{reader=response.clone().body.getReader();let text='',left=200000;const decoder=new TextDecoder();while(left>0){const part=await reader.read();if(part.done)break;const bytes=part.value.subarray(0,left);left-=bytes.length;text+=decoder.decode(bytes,{stream:true});if(/#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO/i.test(text))break}playlist(response.url,text)}catch(e){}finally{if(reader)reader.cancel().catch(function(){})}}
      const fetch0=window.fetch;window.fetch=function(){return fetch0.apply(this,arguments).then(function(r){try{const type=r.headers.get('content-type')||'';if(/mpegurl/i.test(type)){send({kind:'media',url:r.url});inspectPlaylist(r)}else media(r.url)}catch(e){}return r})};
      const open=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(){this.addEventListener('load',function(){try{if(/mpegurl/i.test(this.getResponseHeader('content-type')||'')){send({kind:'media',url:this.responseURL});playlist(this.responseURL,this.responseText)}else media(this.responseURL)}catch(e){}});return open.apply(this,arguments)};
      let tries=0;const timer=setInterval(function(){if(++tries>85){clearInterval(timer);return}
        let playingSeconds=0;document.querySelectorAll('video').forEach(function(v){v.muted=true;try{v.play().catch(function(){})}catch(e){};if(!v.paused&&v.currentTime>0&&isFinite(v.duration)&&v.duration>0&&!v.closest('.video-ads,.ad-container,.jw-flag-ads,.vjs-ad-playing,[data-ad]')){playingSeconds=Math.max(playingSeconds,v.duration);media(v.currentSrc)}});
        send({kind:'player',duration:playingSeconds});
        try{performance.getEntriesByType('resource').slice(-150).forEach(function(e){media(e.name)})}catch(e){}
        const play=document.querySelector('[data-plyr="play"],button[aria-label="Play"],button[aria-label="Play video"],.vjs-big-play-button,.jw-icon-playback');if(play&&document.querySelectorAll('video').length===0&&tries%3===0)play.click();
      },250);
    })();
    """.trimIndent()
}
