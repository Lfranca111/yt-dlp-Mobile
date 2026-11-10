package com.linfranca.ytdlpmobile
import java.net.URI
import org.jsoup.Jsoup

/** Shared recognition: DOM, metadata, IDs and player assets. Never gate on the document title. */
internal object WebsitePageFingerprint {
    data class Assessment(val handler: String?,val loading: Boolean,val challenge: Boolean,val evidence: Map<String,Boolean>) {
        fun summary()="handler=${handler ?: "none"}; loading=$loading; challenge=$challenge; "+evidence.entries.joinToString("; ") { "${it.key}=${it.value}" }
        fun candidates(): List<String> = listOf("AniWaves" to "ani_","WCOStream" to "wco_","h123movies" to "h123_").map { (name,prefix) ->
            val checks=evidence.filterKeys { it.startsWith(prefix) }
            "candidate=$name; decision=${if(handler==name) "matched" else "not selected"}; found=${checks.filterValues { it }.keys.joinToString(",").ifBlank { "none" }}; missing=${checks.filterValues { !it }.keys.joinToString(",").ifBlank { "none" }}; conflicts=${listOfNotNull("challenge".takeIf { challenge },"ambiguous handlers".takeIf { evidence["ambiguous_handlers"]==true },"route/DOM content ID mismatch".takeIf { prefix=="ani_" && evidence["content_id_conflict"]==true }).joinToString(",").ifBlank { "none" }}"
        }
    }
    fun challenge(html: String): Boolean = Jsoup.parse(html).select("#challenge-form,.cf-challenge-running,iframe[src*=challenges.cloudflare.com],form[action*=challenge]").any { e ->
        (e.parents()+e).none { it.hasAttr("hidden") || it.attr("aria-hidden")=="true" || Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(it.attr("style")) }
    }
    fun loading(html: String): Boolean {
        val d=Jsoup.parse(html);val placeholders=setOf("","loading","loading...","loading title...","loading title…")
        val title=d.title().trim().lowercase();val h1=d.selectFirst("h1")?.text().orEmpty().trim().lowercase()
        val primary=d.selectFirst(".layout-page-watchtv[data-id],iframe[src*=/inc/embed/],iframe[src*=/embed/],video[src],[data-video-id],[data-content-id]")!=null
        val pending=d.select("main[aria-busy=true], #root:empty, #app:empty, [data-page-loading=true]").any { e ->
            (e.parents()+e).none { it.tagName()=="aside" || it.hasAttr("hidden") || it.attr("aria-hidden")=="true" || Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(it.attr("style")) }
        }
        return (title in placeholders && h1 in placeholders && !primary) || h1 in placeholders.filter { it.isNotEmpty() } || pending
    }
    fun scripts(url: String,html: String): List<String> {
        val u=URI(url)
        return Jsoup.parse(html,url).select("script[src], link[rel=modulepreload][href], link[rel=preload][as=script][href]")
            .map { it.absUrl(if(it.hasAttr("src")) "src" else "href") }.filter { raw -> runCatching {
                val v=URI(raw);v.scheme=="https" && v.rawAuthority==u.rawAuthority && (v.path.endsWith(".js",true) || v.path.endsWith(".mjs",true))
            }.getOrDefault(false) }.distinct().sortedBy { if(it.contains("detail",true) || it.contains("player",true)) 0 else 1 }.take(64)
    }
    fun assess(url: String,html: String,assets: String=""): Assessment {
        val u=PageRedirectPolicy.address(url);val d=Jsoup.parse(html,url);val e=linkedMapOf<String,Boolean>();val matches=mutableListOf<String>()
        val show=Regex("/watch/[^/]+-(\\d+)(?:/|$)").find(u.path)?.groupValues?.get(1)
        e["page_heading_present"]=d.selectFirst("h1")!=null
        e["page_metadata_present"]=d.selectFirst("meta[property^=og:],meta[name=application-name]")!=null
        e["page_player_element"]=d.selectFirst("video,iframe[src],#detail-player-trigger,[data-video-id]")!=null
        e["page_script_references"]=d.selectFirst("script[src]")!=null
        e["content_id_conflict"]=show!=null && d.selectFirst(".layout-page-watchtv[data-id]")?.attr("data-id")?.let { it!=show }==true
        e["ani_show_route_and_dom_id"]=show!=null && d.selectFirst(".layout-page-watchtv[data-id]")?.attr("data-id")==show
        if(e["ani_show_route_and_dom_id"]==true) matches+="AniWaves"
        val decoded=runCatching { WebsiteSourceResolver.decodeWco(html) }.getOrDefault("")
        val wco=Jsoup.parse(html+decoded,url)
        e["wco_scoped_player"]=wco.select("iframe[src]").any { frame -> runCatching { val v=URI(frame.absUrl("src"));v.host=="embed.wcostream.com" && v.path.startsWith("/inc/embed/") }.getOrDefault(false) }
        val episodes=d.select("a[href]").count { a -> runCatching { val v=URI(a.absUrl("href"));v.host==u.host && Regex("episode-\\d+").containsMatchIn(v.path) }.getOrDefault(false) }
        val metadata=(d.select("meta[property=og:site_name],meta[name=application-name]").joinToString(" ") { it.attr("content") }+" "+d.select("header img[alt],a[aria-label],[itemprop=name]").joinToString(" ") { it.attr("alt")+it.attr("aria-label")+it.text() }+" "+d.title()).lowercase()
        e["wco_series_structure"]=u.path.startsWith("/anime/") && episodes>=2 && ("wcostream" in metadata || "watch cartoons online" in metadata)
        if(e["wco_scoped_player"]==true && Regex("episode-\\d+").containsMatchIn(u.path) || e["wco_series_structure"]==true) matches+="WCOStream"
        e["h123_detail_route"]=Regex("^/(movie|series|tv)/[^/]+").containsMatchIn(u.path)
        e["h123_metadata_or_structure"]="123movies" in metadata || d.selectFirst("#detail-schema,#episode-select,.detail-page,.detail-hero,[data-page=detail]")!=null
        e["h123_page_configuration_scope"]=d.selectFirst("script,#detail-schema,.detail-hero")!=null
        e["h123_player_config"]=(html+assets).contains("https://moviesapi.to/")
        val token=u.path.trim('/').split('/').getOrNull(1)?.substringAfterLast('-').orEmpty()
        e["h123_compatible_content_id"]=runCatching { WebsiteSourceResolver.decodeH123(token,html+assets).matches(Regex("\\d+")) }.getOrDefault(false)
        if(listOf("h123_detail_route","h123_metadata_or_structure","h123_player_config","h123_compatible_content_id","h123_page_configuration_scope").all { e[it]==true }) matches+="h123movies"
        val blocked=challenge(html);e["ambiguous_handlers"]=matches.distinct().size>1
        return Assessment(if(blocked) null else matches.distinct().singleOrNull(),loading(html),blocked,e)
    }
    fun detect(url: String,html: String,assets: String="")=assess(url,html,assets).handler
}
