package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CancellationException

/** Shared discovery/playback rules. All URLs are resolved afresh; captures are test inputs only. */
internal class WebsiteSourceResolver(
    private val get: (String,String,Map<String,String>)->String,
    private val stopped: ()->Boolean = { false },
    private val log: (String)->Unit = {},
    private val playable: (String,String)->Boolean = { _,_->true },
    private val browserPlayback: ((Media)->Media)? = null
) {
    data class Media(val page: String,val id: String,val title: String,val url: String,val referer: String,
        val subtitles: JSONObject = JSONObject(),val playerPage: String="",val playerSource: String="",val playerServer: Int=0,
        val headers: Map<String,String> = emptyMap(),val expectedSeconds: Double=0.0,val browserResolved: Boolean=false)
    var listingPage: String=""; private set
    private var requestLimit=24
    private var requests=0
    private val cache=mutableMapOf<String,String>()
    private fun read(url: String,referer: String="",headers: Map<String,String> = emptyMap()): String {
        if(stopped()) throw CancellationException("Website handler canceled")
        requireHttp(url)
        check(++requests<=requestLimit) { "Website handler request limit reached; yt-dlp fallback required" }
        return get(url,referer,headers)
    }
    private fun script(url: String,referer: String)=cache.getOrPut(url) { read(url,referer) }
    private fun loadScripts(urls: List<String>,referer: String): String = urls.mapNotNull {
        try { script(it,referer) }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { log("Website script unavailable; continuing with remaining exposed configuration");null }
    }.joinToString("\n")
    private fun page(url: String): org.jsoup.nodes.Document {
        val doc=Jsoup.parse(read(url),url)
        check(!WebsitePageFingerprint.challenge(doc.outerHtml())) { "Website requires sign-in or human verification in Browser Assist" }
        return doc
    }
    fun resolve(raw: String): Media {
        val url=normalize(raw)
        return when(site(url)) {
            "AniWaves" -> ani(url)
            "h123movies" -> h123(url)
            "WCOStream" -> wco(url)
            else -> error("No dedicated website handler")
        }
    }
    fun discover(raw: String): List<MonitoringItem> {
        val url=normalize(raw);listingPage=url;val doc=page(url);val site=site(url)
        var items=listing(url,doc.outerHtml())
        if(site=="AniWaves" && items.isEmpty()) {
            val id=doc.selectFirst(".layout-page-watchtv[data-id]")?.attr("data-id").orEmpty()
            if(id.matches(Regex("\\d+"))) {
                val result=JSONObject(read("${origin(url)}/ajax/episode/list/$id",url)).optString("result")
                items=listing(url,result)
            }
        }
        if(site=="WCOStream" && items.isEmpty() && !URI(url).path.startsWith("/anime/")) {
            val series=doc.select("a[href]").map { it.absUrl("href") }.firstOrNull {
                site(it)=="WCOStream" && URI(it).path.startsWith("/anime/")
            }
            if(series!=null) { listingPage=series;log("WCOStream: following the page's series listing");items=listing(series,read(series,url)) }
        }
        check(items.isNotEmpty()) { "$site did not expose an episode or video listing; baseline retained. Use a series/category link, or sign in using Browser Assist." }
        return items
    }
    private fun ani(url: String): Media {
        requestLimit=128
        val doc=page(url)
        val show=doc.selectFirst(".layout-page-watchtv[data-id]") ?: error("AniWaves show identity missing")
        val id=show.attr("data-id");check(id.matches(Regex("\\d+"))) { "Invalid AniWaves show identity" }
        val episode=Regex("/(?:ep-|episode/)([\\d.]+)").find(URI(url).path)?.groupValues?.get(1)
            ?: show.attr("data-ep-name").takeIf { it.matches(Regex("[\\d.]+")) }
            ?: error("Choose an individual AniWaves episode for manual download")
        val language=AniPlaybackCatalog.audio(query(url,"audio"),show.attr("data-ep-type"))
        val servers=JSONObject(read("${origin(url)}/ajax/server/list?servers=$id&eps=${enc(episode)}",url))
        val groups=AniPlaybackCatalog.parse(servers.optString("result"))
        log("AniWaves discovered options; groups="+groups.joinToString(",") { "${it.key}:${it.servers.size}" }+"; selectedAudio=$language; fresh episode response; token values omitted")
        val available=groups.firstOrNull { it.key==language }?.servers.orEmpty()
        val wanted=query(url,"server").takeUnless { it=="auto" }.orEmpty()
        val links=available.filter { wanted.isBlank() || it.name.equals(wanted,true) }
        check(wanted.isBlank() || links.isNotEmpty()) { "Selected $language server is unavailable; choose another server explicitly" }
        check(links.isNotEmpty()) { "No $language server for this AniWaves episode; use Browser Assist to choose an available version" }
        var last: Exception?=null
        for((index,server) in links.withIndex()) {
            var stage="source-request"
            try {
                log("AniWaves: resolving ${language.uppercase()} server ${server.name}; attempt=${index+1}/${links.size}; explicit=${wanted.isNotBlank()}")
                val source=JSONObject(read("${origin(url)}/ajax/sources?id=${enc(server.token)}&asi=0&autoPlay=0",url))
                    .optJSONObject("result") ?: error("AniWaves source response changed")
                val embed=source.optString("url");requireHttp(embed)
                check(URI(embed).scheme=="https") { "AniWaves player must use HTTPS" }
                stage="player-inspection"
                val player=AniPlaybackCatalog.player(embed,read(embed,url))
                log("AniWaves player matched; host=${URI(embed).host}; resolver=${player.kind}; provenance=selected_episode_server_response; URL/token values omitted")
                stage="player-sources"
                val response=JSONObject(read(player.endpoint,embed))
                val candidates=AniPlaybackCatalog.sources(response,player.kind)
                check(candidates.isNotEmpty()) { "AniWaves player returned no compatible sources" }
                stage="media-access"
                for(media in candidates) if(playable(media,embed)) {
                    return Media(url,"aniwaves:$id:$episode:$language",title(doc.title()),media,embed,tracks(response.optJSONArray("tracks"),embed))
                }
                error("Embedded playback source unavailable")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                last=e
                val category=when {
                    e.message.orEmpty().contains("Unsupported AniWaves player structure") -> "unsupported-player-structure"
                    e.message.orEmpty().contains("Ambiguous AniWaves") -> "ambiguous-source-response"
                    e.message.orEmpty().contains("no compatible sources") -> "no-compatible-media"
                    else -> WebsiteDownloadFailure.describe(e).kind
                }
                log("AniWaves server failed; audio=$language; server=${server.name}; stage=$stage; category=$category; exception=${e.javaClass.simpleName}; remaining=${links.size-index-1}; "+if(wanted.isNotBlank()) "explicit selection retained; no substitution" else "Automatic continues within selected audio group")
            }
        }
        throw IllegalStateException("AniWaves exposed servers did not resolve; yt-dlp/Browser Assist fallback required",last)
    }
    private fun wco(url: String): Media {
        val doc=page(url)
        val decoded=decodeWco(doc.html())
        val iframeDoc=Jsoup.parse(doc.html()+decoded,url)
        var embed=iframeDoc.select("iframe[src]").map { it.absUrl("src") }.firstOrNull {
            URI(it).host.orEmpty().let { h -> h=="embed.wcostream.com" } && URI(it).path.contains("/inc/embed/")
        } ?: error("WCOStream episode player not found; choose an episode, not a series index")
        var player=read(embed,url)
        if(!player.contains("getvidlink.php")) {
            val frame=Jsoup.parse(player,embed).select("iframe[src]").map { it.absUrl("src") }.firstOrNull {
                URI(it).host=="embed.wcostream.com" && URI(it).path=="/inc/embed/video-js.php"
            }
            // This player also has a documented-by-capture direct video-js route. Keep the fresh
            // file/signature query from the page; never copy the capture's temporary values.
            val next=frame ?: if(URI(embed).path=="/inc/embed/index.php") URI(embed).let {
                "${it.scheme}://${it.rawAuthority}/inc/embed/video-js.php"+(it.rawQuery?.let { q -> "?$q" } ?: "")
            } else error("WCOStream player layout changed")
            player=read(next,embed);embed=next
        }
        val endpoint=Regex("""\$\.getJSON\(\s*["']([^"']*getvidlink\.php[^"']*)["']""").find(player)?.groupValues?.get(1)
            ?: error("WCOStream player metadata route missing")
        val j=JSONObject(read(URI(embed).resolve(endpoint.replace("&amp;","&")).toString(),embed))
        val server=j.optString("server");requireHttp(server)
        check(URI(server).host.orEmpty().let { it=="wcostream.com" || it.endsWith(".wcostream.com") }) { "Unexpected WCOStream media server" }
        val subs=JSONObject()
        j.optString("sub").takeIf { it.isNotBlank() }?.let { token -> subs.put("en",JSONArray().put(JSONObject().put("url","${server.trimEnd('/')}/getvid?evid=${enc(token)}").put("ext","vtt"))) }
        for(key in listOf("fhd","hd","enc")) {
            val token=j.optString(key);if(token.isBlank()) continue
            val route="${server.trimEnd('/')}/getvid?evid=${enc(token)}&json"
            try {
                val target=org.json.JSONTokener(read(route,embed)).nextValue() as? String ?: continue
                requireHttp(target)
                check(URI(target).host.orEmpty().let { it=="wcostream.com" || it.endsWith(".wcostream.com") }) { "Unexpected WCOStream playback address" }
                check(playable(target,embed)) { "WCOStream media response unavailable" }
                log("WCOStream: fresh ${key.uppercase()} playback route obtained")
                return Media(url,"wcostream:"+URI(url).path.trim('/'),title(doc.title()),target,embed,subs)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { log("WCOStream quality route unavailable; trying next quality") }
        }
        error("WCOStream media unavailable or server at capacity; yt-dlp/Browser Assist fallback required")
    }
    private fun h123(url: String): Media {
        val doc=page(url)
        val uri=URI(url);val parts=uri.path.trim('/').split('/')
        check(parts.firstOrNull() in setOf("movie","series","tv") && parts.size>=2) { "Choose a h123movies movie or episode for manual download" }
        val ownScripts=doc.select("script[src]").map { it.absUrl("src") }.filter { URI(it).host==uri.host }.take(6)
        check(ownScripts.isNotEmpty()) { "h123movies page scripts missing" }
        // The site can move its page rules into a bundled script with an unrelated filename.
        // Inspect same-origin script text only; never execute the scripts or follow ad links.
        val config=loadScripts(ownScripts,url)
        val id=decodeH123(query(url,"id").ifBlank { parts[1].substringAfterLast('-') },config)
        check(id.matches(Regex("\\d+"))) { "h123movies title identity could not be resolved" }
        val tv=parts[0]!="movie"
        check(!tv || Regex("/episode-\\d+").containsMatchIn(uri.path) || query(url,"episode").isNotBlank()) { "Choose a specific h123movies episode for manual download" }
        val season=Regex("/season-(\\d+)").find(uri.path)?.groupValues?.get(1) ?: query(url,"season").ifBlank { "1" }
        val episode=Regex("/episode-(\\d+)").find(uri.path)?.groupValues?.get(1) ?: query(url,"episode").ifBlank { "1" }
        check(season.matches(Regex("\\d+")) && episode.matches(Regex("\\d+"))) { "Invalid episode identity" }
        val suffix=if(tv) "tv/$id/$season/$episode" else "movie/$id"
        val servers=H123ServerConfig.parse(config,suffix)
        if(servers.isEmpty()) {
            check(!Regex("""[1-6]\s*:\s*\{\s*movie\s*:""").containsMatchIn(config)) { "Website server configuration changed; no verified provider map" }
            log("h123 website server map unavailable; legacy native provider only; numbered mapping unverified")
            return h123Native(url,doc,id,tv,season,episode,suffix,config,0)
        }
        val requested=query(url,"ydlp_server").toIntOrNull()
        check(requested==null || servers.any { it.number==requested }) { "Selected website server unavailable" }
        val candidates=if(requested==null) servers else servers.filter { it.number==requested }
        log("h123 website selection; mode=${if(requested==null) "automatic" else "explicit"}; requested=${requested ?: 0}; mappedServers=${servers.size}; mapping=website player configuration")
        var last: Exception?=null
        for(server in candidates) {
            if(stopped()) throw CancellationException("Website handler canceled")
            log("h123 website server attempt; server=${server.number}; providerHost=${URI(server.url).host}; content=$suffix; other server substitution=${requested==null}")
            if(URI(server.url).host=="moviesapi.to") {
                try { return h123Native(url,doc,id,tv,season,episode,suffix,config,server.number) }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { last=e;log("h123 native preparation failed; websiteServer=${server.number}; category=${WebsiteDownloadFailure.describe(e).kind}; trying bounded background player for same website server") }
            }
            val seed=Media(url,"h123:${if(tv) "tv:$id:$season:$episode" else "movie:$id"}",
                title(doc.title()).takeUnless { it.contains("Loading",true) } ?: URI(url).path.trim('/').split('/')[1].substringBeforeLast('-').replace('-',' '),
                "",server.url,playerPage=server.url,playerSource="website:${server.number}",playerServer=server.number)
            try {
                val recover=browserPlayback ?: error("Background player unavailable; use Browser Assist")
                return recover(seed)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { last=e;log("h123 website server failed; server=${server.number}; stage=background-player; category=${WebsiteDownloadFailure.describe(e).kind}; next=${if(requested==null) "next website server" else "none; explicit selection preserved"}") }
        }
        throw IllegalStateException("Selected website playback failed",last)
    }
    private fun h123Native(url: String,doc: org.jsoup.nodes.Document,id: String,tv: Boolean,season: String,episode: String,suffix: String,config: String,websiteServer: Int): Media {
        check(config.contains("https://moviesapi.to/")) { "h123movies player configuration changed" }
        val embed="https://moviesapi.to/$suffix"
        val wrapper=Jsoup.parse(read(embed,url),embed)
        val js=wrapper.select("script[src]").map { it.absUrl("src") }.filter { URI(it).host=="moviesapi.to" && URI(it).path.startsWith("/assets/") }.take(3)
            .let { loadScripts(it,embed) }
        val cdn=Regex("https://cdn\\.vidspark\\.to").find(js)?.value ?: error("h123movies embedded player route changed")
        val playerUrl="$cdn/$suffix"
        val player=Jsoup.parse(read(playerUrl,embed),playerUrl)
        val playerScript=player.select("script[src]").map { it.absUrl("src") }.filter { URI(it).host==URI(cdn).host && URI(it).path.startsWith("/assets/") }.take(3)
            .let { loadScripts(it,playerUrl) }
        // Read the same public player configuration used by this session; no saved HAR token is shipped.
        val keyVariable=Regex("""["']?x-player-key["']?\s*:\s*([\w$]+)""").find(playerScript)?.groupValues?.get(1)
            ?: error("Player request configuration unavailable")
        val key=Regex("""(?:var |let |const |,)"""+Regex.escape(keyVariable)+"""\s*=\s*[`"']([^`"']+)[`"']""").find(playerScript)?.groupValues?.get(1)
            ?: error("Player request configuration changed")
        val sources=Regex("""key\s*:\s*[`"']([A-Za-z0-9_-]+)[`"']\s*,\s*label""").findAll(playerScript).map { it.groupValues[1] }.distinct().take(12).toList()
        check(sources.isNotEmpty()) { "Player server list unavailable" }
        val requested=if(websiteServer>0) null else query(url,"ydlp_server").toIntOrNull()
        check(requested==null || requested in 1..sources.size) { "Selected server unavailable; choose another server explicitly" }
        val candidates=if(websiteServer>0) sources.take(3) else if(requested==null) sources else listOf(sources[requested-1])
        log("h123 native source list; websiteServer=$websiteServer; backendSources=${sources.size}; selectionMode=${if(requested==null) "automatic within provider" else "legacy internal index"}")
        var lastFailure: Exception?=null
        for((position,source) in candidates.withIndex()) {
            var stage="player-request"
            try {
                val body=read("$cdn/api/vidora/v1/$suffix?source=${enc(source)}",playerUrl,mapOf("x-player-key" to key))
                stage="player-response"
                val response=JSONObject(body)
                log("h123movies server ${sources.indexOf(source)+1} response; websiteServer=$websiteServer; backendIndex=${sources.indexOf(source)+1}; result=${response.optBoolean("result")}; mediaEntries=${response.optJSONArray("sources")?.length() ?: 0}; payload values omitted")
                check(response.optBoolean("result")) { "Player source not available" }
                check(!response.has("tmdb_id") || response.optString("tmdb_id")==id) { "Player returned a different title identity" }
                stage="media-entry"
                val entry=response.optJSONArray("sources")?.optJSONObject(0) ?: error("No playable source")
                val media=entry.optString("url").ifBlank { entry.optString("file") };requireHttp(media)
                stage="media-access"
                check(playable(media,playerUrl)) { "Player source did not return media" }
                log("h123movies: fresh playback from exposed server ${sources.indexOf(source)+1}")
                return Media(url,"h123:${if(tv) "tv:$id:$season:$episode" else "movie:$id"}",response.optString("title").ifBlank { title(doc.title()).takeUnless { it.contains("Loading",true) } ?: URI(url).path.trim('/').split('/')[1].substringBeforeLast('-').replace('-',' ') },media,playerUrl,tracks(entry.optJSONArray("tracks"),playerUrl)).copy(playerPage=playerUrl,playerSource=source,playerServer=if(websiteServer>0) websiteServer else sources.indexOf(source)+1)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                lastFailure=e
                val reason=WebsiteDownloadFailure.describe(e)
                val next=if(position+1<candidates.size) "trying next exposed server" else if(requested!=null) "selected server failed; no other server substituted" else "no further exposed servers"
                log("h123movies server ${sources.indexOf(source)+1} failed; websiteServer=$websiteServer; backendIndex=${sources.indexOf(source)+1}; stage=$stage; category=${reason.kind}; ${reason.detail}; $next")
            }
        }
        throw IllegalStateException("h123movies exposed servers did not resolve; yt-dlp/Browser Assist fallback required",lastFailure)
    }

    companion object {
        fun site(raw: String): String? = runCatching { WebsiteDomains.site(normalize(raw)) }.getOrNull()
        fun normalize(raw: String): String = raw.trim().let { if(it.contains("://")) it else "https://$it" }.also { requireHttp(it) }
        fun requireHttp(url: String) {
            val u=URI(url)
            require(u.scheme in setOf("https","http") && !u.host.isNullOrBlank() && u.userInfo==null && !url.contains('\n') && !url.contains('\r')) { "Invalid website/playback address" }
        }
        private fun origin(url: String)=URI(url).let { "${it.scheme}://${it.rawAuthority}" }
        private fun enc(value: String)=URLEncoder.encode(value,"UTF-8")
        private fun query(url: String,key: String)=URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=')==key }?.substringAfter('=',"")?.let { java.net.URLDecoder.decode(it,"UTF-8") }.orEmpty()
        private fun title(value: String)=value.substringBefore(" | ").removePrefix("Aniwave - ").trim()
        fun decodeH123(raw: String,script: String): String {
            if(raw.matches(Regex("\\d+"))) return raw
            val map=Regex("""Object\.freeze\(\{(0\s*:\s*["'][^}]+)\}\)""").find(script)?.groupValues?.get(1) ?: return ""
            val pairs=Regex("""(\d)\s*:\s*["']([^"'])["']""").findAll(map).associate { it.groupValues[2][0] to it.groupValues[1] }
            if(pairs.size!=10) return ""
            val code=raw.removePrefix("x")
            return code.map { pairs[it] ?: return "" }.joinToString("")
        }
        fun decodeWco(html: String): String {
            val block=Regex("""(?s)<script[^>]*>([^<]*String\.fromCharCode[^<]*document\.write[^<]*)</script>""").find(html)?.groupValues?.get(1) ?: return ""
            val array=Regex("""(?s)var\s+\w+\s*=\s*(\[[^\]]+\])""").find(block)?.groupValues?.get(1) ?: return ""
            val offset=Regex("""\)\s*-\s*(\d+)\s*\)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return ""
            val a=JSONArray(array);check(a.length()<=20000) { "WCOStream player markup too large" }
            return (0 until a.length()).map { i ->
                val digits=String(Base64.getDecoder().decode(a.getString(i)),Charsets.UTF_8).filter(Char::isDigit)
                val code=digits.toInt()-offset;check(code in 0..65535);code.toChar()
            }.joinToString("")
        }
        fun listing(raw: String,html: String): List<MonitoringItem> {
            val url=normalize(raw);val which=site(url) ?: return emptyList();val doc=Jsoup.parse(html,url);val path=URI(url).path
            val root=if(which=="AniWaves") doc.selectFirst(".layout-page-watchtv")?.attr("data-id").orEmpty() else ""
            val showSlug=if(which=="AniWaves") path.substringAfter("/watch/").substringBefore('/').substringAfterLast('-') else ""
            val selected=when(which) {
                "AniWaves" -> doc.select(".episodes a[href][data-num], a[href][data-ids][data-num]")
                "h123movies" -> if(path.startsWith("/series/") || path.startsWith("/tv/")) doc.select("#episode-select a[href][data-episode], a[href][data-episode]")
                    else if(!path.startsWith("/movie/")) doc.select("article.movie-card a.movie-card-link[href]") else org.jsoup.select.Elements()
                else -> if(path.startsWith("/anime/")) doc.select("a[href]") else org.jsoup.select.Elements()
            }
            val found=selected.mapNotNull { a ->
                if(a.parents().any { Regex("(?i)(sidebar|recommend|related|advert|footer|comment)").containsMatchIn(it.id()+" "+it.className()) }) return@mapNotNull null
                val target=runCatching { URI(a.absUrl("href").substringBefore('#')) }.getOrNull() ?: return@mapNotNull null
                if(site(target.toString())!=which || target.scheme !in setOf("http","https")) return@mapNotNull null
                var itemUrl=target.toString();var identity=""
                when(which) {
                    "AniWaves" -> {
                        if(a.attr("enabled")=="0") return@mapNotNull null
                        val show=target.path.substringAfter("/watch/").substringBefore('/').substringAfterLast('-')
                        if((root.ifBlank { showSlug }).isNotBlank() && show!=root.ifBlank { showSlug }) return@mapNotNull null
                        val ep=a.attr("data-num").takeIf { it.matches(Regex("[\\d.]+")) } ?: return@mapNotNull null
                        val lang=AniPlaybackCatalog.audio(query(url,"audio"),"")
                        if(lang=="dub" && a.attr("data-dub")!="1") return@mapNotNull null
                        if(a.hasAttr("data-$lang") && a.attr("data-$lang")!="1") return@mapNotNull null
                        itemUrl="${origin(url)}/watch/$show/ep-$ep?audio=$lang&server=${enc(query(url,"server").ifBlank { "auto" })}"
                        identity="aniwaves:$show:$ep:$lang"+query(url,"server").takeIf { it.isNotBlank() && it!="auto" }?.let { ":$it" }.orEmpty()
                    }
                    "h123movies" -> {
                        if(path.startsWith("/series/") || path.startsWith("/tv/")) {
                            val scope=path.trim('/').split('/').take(2).joinToString("/")
                            if(!target.path.trim('/').startsWith("$scope/") || !Regex("/episode-\\d+").containsMatchIn(target.path)) return@mapNotNull null
                        } else if(!Regex("^/(?:movie|series)/[^/]+/?$").matches(target.path)) return@mapNotNull null
                        identity="h123:"+target.path.trim('/');itemUrl=target.toString()
                        query(url,"ydlp_server").takeIf(String::isNotBlank)?.let { server -> itemUrl+=(if(itemUrl.contains('?')) "&" else "?")+"ydlp_server="+enc(server);identity+=":$server" }
                    }
                    else -> {
                        if(!Regex("(?i)(?:episode[-/ ]?\\d|[-/]ep[-/]?\\d)").containsMatchIn(target.path)) return@mapNotNull null
                        identity="wcostream:"+target.path.trim('/')
                    }
                }
                val date=runCatching { java.time.OffsetDateTime.parse(a.attr("data-aired")).toInstant().toEpochMilli() }.getOrDefault(0)
                MonitoringItem(identity,itemUrl,a.text().ifBlank { a.attr("title") },date)
            }.distinctBy { it.id }
            val chronological=which!="h123movies" || path.startsWith("/series/") || path.startsWith("/tv/")
            return (if(chronological) found.sortedByDescending { item ->
                val p=URI(item.url).path
                val season=Regex("(?i)season[-/]?(\\d+)").find(p)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                val episode=Regex("(?i)(?:episode[-/]|ep-)([\\d.]+)").find(p)?.groupValues?.get(1)?.trimEnd('.')?.toDoubleOrNull() ?: 0.0
                season*1_000_000+episode
            } else found).take(200)
        }
        fun individual(raw: String): Boolean = runCatching {
            val path=URI(normalize(raw)).path
            when(site(raw)) {
                "AniWaves" -> Regex("/(?:ep-|episode/)[\\d.]+").containsMatchIn(path)
                "h123movies" -> path.startsWith("/movie/") || Regex("/episode-\\d+").containsMatchIn(path) || query(raw,"episode").isNotBlank()
                "WCOStream" -> path.isNotBlank() && path!="/" && !path.startsWith("/anime/")
                else -> false
            }
        }.getOrDefault(false)
        private fun tracks(a: JSONArray?,base: String): JSONObject {
            val result=JSONObject()
            for(i in 0 until (a?.length() ?: 0)) {
                val t=a!!.optJSONObject(i) ?: continue
                if(t.optString("kind") !in setOf("captions","subtitles","")) continue
                val raw=t.optString("file").ifBlank { t.optString("url").ifBlank { t.optString("src") } }
                if(raw.isBlank()) continue
                val url=runCatching { URI(base).resolve(raw).toString().also(::requireHttp) }.getOrNull() ?: continue
                val lang=t.optString("srclang").ifBlank { t.optString("language").ifBlank { t.optString("label").ifBlank { "und" } } }
                val list=result.optJSONArray(lang) ?: JSONArray().also { result.put(lang,it) }
                list.put(JSONObject().put("url",url).put("ext",if(URI(url).path.endsWith(".srt",true)) "srt" else "vtt"))
            }
            return result
        }
    }
}
