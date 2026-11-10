package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest

/** Declarative discovery only. Media URLs, cookies and executable selectors are never recipes. */
internal object CustomLivePolicy {
    data class State(val online: Boolean,val offline: Boolean,val target: String?,val detail: String)
    fun state(j: JSONObject,html: String,page: String=j.getString("url")): State {
        val doc=Jsoup.parse(html,page)
        val on=j.optString("liveText").isNotBlank() && j.optString("liveSelector").isNotBlank() &&
            doc.select(j.getString("liveSelector")).text().contains(j.getString("liveText"),true)
        val off=j.optString("offlineText").isNotBlank() &&
            doc.select(j.optString("offlineSelector").ifBlank { j.optString("liveSelector") }).text().contains(j.getString("offlineText"),true)
        if(on && off) return State(false,false,null,"Conflicting live/offline indicators; state unknown")
        if(off) return State(false,true,null,"Explicit offline indicator matched")
        if(j.optString("liveLocation","page")=="page") return State(on,false,page,if(on) "Live indicator matched; media verification required" else "Live state requires fresh playback evidence")
        val selected=doc.select(j.getString("liveLinkSelector"))
        val scoped=if(on && selected.size>1) selected.filter { anchor ->
            val card=anchor.closest("article,li,[data-live]")
            card!=null && card.select(j.getString("liveSelector")).text().contains(j.getString("liveText"),true)
        }.ifEmpty { selected } else selected
        val links=scoped.mapNotNull { element ->
            val anchor=if(element.hasAttr("href")) element else element.selectFirst("a[href]") ?: element.closest("a[href]")
            anchor?.absUrl("href")?.let { safeTarget(j.getString("url"),it) }
        }.distinct()
        return State(on,false,links.singleOrNull(),when(links.size) { 0->"No matching live destination; state unknown";1->"Live destination selected; playback verification required";else->"Multiple live destinations matched; narrow the selection rule" })
    }
    fun followRequired(j: JSONObject,html: String): Boolean {
        if(j.optString("liveLocation","page")!="linked") return false
        val matches=Jsoup.parse(html,j.getString("url")).select(j.getString("liveLinkSelector"))
        val element=matches.singleOrNull() ?: return false
        if(element.hasAttr("href") || element.selectFirst("a[href]")!=null || element.closest("a[href]")!=null) return false
        return element.tagName()=="button" || element.attr("role")=="button" || element.hasAttr("onclick") || element.selectFirst("button,[role=button],[onclick]")!=null
    }
    fun safeTarget(base: String,url: String): String? = runCatching {
        val a=URI(base);val b=a.resolve(url)
        if(b.scheme!="https" || b.host!=a.host || b.port!=a.port || b.userInfo!=null || b.fragment!=null ||
            Regex("(?i)(token|auth|cookie|signature|api.?key|password)=").containsMatchIn(b.rawQuery.orEmpty())) null else b.toString()
    }.getOrNull()
    fun identity(page: String): String = "custom-live:"+MessageDigest.getInstance("SHA-256").digest(page.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun sequence(body: String): Long? = Regex("(?m)^#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
    fun ended(body: String)=body.contains("#EXT-X-ENDLIST") || Regex("(?i)#EXT-X-PLAYLIST-TYPE\\s*:\\s*VOD").containsMatchIn(body)
    fun dynamicDash(body: String)=Regex("(?i)<(?:[A-Za-z0-9_]+:)?MPD\\b[^>]*\\btype\\s*=\\s*['\"]dynamic['\"]").containsMatchIn(body)
    fun manifestLive(first: String,second: String,explicit: Boolean): Boolean {
        if(first.contains("ContentProtection",true) || second.contains("ContentProtection",true)) return false
        if(first.trimStart().startsWith("#EXTM3U") && second.trimStart().startsWith("#EXTM3U")) {
            if(ended(first) || ended(second) || !second.contains("#EXTINF:")) return false
            return explicit || (sequence(first)!=null && sequence(second)!=null && sequence(second)!!>sequence(first)!!)
        }
        if(!dynamicDash(first) || !dynamicDash(second)) return false
        fun published(s: String)=Regex("publishTime\\s*=\\s*['\"]([^'\"]+)['\"]").find(s)?.groupValues?.get(1)
        return explicit || (published(first)!=null && published(second)!=null && published(first)!=published(second))
    }
}

internal object CustomSetupPolicy {
    private val upload=listOf("url","browserMode","linkSelector","pathPrefix","pathPattern","identityKeys","newestFirst","discovery","idAttribute","urlTemplate","examples","videoOnly","structured","jsonRecordKind")
    private val live=listOf("url","browserMode","liveLocation","liveLinkSelector","liveSelector","liveText","offlineSelector","offlineText","liveAllowUnconfirmedAudio")
    fun fingerprint(j: JSONObject,part: String): String {
        val text=(if(part=="uploads") upload else live).joinToString("\u001f") { "$it=${j.opt(it)}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun applySourceChange(r: MonitoringRule,old: JSONObject,j: JSONObject) {
        // Shared recipe edits never replace the channel or mode chosen on a monitoring rule.
        if(j.getInt("schema")<4 || fingerprint(old,"uploads")!=fingerprint(j,"uploads")) {
            val ended=r.customState.optBoolean("liveEndConfirmed")
            r.baseline=false;r.customState=JSONObject()
            if(j.getInt("schema")>=4 && ended) r.customState.put("liveEndConfirmed",true)
        }
        if(j.getInt("schema")<4 || fingerprint(old,"live")!=fingerprint(j,"live")) {
            r.liveSession="";r.offlineChecks=0;r.customState.remove("liveEndConfirmed")
        }
    }
    fun ready(j: JSONObject): Boolean = (j.getString("mode")=="live" || j.optString("uploadValidated")==fingerprint(j,"uploads")) &&
        (j.getString("mode")=="uploads" || j.optString("liveValidated")==fingerprint(j,"live"))
}
