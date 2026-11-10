package com.linfranca.ytdlpmobile

import java.net.URI
import org.json.JSONObject

/** Custom monitoring only: scope discoveries without requiring scrolled historical coverage. */
internal object CustomBackgroundPolicy {
    fun extractorCompatible(url: String): Boolean = runCatching {
        val original=URI(url);val target=URI(MonitoringPolicy.target(url,false))
        // Exact learned custom scope remains conservative even as built-in routing expands.
        if(MonitoringPolicy.handler(url)=="YouTube" && (original.path.trimEnd('/').endsWith("/shorts") || !original.rawQuery.isNullOrBlank())) return@runCatching false
        if(original.scheme!=target.scheme || original.authority!=target.authority) return@runCatching false
        if(!original.rawQuery.isNullOrBlank() && original.rawQuery!=target.rawQuery) false
        else if(original.path.trimEnd('/')==target.path.trimEnd('/')) true
        else {
            val path=original.path.trimEnd('/')
            (MonitoringPolicy.handler(url)=="YouTube" && Regex("^/(@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+)$").matches(path) && target.path==path+"/videos") ||
                (MonitoringPolicy.handler(url)=="Twitch" && path.trim('/').split('/').size==1 && target.path==path+"/videos")
        }
    }.getOrDefault(false)
    fun scoped(j: JSONObject,items: List<MonitoringItem>): List<MonitoringItem> {
        val origin=URI(j.getString("url"))
        return items.mapNotNull { item ->
            val u=runCatching { URI(item.url) }.getOrNull() ?: return@mapNotNull null
            fun host(s: String?)=s?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
            if(u.scheme!="https" || host(u.host)!=host(origin.host) || u.userInfo!=null || (if(u.port<0) 443 else u.port)!=(if(origin.port<0) 443 else origin.port) ||
                !u.path.startsWith(j.getString("pathPrefix")) || !CustomUploadPolicy.matchesPath(u.path,j.optString("pathPattern"))) return@mapNotNull null
            if(Regex("(?i)(token|auth|cookie|signature|key)=").containsMatchIn(u.rawQuery.orEmpty())) return@mapNotNull null
            val keys=CustomUploadPolicy.keys(j)
            if(keys.any { it !in CustomUploadPolicy.query(u) }) return@mapNotNull null
            val url=CustomUploadPolicy.canonical(item.url,keys)
            item.copy(id="custom:"+url,url=url)
        }.distinctBy { it.id }.take(40)
    }
    fun channelCompatible(saved: String,current: String): Boolean = runCatching {
        val a=URI(saved);val b=URI(current)
        a.scheme==b.scheme && a.authority==b.authority && a.path.trimEnd('/')==b.path.trimEnd('/') &&
            CustomUploadPolicy.query(a).all { (k,v)->CustomUploadPolicy.query(b)[k]==v }
    }.getOrDefault(false)
    fun interaction(url: String,title: String,html: String): String? {
        MonitoringInteractionPolicy.responseKind(url,html)?.let { return MonitoringInteractionPolicy.message(it) }
        val path=runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if(Regex("(?i)(^|/)(login|signin|sign-in|captcha|challenge)(/|$)").containsMatchIn(path)) return "Sign-in or verification page encountered"
        if(Regex("(?i)verify (that )?you('|’)re human|verify you are human|just a moment|security verification").containsMatchIn(title)) return "Human verification page encountered"
        if(org.jsoup.Jsoup.parse(html).selectFirst("input[type=password]")!=null) return "Sign-in form encountered"
        return null
    }
    /** A recent-page comparison is enough; scrolled history is deliberately not required. */
    fun comparison(browser: List<MonitoringItem>,background: List<MonitoringItem>): Boolean {
        val expected=browser.take(5).map { it.id }.toSet()
        return expected.isNotEmpty() && background.any { it.id in expected }
    }
}
