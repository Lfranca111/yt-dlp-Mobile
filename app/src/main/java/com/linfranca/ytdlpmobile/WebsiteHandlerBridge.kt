package com.linfranca.ytdlpmobile

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.CancellationException

/** Android session/network adapter used by both download engines and monitoring. */
internal object WebsiteHandlerBridge {
    data class Prepared(val info: String,val jar: File): java.io.Closeable { override fun close() { jar.delete() } }
    private const val AGENT="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    private class Client(val c: Context,val original: String,val stopped: ()->Boolean,val log: (String)->Unit,
        val cookieFile: String="",val rawCookies: String="",val userAgent: String="") {
        val session=MediaCookieJar()
        var lastMediaFailure: WebsiteDownloadFailure.Reason?=null
        val agent=userAgent.ifBlank { runCatching { MonitoringSession.agent(MonitoringSession.mode(c,original),WebSettings.getDefaultUserAgent(c)) }.getOrDefault(AGENT) }
        private val browserAttempts=mutableSetOf<String>()
        private var requestSequence=0
        private val deadline=System.nanoTime()+90_000_000_000L
        fun remainingMs()=((deadline-System.nanoTime())/1_000_000L).coerceAtLeast(1)
        fun active() { if(stopped()) throw CancellationException("Website handler canceled");check(System.nanoTime()<deadline) { "Website handler timed out" } }
        fun cookies(url: String): String {
            val browser=CookieManager.getInstance().getCookie(url).orEmpty()
            val imported=runCatching { File(cookieFile.ifBlank { File(c.filesDir,"cookies/imported-cookies.txt").path }).takeIf { it.isFile }?.readLines().orEmpty() }.getOrDefault(emptyList())
            session.load(imported.joinToString("\n"),url,overwrite=false)
            val available=listOf(browser,if(URI(url).host==URI(original).host) rawCookies else "",cookieHeader(imported,url)).filter { it.isNotBlank() }.joinToString("; ")
            session.observe(url,available)
            return session.header(url)
        }
        fun playable(raw: String,referer: String): Boolean {
            lastMediaFailure=null
            var target=raw
            repeat(5) {
                active();WebsiteSourceResolver.requireHttp(target)
                val conn=URI(target).toURL().openConnection() as HttpURLConnection
                conn.instanceFollowRedirects=false;conn.connectTimeout=10000;conn.readTimeout=10000
                conn.setRequestProperty("User-Agent",agent);MediaRequestPolicy.referrer(referer,target).takeIf(String::isNotBlank)?.let { conn.setRequestProperty("Referer",it) }
                conn.setRequestProperty("Range","bytes=0-4095")
                cookies(target).takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie",it) }
                try {
                    val status=conn.responseCode
                    session.accept(target,conn.headerFields.entries.filter { it.key!=null }.associate { it.key!! to it.value })
                    if(status in 300..399) {
                        target=URI(target).resolve(conn.getHeaderField("Location") ?: return false).toString()
                        check(URI(target).scheme=="https") { "Media redirected to an insecure address" }
                    } else {
                        if(status !in setOf(200,206)) {
                            lastMediaFailure=WebsiteDownloadFailure.describe(IllegalStateException("HTTP $status"))
                            log("native media preflight rejected; host=${URI(target).host}; HTTP=$status; reason=${lastMediaFailure?.kind}; URL/header values omitted")
                            return false
                        }
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                        conn.inputStream.use { input -> while(out.size()<4096) { active();val n=input.read(buffer,0,minOf(buffer.size,4096-out.size()));if(n<0) break;out.write(buffer,0,n) } }
                        val bytes=out.toByteArray();val prefix=bytes.toString(Charsets.UTF_8).trimStart()
                        val type=conn.contentType.orEmpty().lowercase()
                        return prefix.startsWith("#EXTM3U") || (bytes.size>=8 && String(bytes,4,4,Charsets.US_ASCII)=="ftyp") ||
                            (type.startsWith("video/") && bytes.isNotEmpty() && !prefix.startsWith("<"))
                    }
                } finally { conn.disconnect() }
            }
            return false
        }
        private fun browserPage(target: String): String? {
            active()
            log("WCOStream page access rejected; trying the saved app browser profile once; no Activity launched")
            return try {
                val config=JSONObject().put("id","builtin-wco-access").put("url",target).put("mode","uploads").put("schema",1)
                    .put("browserMode",MonitoringSession.mode(c,original)).put("selector","a[href]")
                val snapshot=MonitoringPageRenderer.render(c,config,{ active();false },validation={ page ->
                    val decoded=runCatching { WebsiteSourceResolver.decodeWco(page.html) }.getOrDefault("")
                    val frames=Jsoup.parse(page.html+decoded,page.url).select("iframe[src]").map { it.absUrl("src") }.filter {
                        runCatching { val uri=URI(it);uri.host=="embed.wcostream.com" && uri.path.startsWith("/inc/embed/") }.getOrDefault(false)
                    }
                    if(frames.isNotEmpty()) listOf("verified-episode-player") else {
                        check(URI(target).path.startsWith("/anime/")) { "Episode player unavailable; open Browser Assist, wait for Close, play the episode and detect again" }
                        WebsiteSourceResolver.listing(page.url,page.html).map { it.id }.also { check(it.isNotEmpty()) { "No scoped WCOStream series listing" } }
                    }
                })
                active();CookieManager.getInstance().flush()
                log("WCOStream saved browser page validated; session values omitted")
                snapshot.html
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                active()
                log("WCOStream saved browser access unavailable; ${e.javaClass.simpleName}; use Browser Assist if interaction is required")
                null
            }
        }
        fun get(raw: String,referer: String,headers: Map<String,String>): String {
            var target=raw;val initialHost=URI(raw).host
            repeat(5) {
                active();WebsiteSourceResolver.requireHttp(target)
                val conn=URI(target).toURL().openConnection() as HttpURLConnection
                conn.instanceFollowRedirects=false;val remaining=((deadline-System.nanoTime())/1_000_000L).coerceIn(1,12000).toInt();conn.connectTimeout=remaining;conn.readTimeout=remaining
                conn.setRequestProperty("User-Agent",agent);conn.setRequestProperty("Accept","*/*")
                MediaRequestPolicy.referrer(referer,target).takeIf(String::isNotBlank)?.let { conn.setRequestProperty("Referer",it) }
                val requestStarted=System.nanoTime();val requestId=++requestSequence
                val cookie=cookies(target);if(cookie.isNotBlank()) conn.setRequestProperty("Cookie",cookie)
                if(URI(target).host==initialHost) headers.forEach { (key,value)->conn.setRequestProperty(key,value) }
                try {
                    val status=conn.responseCode
                    session.accept(target,conn.headerFields.entries.filter { it.key!=null }.associate { it.key!! to it.value })
                    conn.headerFields.filterKeys { it?.equals("Set-Cookie",true)==true }.values.flatten().forEach { CookieManager.getInstance().setCookie(target,it) }
                    log("website HTTP; request=$requestId; elapsedMs=${(System.nanoTime()-requestStarted)/1_000_000}; pathKind=${if(URI(target).path.startsWith("/api/vidora/")) "player-api" else "page-or-script"}; refererPresent=${MediaRequestPolicy.referrer(referer,target).isNotBlank()}; originPresent=${headers.keys.any { it.equals("Origin",true) }}; playerKeyPresent=${headers.keys.any { it.equals("x-player-key",true) }}; host=${URI(target).host}; status=$status; responseType=${conn.contentType?.substringBefore(';')}; session=${cookie.isNotBlank()}; values omitted")
                    if(status in 300..399) {
                        target=URI(target).resolve(conn.getHeaderField("Location") ?: error("Website redirect has no destination")).toString()
                        check(URI(target).scheme=="https") { "Website redirected to an insecure address" }
                    } else {
                        if(status in setOf(401,403) && WebsiteSourceResolver.site(target)=="WCOStream" && browserAttempts.add(target)) {
                            browserPage(target)?.let { return it }
                        }
                        if(status!=200) {
                            val body=runCatching { conn.errorStream?.use { input -> val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(512);while(bytes.size()<2048) { val n=input.read(buffer,0,minOf(buffer.size,2048-bytes.size()));if(n<0) break;bytes.write(buffer,0,n) };bytes.toString("UTF-8") }.orEmpty() }.getOrDefault("")
                            val markers=listOf("bad gateway","cloudflare","upstream","timeout","unavailable","too many requests","access denied").filter { body.contains(it,true) }
                            val retry=conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0,86400)
                            log("website error response; request=$requestId; HTTP=$status; markers=${markers.joinToString(",").ifBlank { "none recognized" }}; inspectedBytes=${body.toByteArray().size}; retryAfterSeconds=${retry ?: -1}; raw response omitted")
                            error("Website HTTP $status; retry with yt-dlp or Browser Assist")
                        }
                        val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        conn.inputStream.use { input -> while(true) { active();val n=input.read(buffer);if(n<0) break;bytes.write(buffer,0,n);check(bytes.size()<=5_000_000) { "Website response exceeds 5 MB" } } }
                        return bytes.toString("UTF-8")
                    }
                } finally { conn.disconnect() }
            }
            error("Too many website redirects")
        }
    }
    fun aniOptions(c: Context,url: String,stopped: ()->Boolean): AniPlaybackCatalog.Catalog {
        val client=Client(c,url,stopped,{})
        return AniPlaybackCatalog.load(url) { target,referer -> client.get(target,referer,emptyMap()) }
    }
    fun prepare(c: Context,url: String,cookieFile: String,rawCookies: String,userAgent: String,stopped: ()->Boolean,
        process: (String?)->Unit,log: (String)->Unit,onFailure: (WebsiteDownloadFailure.Reason)->Unit = {}): Prepared? {
        val site=WebsiteSourceResolver.site(url) ?: return null
        val client=Client(c,url,stopped,log,cookieFile,rawCookies,userAgent)
        var jar: File?=null
        var stage="resolve-player-source"
        var server=0
        fun preparation(next: String) {
            stage=next
            if(site=="h123movies") log("h123 preparation; websiteServer=$server; stage=$stage; starting")
        }
        return try {
            log("$site dedicated playback handler started")
            val chosen=PlaybackChoice.apply(c,url)
            val resolved=WebsiteSourceResolver(client::get,stopped,log,client::playable,browserPlayback={ seed ->
                client.active();client.cookies(seed.playerPage)
                val captured=H123WebsiteCapture.capture(c,seed,client.agent,client.session.netscape().lines(),{ client.active();false },log,preferAudioMaster=PlaybackChoice.get(c,chosen).audio=="en") { media,headers,seconds ->
                    if(stopped()) throw CancellationException("Download canceled")
                    try {
                        val candidate=VerifiedMediaAccess(seed.playerPage,client.agent,{ target -> headers+mapOf("Cookie" to client.cookies(target)) },stopped,client.session,budgetMs=minOf(6000,client.remainingMs()))
                            .plan(media,url,seed.title)
                        val duration=candidate.optDouble("_app_expected_duration",0.0)
                        val matched=duration>0 && kotlin.math.abs(duration-seconds)<=maxOf(2.0,seconds*0.01)
                        log("h123 background validation; websiteServer=${seed.playerServer}; completeVOD=true; playlistSeconds=${duration.toLong()}; playingSeconds=${seconds.toLong()}; durationMatch=$matched; candidate not exported")
                        matched
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { log("h123 background validation failed; websiteServer=${seed.playerServer}; category=${WebsiteDownloadFailure.describe(e).kind}");false }
                }
                seed.copy(url=captured.url,headers=captured.headers,expectedSeconds=captured.duration,browserResolved=true)
            }).resolve(chosen)
            server=resolved.playerServer
            preparation("flush-browser-session")
            client.active();CookieManager.getInstance().flush()
            if(site=="h123movies") {
                val media=URI(resolved.url)
                val kind=when {
                    media.path.orEmpty().endsWith(".m3u8",true) -> "HLS hint"
                    media.path.orEmpty().endsWith(".mpd",true) -> "DASH hint"
                    media.path.orEmpty().endsWith(".mp4",true) -> "MP4 hint"
                    else -> "no format suffix"
                }
                log("h123 preparation source; websiteServer=$server; browserResolved=${resolved.browserResolved}; type=$kind; URL omitted")
            }
            preparation("fetch-parse-and-validate-media")
            val content=VerifiedMediaAccess(resolved.referer,client.agent,{ target -> resolved.headers+mapOf("Cookie" to client.cookies(target)) },stopped,client.session)
            val info=content.plan(resolved.url,url,resolved.title)
            if(site=="h123movies") log("h123 preparation; websiteServer=$server; stage=$stage; passed; formats=${info.optJSONArray("formats")?.length() ?: 1}; segments=${info.optInt("_app_expected_segments")}")
            preparation("compare-playback-duration")
            if(resolved.expectedSeconds>0) check(kotlin.math.abs(info.optDouble("_app_expected_duration",0.0)-resolved.expectedSeconds)<=maxOf(2.0,resolved.expectedSeconds*0.01)) { "Background media duration changed" }
            info.put("_app_browser_prepared",resolved.browserResolved)
            // Export after all playlist requests, retaining Set-Cookie domains/paths for segment hosts.
            preparation("write-session-cookie-jar")
            val cookieJar=File(c.cacheDir,"website-jars/${UUID.randomUUID()}.txt").apply {
                parentFile?.mkdirs()
                val combined=MediaCookieJar()
                File(cookieFile.ifBlank { File(c.filesDir,"cookies/imported-cookies.txt").path }).takeIf { it.isFile }?.let { combined.load(it.readText()) }
                combined.load(info.optString("_app_cookie_jar"))
                writeText(combined.netscape())
            }
            jar=cookieJar
            info.put("id",resolved.id.replace(':','-')).put("subtitles",resolved.subtitles).put("is_live",false)
            val selection=PlaybackChoice.get(c,chosen)
            if(site=="h123movies" && selection.audio=="en") {
                preparation("select-english-rendition")
                H123AudioLanguage.plan(info,log)
            }
            preparation("build-download-plan")
            val label=listOf(selection.audio.uppercase(),selection.server).filter(String::isNotBlank).joinToString(" · ")
            if(label.isNotBlank()) info.put("title",info.optString("title")+" ["+label+"]")
            info.put("_app_variant",selection.variant())
            if(site=="h123movies") info.put("_app_trial_player",resolved.playerPage).put("_app_trial_source",resolved.playerSource).put("_app_trial_server",resolved.playerServer).put("_app_trial_agent",client.agent)
            log("$site playlist-verified download plan (segment access checked on failure); formats=${info.optJSONArray("formats")?.length() ?: 1}; segments=${info.optInt("_app_expected_segments")}; expectedSeconds=${info.optDouble("_app_expected_duration",0.0).toInt()}; URL/header values omitted")
            Prepared(info.toString(),cookieJar).also { jar=null }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            if(stopped()) throw CancellationException("Website handler canceled")
            if(StorageGuard.failure(e)) throw e
            val described=WebsiteDownloadFailure.describe(e)
            val reason=if(described.kind=="media-access") client.lastMediaFailure ?: described else described
            if(site=="h123movies") {
                val causes=generateSequence(e as Throwable) { it.cause?.takeIf { cause -> cause!==it } }.take(5).toList()
                val http=causes.firstNotNullOfOrNull { cause ->
                    Regex("""(?i)\bHTTP(?: Error)?[ =:]+([45][0-9]{2})\b""").find(cause.message.orEmpty())?.groupValues?.get(1)
                } ?: "unavailable"
                val safeReasons=mapOf(
                    "English audio is not available in the verified playlist; no other language substituted" to "no English rendition declared",
                    "No matching video rendition for English audio" to "no compatible video rendition for selected audio group",
                    "Background media duration changed" to "playback and media duration disagree",
                    "Website handler timed out" to "preparation timeout")
                val check=causes.firstNotNullOfOrNull { safeReasons[it.message] } ?: when {
                    http!="unavailable" -> "HTTP request rejected"
                    causes.any { it is java.net.SocketTimeoutException } -> "request timed out"
                    causes.any { it is java.net.UnknownHostException } -> "host lookup failed"
                    causes.any { it is org.json.JSONException } -> "invalid JSON"
                    else -> "see app source location"
                }
                val location=causes.asReversed().firstNotNullOfOrNull { cause ->
                    cause.stackTrace.firstOrNull { it.className.startsWith("com.linfranca.ytdlpmobile.") }
                }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "unavailable"
                log("h123 preparation failed; websiteServer=$server; stage=$stage; check=$check; HTTP=$http; exception=${causes.last().javaClass.simpleName}; location=$location; raw values omitted")
            }
            onFailure(reason)
            log("$site dedicated playback unavailable; category=${reason.kind}; ${reason.detail}; "+
                if(site=="AniWaves") "use the existing Browser Assist option; unsupported webpage fallback skipped" else "trying original URL once with yt-dlp fallback")
            null
        } finally { jar?.delete();process(null) }
    }

    fun monitoring(c: Context,r: MonitoringRule,live: Boolean,stopped: ()->Boolean): MonitoringResult {
        val site=WebsiteSourceResolver.site(r.url) ?: error("No dedicated handler")
        if(live) return MonitoringResult(offline=true,detail="$site handler supports on-demand videos/episodes; no native live-channel monitoring")
        val log: (String)->Unit={ MonitoringStore.log(c,it) }
        val client=Client(c,r.url,stopped,log)
        val resolver=WebsiteSourceResolver(client::get,stopped,log)
        val direct=runCatching { resolver.discover(r.url) }
        if(stopped()) throw CancellationException("Monitoring check canceled")
        val items=if(direct.isSuccess) direct.getOrThrow() else {
            log("$site listing needs rendered page; direct category=${MonitoringFailure.kind(direct.exceptionOrNull()?.message)}; baseline retained until listing validates")
            val target=resolver.listingPage.ifBlank { r.url }
            val config=JSONObject().put("id","builtin-$site").put("url",target).put("mode","uploads").put("schema",1)
                .put("browserMode",MonitoringSession.mode(c,r.url)).put("selector","a[href]")
            val snapshot=MonitoringPageRenderer.render(c,config,stopped,validation={ s ->
                WebsiteSourceResolver.listing(s.url,s.html).map { it.id }.also { check(it.isNotEmpty()) { "No scoped episode/video listing in rendered page" } }
            })
            WebsiteSourceResolver.listing(snapshot.url,snapshot.html)
        }
        check(items.isNotEmpty()) { "No verified website listing; baseline retained" }
        CookieManager.getInstance().flush()
        return MonitoringResult(items=items,detail="$site: ${items.size} recent episode/video entries; first 200 scoped matches")
    }
    internal fun cookieHeader(lines: List<String>,url: String,now: Long=System.currentTimeMillis()/1000): String {
        val uri=URI(url);val host=uri.host.lowercase();val path=uri.path.ifBlank { "/" }
        return lines.mapNotNull { raw ->
            val line=raw.removePrefix("#HttpOnly_");if(line.startsWith('#') || line.isBlank()) return@mapNotNull null
            val parts=line.split('\t');if(parts.size<7) return@mapNotNull null
            val domain=parts[0].trimStart('.').lowercase()
            if(host!=domain && !(parts[1].equals("TRUE",true) && host.endsWith(".$domain"))) return@mapNotNull null
            if(parts[3].equals("TRUE",true) && uri.scheme!="https") return@mapNotNull null
            val expires=parts[4].toLongOrNull() ?: return@mapNotNull null
            if(expires!=0L && expires<=now) return@mapNotNull null
            val cookiePath=parts[2].ifBlank { "/" }
            if(!path.startsWith(cookiePath) || (path.length>cookiePath.length && !cookiePath.endsWith('/') && path[cookiePath.length]!='/')) return@mapNotNull null
            "${parts[5]}=${parts[6]}".takeIf { !it.contains('\n') && !it.contains('\r') }
        }.distinct().joinToString("; ")
    }
}
