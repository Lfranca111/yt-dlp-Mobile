package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.CancellationException
import com.linfranca.ytdlpmobile.CaptureSiteRoutes.Site

/** Only use playback data tied to the submitted post or channel. Never reuse capture credentials. */
internal object CaptureSiteHandlers {
    private const val AGENT="Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val ROUTE_TAG="_app_capture_source"
    private val nativeVideo=setOf(Site.ARCHIVE,Site.DAILY,Site.NG,Site.PEER,Site.RU,Site.VIMEO)
    private fun enc(s: String)=URLEncoder.encode(s,"UTF-8")
    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.orEmpty()
    private fun date(s: String)=runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
    private fun https(url: String, vararg roots: String): Boolean {
        val u=CaptureSiteRoutes.web(url) ?: return false
        return u.scheme.equals("https",true) && roots.any { CaptureSiteRoutes.domain(CaptureSiteRoutes.host(u),it) }
    }

    private class Reader(private val stop: ()->Boolean, private val log: (String)->Unit,
        val agent: String=AGENT, private val session: MediaCookieJar=MediaCookieJar()) {
        private val deadline=System.nanoTime()+25_000_000_000L
        fun read(url: String, stage: String, page: String="", extra: Map<String,String> = emptyMap(),
            payload: String?=null, contentType: String="application/json"): String {
            val uri=CaptureSiteRoutes.web(url) ?: error("Invalid playback address")
            require(uri.scheme=="https")
            val connection=uri.toURL().openConnection() as HttpURLConnection
            fun active() {
                if(stop()) throw CancellationException("Playback check canceled")
                check(System.nanoTime()<deadline) { "Playback check timed out" }
            }
            try {
                active()
                val timeout=((deadline-System.nanoTime())/1_000_000).coerceIn(1,6500).toInt()
                connection.connectTimeout=timeout
                connection.readTimeout=timeout
                connection.instanceFollowRedirects=false
                connection.setRequestProperty("User-Agent",agent)
                connection.setRequestProperty("Cache-Control","no-cache")
                MediaRequestPolicy.referrer(page,url).takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Referer",it) }
                session.header(url).takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Cookie",it) }
                extra.forEach { (name,value)->connection.setRequestProperty(name,value) }
                if(payload!=null) {
                    connection.requestMethod="POST"
                    connection.setRequestProperty("Content-Type",contentType)
                    connection.doOutput=true
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }
                val status=connection.responseCode
                log("Captured-site check; stage=$stage; HTTP=$status; request values omitted")
                check(status in 200..299) { "Playback endpoint returned HTTP $status" }
                session.accept(url,connection.headerFields.entries.filter { it.key!=null }.associate { it.key!! to it.value })
                val out=ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer=ByteArray(8192)
                    while(true) {
                        active()
                        val n=input.read(buffer)
                        if(n<0) break
                        check(out.size()+n<=4_000_000) { "Playback response exceeds size limit" }
                        out.write(buffer,0,n)
                    }
                }
                return out.toString("UTF-8")
            } finally { connection.disconnect() }
        }
        fun json(url: String,stage: String,page: String="",extra: Map<String,String> = emptyMap()) =
            JSONObject(read(url,stage,page,extra))
    }

    private fun downloadBinding(url: String): String? {
        val route=CaptureSiteRoutes.video(url) ?: return null
        return route.site.name+":"+route.id
    }
    private fun savedPlan(folder: File,url: String): JSONObject? = runCatching {
        val file=File(folder,"browser-selection.json")
        if(!file.isFile || file.length()>=1_000_000) return@runCatching null
        JSONObject(file.readText()).takeIf { it.optString(ROUTE_TAG)==downloadBinding(url) }
    }.getOrNull()
    fun useSoopParts(folder: File,url: String): Boolean {
        if(!CaptureSiteRoutes.isSoopVod(url)) return false
        val binding=downloadBinding(url) ?: return false
        val marker=File(folder,PartialDownloadCache.DIRECTORY+"/capture-soop-source")
        return runCatching {
            if(marker.isFile) return@runCatching marker.readText()==binding
            if(folder.list()?.isEmpty()!=true) return@runCatching false
            check(marker.parentFile?.mkdirs()==true || marker.parentFile?.isDirectory==true)
            marker.writeText(binding)
            true
        }.getOrDefault(false)
    }

    fun prepare(context: Context,folder: File,url: String,cookieFile: String,agent: String,stopped: ()->Boolean,log: (String)->Unit): WebsiteHandlerBridge.Prepared? {
        val r=CaptureSiteRoutes.video(url) ?: return null
        if(r.site !in nativeVideo) return null
        val previous=savedPlan(folder,url)
        if(previous==null && folder.list()?.isEmpty()!=true) return null
        return try {
            val session=MediaCookieJar()
            if(cookieFile.isNotBlank()) File(cookieFile).takeIf { it.isFile && it.length()<=4_000_000 }?.let { session.load(it.readText()) }
            val read=Reader(stopped,log,agent.ifBlank { AGENT },session)
            var title="${r.site.label} ${r.id}"
            var seconds=0.0
            val media=when(r.site) {
                Site.ARCHIVE -> {
                    val j=read.json("https://archive.org/metadata/${r.id}","Internet Archive item files",url)
                    val metadata=j.getJSONObject("metadata")
                    check(metadata.optString("identifier")==r.id && metadata.optString("mediatype")=="movies" &&
                        metadata.optString("access-restricted-item").lowercase() !in setOf("true","1","yes"))
                    val file=j.objects("files").filter { it.optString("source")=="original" }
                        .map { it.optString("name") }.single { it.endsWith(".mp4",true) }
                    check(file.split('/').all { it.isNotBlank() && it !in setOf(".","..") })
                    title=metadata.optString("title").ifBlank { title }
                    "https://archive.org/download/${r.id}/"+file.split('/').joinToString("/") { enc(it).replace("+","%20") }
                }
                Site.DAILY -> {
                    val j=read.json("https://geo.dailymotion.com/videos/${r.id}","Dailymotion playback",url)
                    check(j.optString("id")==r.id)
                    val stream=j.getJSONObject("stream")
                    check(stream.optString("stream_type")=="recorded")
                    try {
                        val detail=read.json("https://geo.dailymotion.com/videos/${r.id}/details","Dailymotion video title",url)
                        if(detail.optString("id")==r.id) title=detail.optJSONObject("info")?.optString("title").orEmpty().ifBlank { title }
                    } catch(e: CancellationException) { throw e } catch(_: Exception) { /* optional title */ }
                    stream.getString("url").also { check(https(it,"dailymotion.com","dmcdn.net")) }
                }
                Site.NG -> {
                    val j=read.json("https://www.newgrounds.com/portal/video/${r.id}","Newgrounds playback",url,mapOf("X-Requested-With" to "XMLHttpRequest"))
                    check(j.optString("id")==r.id)
                    title=j.optString("title").ifBlank { title }
                    val s=j.getJSONObject("sources")
                    s.keys().asSequence().sortedByDescending { it.removeSuffix("p").toIntOrNull() ?: 0 }
                        .flatMap { s.objects(it).asSequence() }.map { it.optString("src") }
                        .first { https(it,"ungrounded.net") }
                }
                Site.PEER -> {
                    val host=CaptureSiteRoutes.host(CaptureSiteRoutes.web(url)!!)
                    val j=read.json("https://$host/api/v1/videos/${r.id}","PeerTube playback",url)
                    check(r.id in setOf(j.optString("shortUUID"),j.optString("uuid")) && j.opt("isLive")==false)
                    check(j.optJSONObject("privacy")?.optInt("id")==1)
                    title=j.optString("name").ifBlank { title }
                    seconds=j.optDouble("duration",0.0)
                    j.objects("streamingPlaylists").map { it.optString("playlistUrl") }
                        .first { https(it,"peer.tube","lostpod.space") }
                }
                Site.RU -> {
                    val j=read.json("https://rutube.ru/api/play/options/${r.id}/?no_404=true","RuTube playback",url)
                    check(j.optString("video_id").equals(r.id,true) && j.optString("effective_video").equals(r.id,true))
                    check(j.optJSONObject("acl_access")?.optBoolean("allowed")==true && j.isNull("drm_token") &&
                        j.optJSONObject("live_streams")?.length()==0)
                    title=j.optString("title").ifBlank { title }
                    seconds=j.optDouble("duration",0.0)/1000
                    j.getJSONObject("video_balancer").getString("m3u8").also { check(https(it,"rutube.ru","rtbcdn.ru")) }
                }
                Site.VIMEO -> {
                    val uri=CaptureSiteRoutes.web(url)!!
                    check(CaptureSiteRoutes.path(uri).substringAfterLast('/')==r.id &&
                        uri.rawQuery.orEmpty().split('&').none { it.substringBefore('=').equals("h",true) })
                    val j=read.json("https://player.vimeo.com/video/${r.id}/config","Vimeo playback",url)
                    val v=j.getJSONObject("video")
                    check(v.optString("id")==r.id && v.isNull("live_event"))
                    title=v.optString("title").ifBlank { title }
                    seconds=v.optDouble("duration",0.0)
                    val files=j.getJSONObject("request").getJSONObject("files")
                    val hls=files.optJSONObject("hls")
                    val candidate=hls?.let { it.optJSONObject("cdns")?.optJSONObject(it.optString("default_cdn"))?.optString("url") }.orEmpty()
                    if(https(candidate,"vimeocdn.com","vimeo.com")) candidate else files.objects("progressive")
                        .sortedByDescending { it.optInt("height") }.map { it.optString("url") }.first { https(it,"vimeocdn.com","vimeo.com") }
                }
                else -> error("No public playback adapter")
            }
            val roots=when(r.site) {
                Site.ARCHIVE -> arrayOf("archive.org")
                Site.DAILY -> arrayOf("dailymotion.com","dmcdn.net")
                Site.NG -> arrayOf("ungrounded.net")
                Site.PEER -> arrayOf("peer.tube","lostpod.space")
                Site.RU -> arrayOf("rutube.ru","rtbcdn.ru")
                Site.VIMEO -> arrayOf("vimeo.com","vimeocdn.com")
                else -> error("Unrecognized media scope")
            }
            val info=VerifiedMediaAccess(url,read.agent,stopped=stopped,session=session,budgetMs=18_000,
                allowedUrl={ https(it,*roots) })
                .plan(media,url,previous?.optString("title").orEmpty().ifBlank { title },seconds)
                .put("id",r.id).put(ROUTE_TAG,downloadBinding(url))
            val jar=File.createTempFile("capture-site-",".cookies",context.cacheDir)
            try { jar.writeText(session.netscape()) } catch(e: Exception) { jar.delete();throw e }
            log("${r.site.label}: verified recorded video plan; using existing transfer engine")
            WebsiteHandlerBridge.Prepared(info.toString(),jar)
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            if(stopped()) throw CancellationException("Playback check canceled")
            if(previous!=null && hasRetainedMedia(folder))
                throw IllegalStateException("Could not refresh the selected playback source. Retained media was kept for retry.",e)
            log("${r.site.label}: direct playback unavailable (${e.javaClass.simpleName}); trying the original URL with yt-dlp")
            null
        }
    }

    fun hasRetainedMedia(folder: File): Boolean =
        PartialDownloadCache.recoverable(folder).isNotEmpty() ||
            File(folder,PartialDownloadCache.DIRECTORY+"/objects").listFiles().orEmpty().any { it.extension=="bin" }

    private fun odyRpc(read: Reader,page: String,method: String,params: JSONObject): JSONObject {
        val query=JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method).put("params",params)
        return JSONObject(read.read("https://api.na-backend.odysee.com/api/v1/proxy",
            "Odysee $method",page,payload=query.toString())).getJSONObject("result")
    }

    private fun odyChannel(read: Reader,page: String,slug: String): String {
        val key="lbry://"+slug.replaceFirst(':','#')
        val claim=odyRpc(read,page,"resolve",JSONObject().put("urls",JSONArray().put(key))).getJSONObject(key)
        val channel=claim.getString("claim_id")
        check(channel.matches(Regex("[a-f0-9]{40}")) && claim.optString("value_type")=="channel")
        return channel
    }

    fun uploads(url: String, stopped: ()->Boolean,log: (String)->Unit): MonitoringResult? {
        val site=CaptureSiteRoutes.site(url) ?: return null
        val read=Reader(stopped,log)
        val items=when(site) {
            Site.DAILY -> {
                val slug=CaptureSiteRoutes.path(CaptureSiteRoutes.web(url)!!).substringAfterLast('/')
                val j=read.json("https://api.dailymotion.com/user/$slug?fields=id,username","Dailymotion creator scope",url)
                val owner=j.getString("id")
                check(owner.matches(Regex("[A-Za-z0-9]+")) && (owner==slug || j.optString("username").equals(slug,true)))
                val api="https://api.dailymotion.com/user/$owner/videos?fields=id,title,owner,created_time,mode,private,published&limit=40&sort=recent"
                read.json(api,"Dailymotion creator uploads",url).objects("list")
                    .filter { it.optString("owner")==owner && it.optString("mode")=="vod" && it.opt("private")==false && it.opt("published")==true }
                    .mapNotNull { row ->
                        val id=row.optString("id")
                        val link="https://www.dailymotion.com/video/$id"
                        if(CaptureSiteRoutes.uploadId(link,site)!=id) return@mapNotNull null
                        MonitoringItem(itemId(site,id),link,row.optString("title"),row.optLong("created_time").coerceIn(0,100_000_000_000)*1000)
                    }
            }
            Site.ODYSEE -> {
                val slug=CaptureSiteRoutes.live(url)?.id ?: return null
                val channel=odyChannel(read,url,slug)
                val params=JSONObject().put("channel_ids",JSONArray().put(channel))
                    .put("stream_types",JSONArray().put("video")).put("claim_type","stream")
                    .put("order_by",JSONArray().put("release_time")).put("page",1).put("page_size",40)
                odyRpc(read,url,"claim_search",params).objects("items").mapNotNull { row ->
                    val signer=row.optJSONObject("signing_channel")
                    val value=row.optJSONObject("value") ?: return@mapNotNull null
                    if(signer?.optString("claim_id")!=channel || row.opt("is_channel_signature_valid")!=true ||
                        value.optString("stream_type")!="video" || value.optJSONObject("source")==null) return@mapNotNull null
                    val claim=row.optString("claim_id")
                    val name=row.optString("name")
                    if(!claim.matches(Regex("[a-f0-9]{40}")) || !name.matches(Regex("[A-Za-z0-9_.()%-]+"))) return@mapNotNull null
                    val creator=slug.substringBefore(':')
                    val link="https://odysee.com/$creator:$channel/$name:$claim"
                    if(CaptureSiteRoutes.uploadId(link,site)==null) return@mapNotNull null
                    MonitoringItem(itemId(site,claim),link,value.optString("title"),value.optLong("release_time").coerceIn(0,100_000_000_000)*1000)
                }
            }
            Site.RU -> {
                val seed=CaptureSiteRoutes.video(url)?.id ?: return null
                val base="https://rutube.ru/pangolin/api/web/serial/$seed"
                val series=read.json(base,"RuTube season scope",url)
                val season=series.optInt("current_season",-1)
                check(season>0 && series.objects("results").any { it.optInt("number")==season })
                val result=read.json("$base/$season/?limit=40&offset=0","RuTube season videos",url).objects("results")
                // The seed proves this is the requested season. If it falls outside the returned page, do not automate.
                check(result.any { it.optString("id").equals(seed,true) })
                result.filter { it.opt("is_livestream")==false && it.opt("is_paid")!=true }.mapNotNull { row ->
                    val link=row.optString("video_url")
                    val id=CaptureSiteRoutes.uploadId(link,site) ?: return@mapNotNull null
                    if(!id.equals(row.optString("id"),true)) return@mapNotNull null
                    MonitoringItem(itemId(site,id.lowercase()),link,row.optString("title"),date(row.optString("publication_ts")))
                }
            }
            Site.KICK -> {
                val slug=CaptureSiteRoutes.live(url)?.id ?: return null
                val channel=read.json("https://kick.com/api/v2/channels/$slug","Kick channel scope",url)
                check(channel.optString("slug").equals(slug,true))
                val number=channel.getLong("id")
                read.json("https://web.kick.com/api/v1/channels/$number/videos","Kick channel recordings",url)
                    .objects("data").filter { it.opt("is_live")==false && it.optString("status")=="public" }
                    .mapNotNull { row ->
                        val owner=row.optJSONObject("channel") ?: return@mapNotNull null
                        if(owner.optLong("id")!=number || !owner.optString("slug").equals(slug,true)) return@mapNotNull null
                        val link="https://kick.com/$slug/videos/${row.optString("id")}"
                        val id=CaptureSiteRoutes.uploadId(link,site) ?: return@mapNotNull null
                        MonitoringItem(itemId(site,id),link,row.optString("title"),date(row.optString("start_time")))
                    }
            }
            Site.PEER -> {
                val uri=CaptureSiteRoutes.web(url) ?: return null
                val host=CaptureSiteRoutes.host(uri)
                val scope=CaptureSiteRoutes.path(uri).split('/').getOrNull(2) ?: return null
                check(scope.matches(Regex("[a-zA-Z0-9_.-]+(?:@[a-zA-Z0-9.-]+)?")))
                val owner=scope.substringBefore('@')
                val home=scope.substringAfter('@',host)
                val api="https://$host/api/v1/video-channels/$scope/videos?start=0&count=40&sort=-publishedAt"
                read.json(api,"PeerTube channel videos",url).objects("data")
                    .filter { it.opt("isLive")==false && it.optJSONObject("privacy")?.optInt("id")==1 }
                    .mapNotNull { row ->
                        val channel=row.optJSONObject("channel") ?: return@mapNotNull null
                        if(!channel.optString("name").equals(owner,true) || !channel.optString("host").equals(home,true)) return@mapNotNull null
                        val id=row.optString("shortUUID")
                        val link="https://$host/w/$id"
                        if(CaptureSiteRoutes.uploadId(link,site)!=id) return@mapNotNull null
                        MonitoringItem(itemId(site,id),link,row.optString("name"),date(row.optString("publishedAt")))
                    }
            }
            else -> return null
        }.distinctBy { it.id }.take(40)
        check(items.isNotEmpty()) { "No verified public uploads found; prior baseline kept" }
        return MonitoringResult(items=items,detail="${site.label}: ${items.size} verified public ${if(site==Site.RU) "episodes in this season" else "uploads"}")
    }

    fun vkReplays(url: String,agent: String,cookie: (String)->String,stopped: ()->Boolean,log: (String)->Unit): MonitoringResult {
        val read=Reader(stopped,log,agent.ifBlank { AGENT })
        val listing=VkReplayListing.discover(url,stopped,{ page ->
            val label=if(page.endsWith("/records")) "Records tab" else "channel page"
            val extra=cookie(page).takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty()
            read.read(page,"VK Video Live replay listing ($label)",extra=extra)
        },log)
        return MonitoringResult(items=listing.items,detail="VK Video Live: ${listing.items.size} verified finished replays")
    }

    fun rumbleUploads(url: String, info: JSONObject?, cookie: String, stopped: ()->Boolean,log: (String)->Unit): List<MonitoringItem> {
        val target=CaptureSiteRoutes.uploads(url)?.takeIf { CaptureSiteRoutes.site(it)==Site.RUMBLE }
            ?: return emptyList()
        val headers=if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
        return RumbleMonitoring.fromPage(target,Reader(stopped,log).read(target,"Rumble creator listing",extra=headers),info)
    }

    fun preview(url: String,site: Site,stopped: ()->Boolean,log: (String)->Unit): List<MonitoringItem> {
        // Visible links are suggestions only: a creator page can also contain recommendations.
        if(!https(url,URI(url).host)) return emptyList()
        val doc=Jsoup.parse(Reader(stopped,log).read(url,"${site.label} listing preview"),url)
        val content=doc.selectFirst("main, [role=main]") ?: return emptyList()
        return content.select("a[href]").asSequence().mapNotNull { anchor ->
            val link=anchor.absUrl("href")
            val id=CaptureSiteRoutes.uploadId(link,site) ?: return@mapNotNull null
            val title=anchor.attr("title").ifBlank { anchor.attr("aria-label") }.ifBlank { anchor.text() }
            MonitoringItem(itemId(site,id),link,title.take(160))
        }.distinctBy { it.id }.take(40).toList()
    }

    fun itemId(site: Site,id: String)="Capture:${site.name}:$id"

    fun live(url: String,stopped: ()->Boolean,log: (String)->Unit,rumbleOwner: String? = null): MonitoringResult? {
        val r=CaptureSiteRoutes.live(url) ?: return null
        val read=Reader(stopped,log)
        val headers=mapOf("User-Agent" to AGENT)
        return when(r.site) {
            Site.ODYSEE -> {
                val channel=odyChannel(read,url,r.id)
                val state=JSONObject(read.read("https://api.odysee.live/livestream/is_live","Odysee live state",url,
                    payload="channel_claim_id="+enc(channel),contentType="application/x-www-form-urlencoded"))
                check(state.optBoolean("success"))
                val data=state.getJSONObject("data")
                if(data.opt("Live")==false && data.optString("ChannelClaimID",channel).ifBlank { channel }==channel)
                    return MonitoringResult(offline=true,detail="Odysee confirms the channel is offline")
                check(data.opt("Live")==true && data.optString("ChannelClaimID")==channel)
                val active=data.getJSONObject("ActiveClaim")
                check(active.opt("Protected")==false)
                val id=active.getString("ClaimID")
                check(id.matches(Regex("[a-f0-9]{40}")))
                val media=data.getString("VideoURL")
                check(https(media,"odysee.live"))
                MonitoringResult(live=MonitoringItem(itemId(r.site,id),url),detail="Odysee public live broadcast confirmed",video=media,videoHeaders=headers)
            }
            Site.RUMBLE -> {
                if(r.id.contains('/')) return null
                val html=read.read(url,"Rumble live post")
                val match=Regex("""Rumble\(\s*["']play["']\s*,\s*(?=\{)""").find(html) ?: return null
                // The player call is JavaScript, so it can contain expressions after these literal fields.
                val tail=html.substring(match.range.last+1).substringBefore("</script>").take(4096)
                val id=Regex("""["']video["']\s*:\s*["'](v[a-zA-Z0-9]+)["']\s*,\s*["']div["']\s*:\s*["']videoPlayer["']""")
                    .findAll(tail).map { it.groupValues[1] }.distinct().singleOrNull() ?: return null
                val config=read.json("https://rumble.com/embedJS/u3/?ifr=0&request=video&ver=2&v=$id","Rumble live player",url)
                if(config.optInt("live")!=2) return null
                if(rumbleOwner!=null) {
                    val author=config.optJSONObject("author")?.optString("url").orEmpty()
                    if(author.isBlank()) return null
                    val owner=CaptureSiteRoutes.live(URI(url).resolve(author).toString())
                    if(owner?.site!=Site.RUMBLE || !owner.id.equals(rumbleOwner,true)) return null
                }
                val media=config.getJSONObject("u").getJSONObject("hls").getString("url")
                check(https(media,"rumble.com","rumble.cloud"))
                val post=CaptureSiteRoutes.uploadId(url,r.site) ?: return null
                MonitoringResult(live=MonitoringItem(itemId(r.site,post),url,config.optString("title")),detail="Rumble public live broadcast confirmed",video=media,videoHeaders=headers)
            }
            Site.VK -> {
                val sources=sequence {
                    val endpoint="https://api.vkplay.live/v1/blog/${r.id}/public_video_stream"
                    yield(runVkCheck(log,"channel API") {
                        val api=read.json(endpoint,"VK Video Live channel API",url)
                        VkLivePlayback.fromStream(api,true,::isVkDash)
                    })
                    yield(runVkCheck(log,"page state") {
                        val html=read.read(url,"VK Video Live channel")
                        val script=Jsoup.parse(html).getElementById("initial-state") ?: return@runVkCheck null
                        val state=JSONObject(script.data().ifBlank { script.text() }).getJSONObject("stream")
                        if(state.optBoolean("isStreamEndedViaWs")) return@runVkCheck null
                        val stream=state.optJSONObject("stream")?.optJSONObject("data")?.optJSONObject("stream")
                            ?: return@runVkCheck null
                        VkLivePlayback.fromStream(stream,false,::isVkDash)
                    })
                }
                val checked=mutableSetOf<String>()
                for(candidate in sources.filterNotNull()) {
                    for(media in candidate.urls.filter(checked::add)) {
                        val access=runVkCheck(log,"DASH playlist") { verifyVkDash(media,url,AGENT,stopped,log) }
                            ?: continue
                        return MonitoringResult(live=MonitoringItem(itemId(r.site,candidate.id),url,candidate.title),
                            detail="VK Video Live: dynamic video and audio confirmed",video=media,videoHeaders=access)
                    }
                }
                null
            }
            else -> null
        }
    }

    fun verifyLiveHls(result: MonitoringResult,page: String,site: Site,stopped: ()->Boolean,log: (String)->Unit): MonitoringResult {
        val allowed=if(site==Site.RUMBLE) arrayOf("rumble.com","rumble.cloud") else arrayOf("odysee.live")
        val read=Reader(stopped,log)
        val plan=LiveHlsRecordingPlan.resolve(result.video) { media ->
            check(https(media,*allowed))
            read.read(media,"${site.label} live playlist",page)
        }
        check(plan.kind==LiveHlsRecordingPlan.Kind.LIVE) { "The selected broadcast no longer exposes a rolling live playlist; try Browser Assist" }
        return result.copy(video=plan.video,audio=plan.audio,audioHeaders=result.videoHeaders)
    }

    fun isVkDash(url: String)=https(url,"okcdn.ru","vkuser.net") &&
        CaptureSiteRoutes.web(url)?.path.orEmpty().endsWith(".mpd",true)

    private inline fun <T> runVkCheck(log: (String)->Unit,stage: String,action: ()->T?): T? =
        try { action().also { if(it==null) log("VK Video Live $stage: no verified live DASH candidate") } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { log("VK Video Live $stage unavailable; type=${e.javaClass.simpleName}; values omitted"); null }

    fun verifyVkDash(url: String,page: String,agent: String,stopped: ()->Boolean,log: (String)->Unit): Map<String,String> {
        check(CaptureSiteRoutes.live(page)?.site==Site.VK && isVkDash(url))
        val effectiveAgent=agent.ifBlank { AGENT }
        val access=VkLivePlayback.requestHeaders(page,url,effectiveAgent)
        val raw=Reader(stopped,log,effectiveAgent).read(url,"VK live DASH",extra=access)
        val xml=Jsoup.parse(raw,"",Parser.xmlParser())
        val mpd=xml.getElementsByTag("MPD").single()
        val sets=mpd.getElementsByTag("AdaptationSet")
        fun has(kind: String)=sets.any { a ->
            a.attr("contentType")==kind || a.attr("mimeType").startsWith("$kind/") ||
                a.getElementsByTag("Representation").any { it.attr("mimeType").startsWith("$kind/") }
        }
        check(mpd.attr("type")=="dynamic" && mpd.getElementsByTag("ContentProtection").isEmpty() && has("video") && has("audio")) {
            "VK Video Live did not expose a public dynamic audio/video manifest"
        }
        return access
    }
}
