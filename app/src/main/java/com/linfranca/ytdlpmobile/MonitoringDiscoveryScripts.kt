package com.linfranca.ytdlpmobile

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URI

/** Installed only on custom monitoring views, never Main or Collections browsers. */
internal object MonitoringDiscoveryScripts {
    fun script(c: Context)=c.assets.open("monitoring/channel-learning.js").bufferedReader().use { it.readText() }
    fun install(c: Context,web: WebView,url: String,config: org.json.JSONObject?=null): ScriptHandler? = runCatching {
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        val u=URI(url);require(u.scheme=="https" && u.host!=null)
        WebViewCompat.addDocumentStartJavaScript(web,script(c)+(config?.let { ";window.__channelLearning&&window.__channelLearning.configure("+it.toString()+")" } ?: ""),setOf("${u.scheme}://${u.rawAuthority}"))
    }.getOrNull()
    fun snapshot()="(window.__channelLearning?window.__channelLearning.snapshot():document.documentElement.outerHTML.slice(0,3000001))"
}
