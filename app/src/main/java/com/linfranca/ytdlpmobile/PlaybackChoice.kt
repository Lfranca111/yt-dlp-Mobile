package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** User selections, never signed media URLs or cookie values. Scoped to a show/movie/series. */
internal object PlaybackChoice {
    data class Choice(val audio: String="",val server: String="") {
        fun json()=JSONObject().put("audio",audio).put("server",server)
        fun variant()=listOf(audio.lowercase(),server.lowercase()).joinToString(":")
    }
    fun key(url: String): String {
        val u=URI(url);val p=u.path.orEmpty();val path=when {
            WebsiteSourceResolver.site(url)=="AniWaves" -> p.substringBefore("/ep-").substringBefore("/episode/")
            else -> p
        }
        return u.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.")+path
    }
    fun get(c: Context,url: String): Choice=runCatching {
        val j=JSONObject(c.getSharedPreferences("website_playback_choice",0).getString(key(url),"{}").orEmpty())
        val q=URI(url).rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8") }
        Choice(q[if(WebsiteSourceResolver.site(url)=="h123movies") "ydlp_audio" else "audio"] ?: j.optString("audio"),(q[if(WebsiteSourceResolver.site(url)=="AniWaves") "server" else "ydlp_server"]?.let { if(it=="auto") "" else if(WebsiteSourceResolver.site(url)=="h123movies") "Server $it" else it }) ?: j.optString("server"))
    }.getOrDefault(Choice())
    fun set(c: Context,url: String,choice: Choice) {
        c.getSharedPreferences("website_playback_choice",0).edit().putString(key(url),choice.json().toString()).apply()
    }
    fun save(c: Context,url: String,audio: String,server: String) {
        if(WebsiteSourceResolver.site(url)==null) return
        val old=get(c,url)
        val choice=Choice(audio.takeIf { it in setOf("sub","ssub","dub","en") } ?: old.audio,server.trim().take(60).ifBlank { old.server })
        c.getSharedPreferences("website_playback_choice",0).edit().putString(key(url),choice.json().toString()).apply()
    }
    fun clean(url: String): String {
        val u=URI(url);val keys=if(WebsiteSourceResolver.site(url)=="AniWaves") setOf("audio","server") else setOf("ydlp_server","ydlp_audio")
        val q=u.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && it.substringBefore('=') !in keys }.joinToString("&")
        return "${u.scheme}://${u.rawAuthority}${u.rawPath.orEmpty()}"+(if(q.isBlank()) "" else "?$q")+(u.rawFragment?.let { "#$it" } ?: "")
    }
    fun apply(c: Context,raw: String,replace: Boolean=false): String {
        val url=if(replace) clean(raw) else raw
        if(WebsiteSourceResolver.site(url) !in setOf("AniWaves","h123movies")) return url
        val choice=if(replace) get(c,clean(url)) else get(c,url);val u=URI(url)
        val existing=u.rawQuery.orEmpty().split('&').filter(String::isNotBlank).toMutableList()
        fun add(k: String,v: String) { if(v.isNotBlank() && existing.none { it.substringBefore('=')==k }) existing+="$k=${URLEncoder.encode(v,"UTF-8")}" }
        if(WebsiteSourceResolver.site(url)=="AniWaves") { add("audio",choice.audio.ifBlank { "sub" });add("server",choice.server.ifBlank { "auto" }) }
        else { add("ydlp_server",choice.server.removePrefix("Server ").ifBlank { "auto" });add("ydlp_audio",choice.audio.takeIf { it=="en" }.orEmpty()) }
        return url.substringBefore('?').substringBefore('#')+(if(existing.isEmpty()) "" else "?"+existing.joinToString("&"))
    }
    fun identityUrl(url: String,variant: String?=null): String {
        val site=WebsiteSourceResolver.site(url) ?: return url
        val q=URI(url).rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8") }
        val explicit=variant?.takeIf { it.isNotBlank() && it!=":" }
        val audio=(explicit?.substringBefore(':') ?: q[if(site=="h123movies") "ydlp_audio" else "audio"]).orEmpty().ifBlank { if(site=="AniWaves") "sub" else "" }.lowercase()
        val server=(explicit?.substringAfter(':',"") ?: q[if(site=="AniWaves") "server" else "ydlp_server"]).orEmpty().lowercase().removePrefix("server ").ifBlank { "auto" }
        if(site=="WCOStream" && explicit==null) return url
        val base=clean(url);val fragment=URI(base).rawFragment?.let { "#$it" }.orEmpty();val plain=base.substringBefore('#')
        return plain+(if(URI(plain).rawQuery.isNullOrBlank()) "?" else "&")+"ydlp_variant="+URLEncoder.encode("$audio:$server","UTF-8")+fragment
    }
}
