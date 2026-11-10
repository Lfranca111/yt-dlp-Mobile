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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Independent background player. Visible Browser Assist code and its candidate ranking are untouched. */
internal object H123BackgroundCapture {
    data class Capture(val url: String,val referer: String,val agent: String,val headers: Map<String,String>)
    private val gate=ReentrantLock()
    @SuppressLint("SetJavaScriptEnabled")
    fun capture(c: Context,info: JSONObject,cookieLines: List<String>,stopped: ()->Boolean,log: (String)->Unit): Capture {
        check(Looper.myLooper()!=Looper.getMainLooper())
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "Background player features unavailable" }
        val handler=Handler(Looper.getMainLooper());val web=AtomicReference<WebView?>();val failure=AtomicReference<String?>()
        val requests=ConcurrentHashMap<String,Map<String,String>>();val authorized=ConcurrentHashMap.newKeySet<String>()
        val deadline=SystemClock.elapsedRealtime()+15000;val closed=java.util.concurrent.atomic.AtomicBoolean(false)
        val page=info.getString("_app_trial_player");val uri=URI(page);val origin="${uri.scheme}://${uri.rawAuthority}";val path=uri.path
        val selected=info.getString("_app_trial_source");val expectedId=path.trim('/').split('/').getOrNull(1).orEmpty()
        require(uri.scheme=="https" && uri.host=="cdn.vidspark.to" && path.matches(Regex("/(movie/\\d+|tv/\\d+/\\d+/\\d+)"))) { "Unsupported background player identity" }
        val agent=info.optString("_app_trial_agent")
        fun active() { if(stopped()) throw CancellationException("Background trial canceled");check(SystemClock.elapsedRealtime()<deadline) { "Background trial timed out; no authorized playback request" };failure.get()?.let { error(it) } }
        fun <T> ui(action: ()->T): T {
            val result=CompletableFuture<T>();handler.post { if(!closed.get()) try { result.complete(action()) } catch(t: Throwable) { result.completeExceptionally(t) } }
            while(true) { active();try { return result.get(100,TimeUnit.MILLISECONDS) } catch(_: TimeoutException) {} }
        }
        check(gate.tryLock(2,TimeUnit.SECONDS)) { "Background trial already in progress" }
        try {
            log("h123 background trial capture started; limit=15s; exact selected backend server enforced; no Activity launched; no CAPTCHA interaction")
            ui {
                val w=WebView(c.applicationContext);web.set(w)
                w.settings.apply { javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=false;allowFileAccess=false;allowContentAccess=false;setSupportMultipleWindows(true);if(agent.isNotBlank()) userAgentString=agent }
                val cookies=CookieManager.getInstance();cookies.setAcceptThirdPartyCookies(w,true)
                WebsiteHandlerBridge.cookieHeader(cookieLines,page).split(';').map(String::trim).filter { it.contains('=') }.forEach { cookies.setCookie(page,"$it; Path=/; Secure") }
                w.webChromeClient=object: WebChromeClient() {
                    override fun onCreateWindow(v: WebView?,dialog: Boolean,gesture: Boolean,message: android.os.Message?)=false
                    override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
                    override fun onJsAlert(v: WebView?,url: String?,message: String?,result: JsResult?): Boolean { result?.cancel();return true }
                    override fun onJsConfirm(v: WebView?,url: String?,message: String?,result: JsResult?): Boolean { result?.cancel();return true }
                    override fun onJsPrompt(v: WebView?,url: String?,message: String?,defaultValue: String?,result: JsPromptResult?): Boolean { result?.cancel();return true }
                }
                w.webViewClient=object: WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?,r: WebResourceRequest?): Boolean {
                        if(r?.isForMainFrame==true && r.url.toString().substringBefore('?').trimEnd('/')!=page.substringBefore('?').trimEnd('/')) { failure.set("Background player navigated away from selected identity");return true }
                        return r?.url?.scheme !in setOf("https","about","blob")
                    }
                    override fun shouldInterceptRequest(v: WebView?,r: WebResourceRequest?): WebResourceResponse? {
                        if(r!=null && requests.size<256 && r.url.scheme=="https") requests[r.url.toString().substringBefore('#')]=r.requestHeaders.filterKeys { it.lowercase() !in setOf("cookie","authorization","proxy-authorization","host","range","content-length","accept-encoding","connection") }
                        return null
                    }
                    override fun onReceivedError(v: WebView?,r: WebResourceRequest?,e: WebResourceError?) { if(r?.isForMainFrame==true) failure.set("Background main-frame request failed") }
                    override fun onRenderProcessGone(v: WebView?,detail: RenderProcessGoneDetail?): Boolean { failure.set("Background renderer unavailable");return true }
                }
                WebViewCompat.addWebMessageListener(w,"H123Trial",setOf(origin)) { _,message,source,isMainFrame,_ ->
                    if(isMainFrame && source.toString().trimEnd('/')==origin) runCatching {
                        val data=JSONObject(message.data.orEmpty())
                        if(data.optString("source")==selected && data.optString("id")==expectedId) {
                            val media=URI(data.getString("url"));if(media.scheme=="https" && media.userInfo==null) authorized.add(media.toString().substringBefore('#'))
                        }
                    }
                }
                WebViewCompat.addDocumentStartJavaScript(w,script(origin,"/api/vidora/v1$path",selected,expectedId),setOf(origin))
                val m=c.resources.displayMetrics;w.layout(0,0,m.widthPixels.coerceAtLeast(360),m.heightPixels.coerceAtLeast(800));w.onResume();w.loadUrl(page)
            }
            while(true) {
                active()
                authorized.firstOrNull { requests.containsKey(it) }?.let { media ->
                    log("h123 background trial captured authorized player media request; title/episode API route and selected server matched; headers=${requests[media]?.size ?: 0}; values omitted")
                    return Capture(media,page,agent,requests[media].orEmpty())
                }
                Thread.sleep(100)
            }
        } finally {
            closed.set(true);handler.post { web.getAndSet(null)?.let { w -> runCatching { WebViewCompat.removeWebMessageListener(w,"H123Trial");w.stopLoading();w.onPause();w.removeAllViews();w.destroy();CookieManager.getInstance().flush() } } }
            gate.unlock()
        }
    }
    internal fun script(origin: String,api: String,selected: String,id: String): String = """
    (function(){
      const origin=${JSONObject.quote(origin)},api=${JSONObject.quote(api)},source=${JSONObject.quote(selected)},id=${JSONObject.quote(id)};
      function rewrite(raw){try{const u=new URL(raw,location.href);if(u.origin===origin&&u.pathname===api){u.searchParams.set('source',source);return u.href}}catch(e){}return raw}
      function inspect(raw,data){try{const u=new URL(raw,location.href);if(u.origin!==origin||u.pathname!==api||u.searchParams.get('source')!==source||!data.result)return;
        if(data.tmdb_id!=null&&String(data.tmdb_id)!==id)return;
        (data.sources||[]).slice(0,8).forEach(function(s){const url=s.url||s.file;if(typeof url==='string')H123Trial.postMessage(JSON.stringify({url:url,source:source,id:id}))});}catch(e){}}
      const f=window.fetch;window.fetch=function(input,init){let raw=typeof input==='string'?input:(input instanceof URL?input.href:input.url);const next=rewrite(raw);let request=input;
        if(next!==raw)request=(typeof input==='string'||input instanceof URL)?next:new Request(next,input);
        return f.call(this,request,init).then(function(response){const u=next;try{if(new URL(u,location.href).pathname===api)response.clone().json().then(function(data){inspect(u,data)}).catch(function(){})}catch(e){}return response})};
      const open=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(method,url){const next=rewrite(String(url));this.addEventListener('load',function(){try{inspect(next,this.responseType==='json'?this.response:JSON.parse(this.responseText))}catch(e){}});const args=Array.from(arguments);args[1]=next;return open.apply(this,args)};
      let tries=0;const timer=setInterval(function(){if(++tries>55){clearInterval(timer);return}document.querySelectorAll('video').forEach(function(v){v.muted=true;try{v.play().catch(function(){})}catch(e){}});
        const play=document.querySelector('[data-plyr="play"],button[aria-label="Play"],button[aria-label="Play video"],.vjs-big-play-button,.jw-icon-playback');if(play)play.click();},250);
    })();
    """.trimIndent()
}
