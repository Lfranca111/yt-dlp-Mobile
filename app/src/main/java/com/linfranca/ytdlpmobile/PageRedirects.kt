package com.linfranca.ytdlpmobile

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal object PageRedirects {
    private var loaded=false
    @Synchronized fun load(c: Context) {
        if(loaded) return
        val saved=runCatching { JSONArray(c.getSharedPreferences("verified_page_redirects",0).getString("records","[]")) }.getOrDefault(JSONArray())
        for(i in 0 until minOf(200,saved.length())) runCatching {
            val j=saved.getJSONObject(i);val handler=j.optString("recognizedHandler");if(handler.isNotBlank()) WebsiteDomains.recognize(j.getString("target"),handler);WebsiteDomains.remember(j.getString("original"),j.getString("target"))
        }
        loaded=true
    }
    private fun key(raw: String)=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    fun resolve(c: Context,raw: String,stopped: ()->Boolean,log: (String)->Unit,cookieFile: String="",rawCookies: String="",userAgent: String="",allowRender: Boolean=true,extractorFallback: Boolean=false): PageRedirectPolicy.Result {
        load(c)
        val report: (String)->Unit={ message -> log(message);MonitoringStore.log(c,"website inspection; $message") }
        val host=runCatching { URI(raw).host }.getOrNull().orEmpty()
        if(!PageRedirectPolicy.page(raw) || MonitoringLinkPolicy.shortHandler(host)!=null)
            return PageRedirectPolicy.Result(raw,raw,listOf(raw),false,"existing_media_or_share_link_path",extractorOnly=extractorFallback)
        val effectiveAgent=userAgent.ifBlank { runCatching { MonitoringSession.agent(MonitoringSession.mode(c,raw),android.webkit.WebSettings.getDefaultUserAgent(c)) }.getOrDefault("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36") }
        var deadline=System.nanoTime()+25_000_000_000L
        val jar=runCatching { File(cookieFile.ifBlank { File(c.filesDir,"cookies/imported-cookies.txt").path }).takeIf { it.length() in 1..8_000_000 }?.readLines().orEmpty() }.getOrDefault(emptyList())
        val initial=URI(raw)
        fun active(): Int {
            if(stopped()) throw CancellationException("Page redirect check canceled")
            val remaining=((deadline-System.nanoTime())/1_000_000).toInt()
            check(remaining>0) { "Page redirect check timed out" };return minOf(4000,remaining)
        }
        fun request(url: String,body: Boolean,head: Boolean=!body): PageRedirectPolicy.Response {
            val timeout=active();PageRedirectPolicy.address(url)
            val u=URI(url);val connection=u.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false;connection.connectTimeout=timeout;connection.readTimeout=timeout
            connection.requestMethod=if(head) "HEAD" else "GET"
            connection.setRequestProperty("User-Agent",effectiveAgent)
            connection.setRequestProperty("Accept","text/html,application/xhtml+xml,*/*;q=0.8")
            val browser=runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault("")
            val rawAllowed=u.scheme==initial.scheme && u.rawAuthority==initial.rawAuthority
            val cookies=listOf(browser,WebsiteHandlerBridge.cookieHeader(jar,url),rawCookies.takeIf { rawAllowed }.orEmpty()).filter { it.isNotBlank() }.joinToString("; ")
            if(cookies.isNotBlank()) connection.setRequestProperty("Cookie",cookies)
            try {
                val status=connection.responseCode
                connection.headerFields.filterKeys { it?.equals("Set-Cookie",true)==true }.values.flatten().forEach { value -> runCatching { CookieManager.getInstance().setCookie(url,value) } }
                val html=if(body && status==200 && (connection.contentType.orEmpty().contains("html",true) || connection.contentType.orEmpty().contains("javascript",true) || u.path.endsWith(".js",true) || u.path.endsWith(".mjs",true))) {
                    val bytes=java.io.ByteArrayOutputStream();connection.inputStream.use { input ->
                        val buffer=ByteArray(8192)
                        while(true) { active();val n=input.read(buffer);if(n<0) break;check(bytes.size()+n<=1_000_000) { "Page identity response too large" };bytes.write(buffer,0,n) }
                    };bytes.toString("UTF-8")
                } else ""
                return PageRedirectPolicy.Response(status,connection.getHeaderField("Location").orEmpty(),html,connection.contentType.orEmpty())
            } finally { connection.disconnect() }
        }
        val result=PageRedirectPolicy.resolve(raw,{ url,body ->
            val r=request(url,body)
            if(!body && r.status in setOf(403,405,501)) request(url,false,false) else r
        },stopped,report,inspectUnknown=WebsiteDomains.site(raw)!=null || MonitoringPolicy.handler(raw)=="General fallback",renderProgress={ target,observe ->
            if(!allowRender) throw PageInspectionIncomplete("rendering is deferred by this monitoring execution policy")
            val uri=URI(target)
            val browser=runCatching { CookieManager.getInstance().getCookie(target).orEmpty() }.getOrDefault("")
            val allowed=uri.scheme==initial.scheme && uri.rawAuthority==initial.rawAuthority
            val scoped=listOf(browser,WebsiteHandlerBridge.cookieHeader(jar,target),rawCookies.takeIf { allowed }.orEmpty()).filter { it.isNotBlank() }.joinToString("; ")
            val started=System.nanoTime()
            try { WebsiteRecognitionRenderer.render(c,target,scoped,effectiveAgent,stopped,report,observe) }
            finally { deadline+=System.nanoTime()-started }
        },extractorFallback=extractorFallback)
        if(result.verified && (result.target!=raw || result.handler.isNotBlank())) {
            if(result.handler.isNotBlank()) WebsiteDomains.recognize(result.target,result.handler)
            WebsiteDomains.remember(raw,result.target)
            if(WebsiteDomains.site(raw)!=null) PlaybackChoice.set(c,result.target,PlaybackChoice.get(c,raw))
            synchronized(this) {
                val prefs=c.getSharedPreferences("verified_page_redirects",0)
                val previous=runCatching { JSONArray(prefs.getString("records","[]")) }.getOrDefault(JSONArray())
                val rows=(0 until previous.length()).mapNotNull { previous.optJSONObject(it) }.filter { it.optString("key")!=key(raw) }.takeLast(199)
                val record=JSONObject().put("key",key(raw)).put("original",raw).put("target",result.target)
                    .put("chain",JSONArray(result.chain)).put("recognizedHandler",result.handler).put("reason",result.reason).put("verifiedAt",System.currentTimeMillis())
                prefs.edit().putString("records",JSONArray(rows+record).toString()).apply()
            }
            report("page resolution verified; handler=${WebsiteDomains.site(result.target) ?: "extractor fallback"}; originalHost=${initial.host}; destinationHost=${URI(result.target).host}; evidence=${result.reason}; original saved link retained; URL/cookie values omitted")
        }
        return result
    }
    fun stableId(id: String,original: String,target: String,page: String=""): String = PageRedirectIdentity.stableId(id,original,target,page)
}
