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
import java.net.URI
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Separate recognition view. Never launches an Activity, clicks playback or changes Browser Assist. */
internal object WebsiteRecognitionRenderer {
    private val gate=ReentrantLock()
    @SuppressLint("SetJavaScriptEnabled")
    fun render(c: Context,url: String,cookie: String,agent: String,stopped: ()->Boolean,log: (String)->Unit,observe: (PageRedirectPolicy.Response)->Boolean={false}): PageRedirectPolicy.Response {
        check(Looper.myLooper()!=Looper.getMainLooper())
        if(!gate.tryLock(2,TimeUnit.SECONDS)) throw PageInspectionIncomplete("another website inspection is finishing")
        val main=Handler(Looper.getMainLooper());val view=AtomicReference<WebView?>();val failure=AtomicReference<String?>()
        var lastState="no DOM snapshot received"
        val ended=java.util.concurrent.atomic.AtomicBoolean(false);val deadline=SystemClock.elapsedRealtime()+35000
        fun active() {
            if(stopped() || ended.get()) throw CancellationException("Website inspection canceled")
            if(SystemClock.elapsedRealtime()>=deadline) throw PageInspectionIncomplete("recognition time limit reached; $lastState")
        }
        fun <T> ui(action: ()->T): T {
            val result=CompletableFuture<T>();main.post { if(!ended.get()) try { result.complete(action()) } catch(t: Throwable) { result.completeExceptionally(t) } }
            while(true) { active();try { return result.get(100,TimeUnit.MILLISECONDS) } catch(_: TimeoutException) {} }
        }
        try {
            log("page inspection render started; limit=35s; independent recognition WebView; no Activity launched")
            ui {
                val w=WebView(c.applicationContext);view.set(w)
                w.settings.apply { javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=true;allowFileAccess=false;allowContentAccess=false;javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(true);if(agent.isNotBlank()) userAgentString=agent }
                val manager=CookieManager.getInstance();manager.setAcceptCookie(true);manager.setAcceptThirdPartyCookies(w,true)
                cookie.split(';').map(String::trim).filter { it.contains('=') }.forEach { manager.setCookie(url,"$it; Path=/; Secure") }
                w.webChromeClient=object: WebChromeClient() {
                    override fun onCreateWindow(v: WebView?,dialog: Boolean,gesture: Boolean,result: android.os.Message?)=false
                    override fun onJsAlert(v: WebView?,url: String?,message: String?,result: JsResult?): Boolean { result?.confirm();return true }
                    override fun onJsConfirm(v: WebView?,url: String?,message: String?,result: JsResult?): Boolean { result?.cancel();return true }
                    override fun onJsPrompt(v: WebView?,url: String?,message: String?,defaultValue: String?,result: JsPromptResult?): Boolean { result?.cancel();return true }
                    override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String?,callback: GeolocationPermissions.Callback?) { callback?.invoke(origin,false,false) }
                }
                w.webViewClient=object: WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?,r: WebResourceRequest?): Boolean {
                        if(r?.isForMainFrame==false) return r.url.scheme !in setOf("https","about","blob")
                        val allowed=runCatching { val u=URI(r?.url.toString());u.scheme=="https" && u.host==URI(url).host }.getOrDefault(false)
                        if(!allowed && r?.isForMainFrame==true) failure.set("rendered page navigated outside the inspected website")
                        return !allowed
                    }
                    override fun shouldInterceptRequest(v: WebView?,r: WebResourceRequest?): WebResourceResponse? {
                        if(r?.isForMainFrame==false && Regex("(?i)\\.(mp4|webm|m3u8|mpd|m4s|ts|aac|mp3)$").containsMatchIn(r.url.path.orEmpty())) return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                        return null
                    }
                    override fun onReceivedError(v: WebView?,r: WebResourceRequest?,e: WebResourceError?) { if(r?.isForMainFrame==true) failure.set("main-frame request failed; code=${e?.errorCode}") }
                    override fun onReceivedHttpError(v: WebView?,r: WebResourceRequest?,response: WebResourceResponse?) { if(r?.isForMainFrame==true) failure.set("main-frame HTTP ${response?.statusCode}") }
                    override fun onRenderProcessGone(v: WebView?,detail: RenderProcessGoneDetail?): Boolean { failure.set("recognition renderer stopped");return true }
                }
                val m=c.resources.displayMetrics;val width=m.widthPixels.coerceAtLeast(360);val height=m.heightPixels.coerceAtLeast(800)
                w.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));w.layout(0,0,width,height);w.onResume();w.loadUrl(url)
            }
            val readiness=WebsiteRenderReadiness()
            while(true) {
                active();failure.get()?.let { if(it.startsWith("rendered page navigated")) throw PageRedirectPolicy.Blocked(it) else throw PageInspectionIncomplete(it) }
                val pending=ui { CompletableFuture<String>().also { result -> view.get()!!.evaluateJavascript(SNAPSHOT) { result.complete(it) } } }
                var raw: String?=null
                while(raw==null) { active();try { raw=pending.get(100,TimeUnit.MILLISECONDS) } catch(_: TimeoutException) {} }
                val decoded=JSONTokener(raw).nextValue() as? String
                if(decoded!=null) {
                    val data=JSONObject(decoded)
                    if(data.optBoolean("tooLarge")) throw PageInspectionIncomplete("rendered DOM exceeds 3 MB")
                    val actual=data.optString("url");val same=PageRedirectPolicy.renderTargetMatches(url,actual)
                    if(actual!="about:blank" && !same) throw PageRedirectPolicy.Blocked("rendered page left the intended page path or changed a selected playback option")
                    val html=data.optString("html")
                    val state="document=${data.optString("ready")}; primaryPending=${data.optInt("pendingCount")}; ignoredPeripheralPending=${data.optInt("ignoredCount")}; contentIdentityPresent=${data.optString("identity").isNotBlank()}; headingPresent=${data.optBoolean("headingPresent")}; playerPresent=${data.optBoolean("playerPresent")}; challenge=${WebsitePageFingerprint.challenge(html)}"
                    if(state!=lastState) { lastState=state;log("page inspection loading state; $state") }
                    if(same && observe(PageRedirectPolicy.Response(200,html=html,contentType="text/html",rendered=true))) {
                        log("page inspection render interrupted; confident handler evidence collected; action=handler handoff")
                        return PageRedirectPolicy.Response(200,html=html,contentType="text/html",rendered=true)
                    }
                    if(same && readiness.accept(data.optString("ready"),data.optBoolean("pending") || WebsitePageFingerprint.loading(html) || WebsitePageFingerprint.challenge(html),data.optString("identity"))) {
                        log("page inspection render complete; DOM parsed; primary content stable=3 samples; bounded whole DOM inspected; ads/analytics not readiness gates")
                        return PageRedirectPolicy.Response(200,html=html,contentType="text/html",rendered=true)
                    }
                }
                Thread.sleep(750)
            }
        } finally {
            ended.set(true);main.post { view.getAndSet(null)?.let { w -> runCatching { CookieManager.getInstance().flush();w.stopLoading();w.onPause();w.removeAllViews();w.destroy() } } }
            gate.unlock();log("page inspection render cleanup; recognition view only; Browser Assist unchanged")
        }
    }
    private val SNAPSHOT="""
      (function(){document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;v.pause()});
        var html=document.documentElement?document.documentElement.outerHTML:'';if(html.length>3000000)return JSON.stringify({tooLarge:true});
        function visible(e){var s=getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden'&&e.getClientRects().length>0}
        var main=document.querySelector('main,article,#root,#app')||document.body;
        var pendingSelector='[aria-busy=true],[data-page-loading=true],[data-detail-title-skeleton]';
        var pending=main?Array.from(main.querySelectorAll(pendingSelector)):[];if(main&&main.matches(pendingSelector))pending.unshift(main);
        var visiblePending=pending.filter(visible);var blockers=visiblePending.filter(function(e){return !e.closest('aside,nav,footer,[role=complementary],[role=navigation],.sidebar,.detail-sidebar,[aria-label=Advertisement]')});
        var busy=!main||blockers.length>0;
        var h=document.querySelector('h1,[data-title],.detail-title,.layout-page-watchtv');var text=h?(h.textContent||h.getAttribute('data-title')||h.getAttribute('data-id')||'').trim():'';
        var frame=main&&main.querySelector('iframe[src*=\"/embed/\"],iframe[src*=\"moviesapi\"],iframe[src*=\"wcostream\"],#player iframe');var frames=frame?frame.src.split('?')[0].split('#')[0]:'';
        var ids=Array.from(document.querySelectorAll('.layout-page-watchtv[data-id],[data-video-id],[data-content-id],meta[property="og:video"]')).slice(0,30).map(function(e){return e.getAttribute('data-id')||e.getAttribute('data-video-id')||e.getAttribute('data-content-id')||e.content||''}).join('|');
        var identity=text+'|'+frames+'|'+ids;if(!text&&!frames&&!ids&&main)identity=(main.innerText||'').slice(0,10000).replace(/\s+/g,' ').trim();
        return JSON.stringify({url:location.href,ready:document.readyState,pending:busy,pendingCount:blockers.length,ignoredCount:visiblePending.length-blockers.length,headingPresent:!!h,playerPresent:!!frame,identity:identity==='||'?'':identity,html:html});})()
    """.trimIndent()
}
