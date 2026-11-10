package com.linfranca.ytdlpmobile

import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Episode-owned options and structurally compatible players; server names/hosts are not enumerated. */
internal object AniPlaybackCatalog {
    data class Server(val name: String,val token: String)
    data class Group(val key: String,val label: String,val servers: List<Server>)
    data class Catalog(val episode: String,val groups: List<Group>,val representative: Boolean=false)
    fun parse(html: String): List<Group> = Jsoup.parse(html).select(".type[data-type]").mapNotNull { block ->
        val key=block.attr("data-type").lowercase()
        if(!key.matches(Regex("[a-z][a-z0-9_-]{0,31}"))) return@mapNotNull null
        val servers=block.select("li[data-link-id]").filter { !it.hasClass("disabled") && it.attr("data-link-id").isNotBlank() }.mapNotNull {
            val name=it.text().trim().take(64)
            if(name.isBlank()) null else Server(name,it.attr("data-link-id"))
        }.distinctBy { it.name.lowercase() }
        if(servers.isEmpty()) null else Group(key,block.selectFirst("label")?.text()?.trim()?.take(40).orEmpty().ifBlank { key.uppercase() },servers)
    }.distinctBy { it.key }
    fun load(url: String,get: (String,String)->String): Catalog {
        val u=URI(url);val origin="${u.scheme}://${u.rawAuthority}"
        val doc=Jsoup.parse(get(url,""),url)
        check(!WebsitePageFingerprint.challenge(doc.outerHtml())) { "AniWaves requires website verification" }
        val show=doc.selectFirst(".layout-page-watchtv[data-id]") ?: error("AniWaves show identity missing")
        val id=show.attr("data-id");check(id.matches(Regex("\\d+"))) { "Invalid AniWaves show identity" }
        val explicit=Regex("/(?:ep-|episode/)([\\d.]+)").find(u.path)?.groupValues?.get(1)
        val current=show.attr("data-ep-name").takeIf { it.matches(Regex("[\\d.]+")) }
        val episode=explicit ?: current ?: run {
            val result=JSONObject(get("$origin/ajax/episode/list/$id",url)).optString("result")
            Jsoup.parse(result).select("a[data-num]").firstOrNull { it.attr("data-num").matches(Regex("[\\d.]+")) }?.attr("data-num")
        } ?: error("No episode available for playback choices")
        val groups=parse(JSONObject(get("$origin/ajax/server/list?servers=$id&eps=${URLEncoder.encode(episode,"UTF-8")}",url)).optString("result"))
        check(groups.isNotEmpty()) { "No playback options exposed for this episode" }
        return Catalog(episode,groups,explicit==null)
    }
    fun audio(explicit: String,dom: String): String = explicit.takeIf { it.matches(Regex("[a-z][a-z0-9_-]{0,31}")) }
        ?: dom.takeIf { it.matches(Regex("[a-z][a-z0-9_-]{0,31}")) } ?: "sub"
    data class Player(val kind: String,val endpoint: String)
    fun player(embed: String,html: String): Player {
        WebsiteSourceResolver.requireHttp(embed)
        check(URI(embed).scheme=="https") { "AniWaves player must use HTTPS" }
        val doc=Jsoup.parse(html,embed)
        check(!WebsitePageFingerprint.challenge(html)) { "AniWaves embedded player requires website verification" }
        val screen=doc.selectFirst("#screen[data-id]")
        val mg=doc.selectFirst("#mg-player[data-id]")?.takeIf { html.contains("getSources?id=") && html.contains("jwplayer") }
        val node=screen ?: mg ?: error("Unsupported AniWaves player structure")
        val token=node.attr("data-id");check(token.isNotBlank() && token.length<=8192) { "Embedded source identity missing" }
        return Player(if(screen!=null) "screen-getSources" else "jw-mg-getSources",URI(embed).resolve("getSources?id="+URLEncoder.encode(token,"UTF-8")).toString())
    }
    fun sources(response: JSONObject,kind: String): List<String> {
        fun address(value: String)=value.trim().takeIf { runCatching { WebsiteSourceResolver.requireHttp(it);URI(it).scheme=="https" }.getOrDefault(false) }
        val values=mutableListOf<String>()
        when(val sources=response.opt("sources")) {
            is String -> address(sources)?.let(values::add)
            is org.json.JSONArray -> for(i in 0 until minOf(sources.length(),32)) {
                val value=sources.opt(i)
                address(if(value is String) value else (value as? JSONObject)?.optString("file").orEmpty())?.let(values::add)
            }
        }
        if(kind=="jw-mg-getSources" && values.isEmpty()) {
            val qualities=response.optJSONObject("sources") ?: run {
                val episodes=response.keys().asSequence().filter { it.matches(Regex("[0-9]+")) }.toList()
                check(episodes.size==1) { "Ambiguous AniWaves source response" }
                response.getJSONObject(episodes.single())
            }
            qualities.keys().asSequence().filter { qualities.optJSONArray(it)!=null }
                .sortedBy { when(it.uppercase()) { "FHD","1080P"->0;"HD","720P"->1;"SD","480P"->2;else->3 } }.forEach { key ->
                    qualities.optJSONArray(key)?.optString(0)?.let { address(it)?.let(values::add) }
                }
        }
        return values.distinct()
    }
}
