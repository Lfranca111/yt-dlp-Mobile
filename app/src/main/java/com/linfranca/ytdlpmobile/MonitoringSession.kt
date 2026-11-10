package com.linfranca.ytdlpmobile

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject

/** Monitoring uses the existing persistent WebView profile. Never copies passwords or clears storage. */
internal object MonitoringSession {
    private fun prefs(c: Context)=c.getSharedPreferences("monitoring_browser_sessions",Context.MODE_PRIVATE)
    private fun key(url: String)=java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    fun mode(c: Context,url: String)=prefs(c).getString(key(url)+".mode","mobile") ?: "mobile"
    fun marker(c: Context,url: String)=prefs(c).getString(key(url)+".marker","").orEmpty()
    fun save(c: Context,url: String,mode: String,marker: String) {
        require(mode in setOf("mobile","desktop"));require(marker.length<=160)
        prefs(c).edit().putString(key(url)+".mode",mode).putString(key(url)+".marker",marker.trim()).apply()
        flush(c,"session settings saved")
    }
    fun agent(mode: String,default: String): String = if(mode=="desktop")
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" else default
    fun requestAgent(c: Context,mode: String): String {
        val default=runCatching { android.webkit.WebSettings.getDefaultUserAgent(c) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: ("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
        return agent(mode,default)
    }
    fun apply(w: WebView,mode: String) {
        w.settings.userAgentString=agent(mode,android.webkit.WebSettings.getDefaultUserAgent(w.context))
        w.settings.useWideViewPort=mode=="desktop";w.settings.loadWithOverviewMode=mode=="desktop"
    }
    fun observePage(w: WebView,stage: String) {
        val page=w.url.orEmpty()
        if(!page.startsWith("https://")) return
        MonitoringAccess.observe(w.context,page,stage,mode(w.context,page))
        runCatching {
            val script=w.context.assets.open("monitoring/interaction-state.js").bufferedReader().use { it.readText() }
            w.evaluateJavascript(script) { raw ->
                val kind=runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                if(kind==null || kind in setOf("age","agreement","login","verification","")) runCatching {
                    MonitoringStore.log(w.context,"monitoring browser gate observation; stage=$stage; kind=${kind?.ifBlank { "none-detected" } ?: "unavailable"}; cookies/storage retained; no automatic acceptance")
                }
            }
        }
    }
    fun flush(c: Context,reason: String) {
        runCatching { CookieManager.getInstance().flush() }
            .onSuccess { MonitoringStore.log(c,"browser session flushed; $reason; shared profile retained; values omitted") }
            .onFailure { MonitoringStore.log(c,"browser session flush failed; ${it.javaClass.simpleName}; retry when browser is available") }
    }
    fun test(c: Context,url: String,mode: String,stopped: ()->Boolean,source: JSONObject?=null): String {
        flush(c,"before separate-view reuse test")
        val wanted=marker(c,url)
        val j=source ?: JSONObject().put("id","session-test").put("url",url).put("mode","uploads")
        j.put("browserMode",mode)
        return try {
            MonitoringPageRenderer.render(c,j,stopped) { page ->
                val text=page.visibleText
                check(text.length>=30) { "Channel content has not loaded" }
                if(wanted.isNotBlank()) check(text.contains(wanted)) { "Saved signed-in account text is not visible; login reuse could not be verified" }
                if(source!=null) {
                    val result=CustomSources.parse(j,page.html,j.optString("mode")=="live",page.url)
                    if(j.optString("mode")=="both") CustomSources.parse(j,page.html,true,page.url)
                    result.items.map { it.id }.ifEmpty { listOf(if(result.offline) "offline" else "live") }
                } else listOf("loaded",if(wanted.isBlank()) "public-access" else "account-marker")
            }
            if(wanted.isBlank()) "Page access reused in a separate browser view. Logged-in access is NOT verified: public pages can load without a login. Add unique signed-in account text and test again."
            else "Session reuse verified in a separate browser view using your signed-in account text. Restart/reboot and network-change testing is still needed; the server may expire the session."
        } catch(e: BrowserCheckRequired) {
            "Sign-in or verification required in the monitoring browser. Saved rules and history are retained."
        } catch(e: Exception) {
            if(stopped()) throw e
            "Session reuse could not be verified. Open the channel, check your account text and retry. Network/loading errors do not prove logout."
        }.also { MonitoringStore.log(c,"session reuse test; mode=$mode; account marker configured=${wanted.isNotBlank()}; outcome=$it; account values omitted") }
    }
}
