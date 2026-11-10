package com.linfranca.ytdlpmobile

import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CancellationException
import org.jsoup.Jsoup

/** A bounded HTTP redirect preflight. Fetch is injected so identity rules are tested without websites. */
internal object PageRedirectPolicy {
    data class Response(val status: Int,val location: String="",val html: String="",val contentType: String="",val rendered: Boolean=false)
    data class Result(val original: String,val target: String,val chain: List<String>,val verified: Boolean,val reason: String,val handler: String="",val extractorOnly: Boolean=false)
    class Blocked(message: String): IllegalStateException("Redirect could not be verified: $message. Review the link in Browser Assist.")
    private val selection=setOf("audio","server","ydlp_server","ydlp_audio","season","episode")
    fun address(raw: String): URI {
        val u=URI(raw)
        require(u.scheme in setOf("http","https") && !u.host.isNullOrBlank() && u.userInfo==null && raw.none { it=='\n' || it=='\r' }) { "Invalid redirect address" }
        return u
    }
    fun page(raw: String): Boolean=runCatching {
        val u=address(raw);!Regex("(?i)\\.(?:m3u8|mpd|mp4|webm|mkv|ts|m4s|mp3|m4a|aac|vtt|srt|jpg|png)$").containsMatchIn(u.path)
    }.getOrDefault(false)
    private fun query(u: URI)=u.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='),"UTF-8") to URLDecoder.decode(it.substringAfter('=',""),"UTF-8")
    }
    fun preserve(original: String,target: String): String {
        val a=address(original);val b=address(target);val wanted=query(a);val got=query(b)
        val additions=a.rawQuery.orEmpty().split('&').filter {
            val key=URLDecoder.decode(it.substringBefore('='),"UTF-8")
            if(key !in selection) false else {
                if(key in got && got[key]!=wanted[key]) throw Blocked("the destination changed a selected playback option")
                key !in got
            }
        }
        return b.toString().substringBefore('#').substringBefore('?')+
            (listOfNotNull(b.rawQuery?.takeIf { it.isNotBlank() },additions.joinToString("&").takeIf { it.isNotBlank() }).joinToString("&").takeIf { it.isNotBlank() }?.let { "?$it" } ?: "")+
            (b.rawFragment?.let { "#$it" } ?: "")
    }
    fun renderTargetMatches(original: String,target: String): Boolean=runCatching {
        val a=address(original);val b=address(target)
        if(a.host!=b.host || a.port!=b.port || a.path.trimEnd('/')!=b.path.trimEnd('/') || a.scheme=="https" && b.scheme!="https") return false
        val before=query(a);val after=query(b)
        for(k in listOf("v","id","list","channel","user")) if(k in before && before[k]!=after[k]) return false
        for(k in selection) if(k in before && k in after && before[k]!=after[k]) return false
        true
    }.getOrDefault(false)
    fun samePage(original: String,target: String,html: String,assets: String=""): Boolean {
        val a=address(original);val b=address(target)
        if(a.path.trimEnd('/')!=b.path.trimEnd('/') || a.path.trim('/').isEmpty()) return false
        val aq=query(a);val bq=query(b)
        for(k in listOf("v","id","list","channel","user")) if(k in aq && aq[k]!=bq[k]) return false
        val doc=Jsoup.parse(html,target)
        val title=doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty().ifBlank { doc.title() }.lowercase()
        val assessment=WebsitePageFingerprint.assess(target,html,assets)
        if(assessment.challenge) return false
        val player=doc.selectFirst("video,iframe[src],meta[property=og:video]")!=null || assessment.handler!=null
        if(!player && listOf("just a moment","access denied","verify you are","captcha","sign in","log in","page not found","advertisement","domain for sale","domain expired","sponsored offer").any { it in title }) return false
        val expected=WebsiteDomains.site(original);val other=WebsiteDomains.site(target)
        if(expected!=null && other!=null && expected!=other) return false
        val brand=when(expected) {
            "AniWaves" -> doc.selectFirst(".layout-page-watchtv[data-id]")!=null || "aniwave" in title
            "h123movies" -> doc.select("script[src]").isNotEmpty() && ("123movies" in html.lowercase() || doc.selectFirst("#episode-select")!=null)
            "WCOStream" -> doc.select("iframe[src]").any { runCatching { URI(it.absUrl("src")).host=="embed.wcostream.com" }.getOrDefault(false) } || "wcostream" in title || "watch cartoons online" in title
            else -> true
        }
        if(!brand && assessment.handler!=expected) return false
        val expectedEpisode=Regex("(?:episode-|/ep-)(\\d+)",RegexOption.IGNORE_CASE).find(a.path)?.groupValues?.get(1) ?: aq["episode"]
        if(expectedEpisode!=null) {
            val reported=Regex("episode\\s*(\\d+)",RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)
                ?: doc.selectFirst(".layout-page-watchtv[data-ep-name]")?.attr("data-ep-name")?.takeIf { it.all(Char::isDigit) }
            if(reported!=null && reported.toIntOrNull()!=expectedEpisode.toIntOrNull()) return false
        }
        // A known old slug or stable channel name must match the final page's own identity.
        val identity=(title+" "+doc.selectFirst("h1")?.text().orEmpty()).lowercase()
        val tokens=a.path.trim('/').split('/').flatMap { it.split('-', '_') }
            .map { URLDecoder.decode(it,"UTF-8").lowercase() }
            .filter { it.length>=3 && it !in setOf("watch","movie","series","anime","channel","videos","user","episode","submitted","profile") && !it.all(Char::isDigit) }
        val matches=tokens.count { it in identity }
        val aniId=Regex("-(\\d+)(?:/|$)").find(a.path)?.groupValues?.get(1)
        if(expected=="AniWaves" && aniId!=null) {
            val reported=doc.selectFirst(".layout-page-watchtv[data-id]")?.attr("data-id")
            if(!reported.isNullOrBlank() && reported!=aniId) return false
        }
        val aniMatch=expected=="AniWaves" && aniId!=null && doc.selectFirst(".layout-page-watchtv[data-id]")?.attr("data-id")==aniId
        val canonical=doc.selectFirst("link[rel=canonical]")?.absUrl("href").orEmpty()
        val canonicalMatch=runCatching { val c=address(canonical);c.host==b.host && c.path.trimEnd('/')==b.path.trimEnd('/') }.getOrDefault(false)
        val matchingH123=expected=="h123movies" && assessment.handler==expected
        return matchingH123 || aniMatch || matches>=2 || matches==1 && (tokens.size==1 || canonicalMatch || expected!=null)
    }
    private data class Inspected(val body: Response,val assets: String,val handler: String?,val complete: Boolean=true)
    /** Inspection is advisory. Only explicit redirect/content conflicts and user cancellation are fatal. */
    private fun inspect(url: String,body: Response,fetch: (String,Boolean)->Response,render: ((String)->Response)?,renderProgress: ((String,(Response)->Boolean)->Response)?,log: (String)->Unit): Inspected {
        if(body.status!=200 || !body.contentType.contains("html",true)) {
            log("page inspection incomplete; HTML unavailable; HTTP=${body.status}; action=existing fallback")
            return Inspected(body,"",null,false)
        }
        val cache=linkedMapOf<String,String>();var failed=0;var total=0;var lastEvidence="";var samples=0
        var current=body;var assets="";var assessment=WebsitePageFingerprint.assess(url,body.html)
        fun evaluate(html: String,stage: String): Boolean {
            assessment=WebsitePageFingerprint.assess(url,html,assets)
            val signature=assessment.summary()+"; scripts=${cache.size}; failures=$failed"
            if(signature!=lastEvidence) {
                lastEvidence=signature
                log("page inspection findings; stage=$stage; sample=$samples; DOM chars=${html.length}; inspectedScripts=${cache.size}; scriptFailures=$failed; "+assessment.summary())
                assessment.candidates().forEach(log)
            }
            return assessment.handler!=null
        }
        fun gather(snapshot: Response,stage: String): Boolean {
            current=snapshot;samples++
            if(evaluate(snapshot.html,stage)) return true
            for(script in WebsitePageFingerprint.scripts(url,snapshot.html)) if(script !in cache && cache.size<8 && total<3000000) {
                try {
                    val data=fetch(script,true)
                    if(data.status==200 && data.html.isNotBlank() && total+data.html.length<=3000000) { cache[script]=data.html;total+=data.html.length }
                    else { failed++;cache[script]="" }
                } catch(e: CancellationException) { throw e } catch(_: Exception) { failed++;cache[script]="" }
                assets=cache.values.joinToString("\n")
                if(evaluate(snapshot.html,"$stage/script")) return true
            }
            return evaluate(snapshot.html,stage)
        }
        if(gather(body,"static")) {
            log("page inspection handoff; handler=${assessment.handler}; reason=confident structural/player/content evidence; unfinished page sections do not delay detection")
            return Inspected(current,assets,assessment.handler)
        }
        val dynamic=assessment.challenge || assessment.loading || WebsitePageFingerprint.scripts(url,current.html).isNotEmpty()
        var complete=!dynamic
        if(dynamic && !body.rendered) {
            log("page inspection continuing while loading; recognition evaluated on each snapshot; missing evidence is not a download failure")
            try {
                val rendered=when {
                    renderProgress!=null -> renderProgress(url) { snapshot ->
                        gather(snapshot,"rendered-progress").also { matched -> if(matched) log("page inspection early handoff; handler=${assessment.handler}; reason=evidence sufficient during loading") }
                    }
                    render!=null -> render(url)
                    else -> throw PageInspectionIncomplete("rendering unavailable or deferred")
                }
                if(rendered.status==200 && rendered.rendered) {
                    gather(rendered,"rendered-final")
                    complete=!assessment.loading && !assessment.challenge
                } else log("page inspection incomplete; renderer returned no usable document; action=existing fallback")
            } catch(e: Blocked) { throw e }
              catch(e: CancellationException) { throw e }
              catch(e: PageInspectionIncomplete) { log("page inspection incomplete; ${e.message}; collected evidence retained; action=reassess then existing fallback") }
              catch(e: Exception) { log("page inspection incomplete; rendererFailure=${e.javaClass.simpleName}; collected evidence retained; action=reassess then existing fallback") }
        }
        // A rendering failure must never discard a previously observed usable DOM/configuration.
        evaluate(current.html,"retained-evidence")
        if(assessment.handler!=null) log("page inspection handoff; handler=${assessment.handler}; collected evidence sufficient")
        else {
            if(failed>0 || WebsitePageFingerprint.scripts(url,current.html).size>cache.size) complete=false
            log("page inspection ${if(complete) "complete" else "incomplete"}; result=no confident dedicated handler; action=continue existing fallback; Browser Assist unchanged")
        }
        return Inspected(current,assets,assessment.handler,complete)
    }
    /** Optional preflight cannot veto the normal extractor's own handling of the submitted URL. */
    fun forExtractor(raw: String,stopped: ()->Boolean,log: (String)->Unit,inspect: ()->Result): Result {
        address(raw) // Input validity and user cancellation are not optional inspection failures.
        if(stopped()) throw CancellationException("Page inspection canceled")
        val result=try { inspect() }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            if(stopped()) throw CancellationException("Page inspection canceled")
            val category=if(e is Blocked) "redirect_verification_failed" else "inspection_unavailable"
            log("page inspection warning; category=$category; exception=${e.javaClass.simpleName}; action=normal yt-dlp using original submitted URL; no replacement URL or handler adopted; values omitted")
            return Result(raw,raw,listOf(raw),false,"${category}_original_extractor",extractorOnly=true)
        }
        if(stopped()) throw CancellationException("Page inspection canceled")
        if(result.verified && result.handler.isNotBlank()) return result
        log("page inspection handoff; result=no verified dedicated handler; observedHops=${result.chain.size-1}; action=normal yt-dlp using original submitted URL; no replacement URL or handler adopted; values omitted")
        return Result(raw,raw,result.chain,false,"unmatched_original_extractor",extractorOnly=true)
    }
    fun resolve(raw: String,fetch: (String,Boolean)->Response,stopped: ()->Boolean={false},log: (String)->Unit={},inspectUnknown: Boolean=true,render: ((String)->Response)?=null,renderProgress: ((String,(Response)->Boolean)->Response)?=null,extractorFallback: Boolean=false): Result {
        if(extractorFallback) return forExtractor(raw,stopped,log) { resolve(raw,fetch,stopped,log,inspectUnknown,render,renderProgress) }
        address(raw)
        if(!page(raw)) return Result(raw,raw,listOf(raw),false,"direct_media_unchanged")
        val chain=mutableListOf(raw);var target=raw;var crossed=false
        for(hop in 0..5) {
            if(stopped()) throw CancellationException("Redirect check canceled")
            val response=try { fetch(target,false) } catch(e: CancellationException) { throw e } catch(_: Exception) {
                log("page redirect preflight unavailable; redirectObserved=${chain.size>1}; no alias registered; action=existing fallback using original URL; values omitted")
                return Result(raw,raw,chain,false,"preflight_unavailable")
            }
            log("page redirect check; hop=$hop; host=${address(target).host}; HTTP=${response.status}; URL values omitted")
            if(response.status in setOf(301,302,303,307,308)) {
                if(hop==5) throw Blocked("redirect limit reached")
                val next=try { preserve(raw,address(target).resolve(response.location.takeIf { it.isNotBlank() } ?: throw Blocked("missing destination")).toString()) } catch(e: Blocked) { throw e } catch(_: Exception) { throw Blocked("invalid destination") }
                val old=address(target);val n=address(next)
                if(old.scheme=="https" && n.scheme!="https") throw Blocked("insecure HTTPS downgrade")
                if(next in chain) throw Blocked("redirect loop")
                crossed=crossed || address(raw).host.lowercase().removePrefix("www.")!=n.host.lowercase().removePrefix("www.") || address(raw).port!=n.port
                if(crossed && n.scheme!="https") throw Blocked("a domain change requires HTTPS")
                chain+=next;target=next;continue
            }
            if(chain.size==1) {
                if(inspectUnknown && response.status in 200..299) {
                    try {
                        val body=fetch(raw,true)
                        val inspection=inspect(raw,body,fetch,render,renderProgress,log)
                        val handler=inspection.handler
                        if(handler!=null) return Result(raw,raw,chain,true,"dedicated_page_inspection",handler)
                    } catch(e: Blocked) { throw e } catch(e: CancellationException) { throw e } catch(_: Exception) { log("unrecognized-domain page inspection unavailable; existing extractor fallback retained; values omitted") }
                }
                return Result(raw,raw,chain,false,"no_redirect")
            }
            if(response.status !in 200..299) {
                log("page inspection incomplete; destination HTTP=${response.status}; no alias registered; action=existing fallback using original URL")
                return Result(raw,raw,chain,false,"destination_unavailable_fallback")
            }
            // www/mobile aliases and HTTPS upgrades retain the page identity only with identical path/query identity.
            val a=address(raw);val b=address(target)
            if(!crossed && a.path.trimEnd('/')==b.path.trimEnd('/') && query(a).filterKeys { it !in selection }==query(b).filterKeys { it !in selection }) {
                val handler=if(inspectUnknown) try { inspect(target,fetch(target,true),fetch,render,renderProgress,log).handler }
                    catch(e: Blocked) { throw e } catch(e: CancellationException) { throw e } catch(_: Exception) { null } else null
                return Result(raw,target,chain,true,"same_host_same_page",handler.orEmpty())
            }
            val body=try { fetch(target,true) } catch(e: CancellationException) { throw e } catch(_: Exception) {
                log("page inspection incomplete; destination identity unavailable; no alias registered; action=existing fallback using original URL")
                return Result(raw,raw,chain,false,"inspection_unavailable_fallback")
            }
            val inspected=inspect(target,body,fetch,render,renderProgress,log)
            if(inspected.body.status!=200 || !inspected.body.contentType.contains("html",true) || !samePage(raw,target,inspected.body.html,inspected.assets)) {
                val dom=Jsoup.parse(inspected.body.html,target)
                val reported=dom.selectFirst(".layout-page-watchtv[data-id]")?.attr("data-id")
                val intended=Regex("-(\\d+)(?:/|$)").find(a.path)?.groupValues?.get(1)
                val intendedEpisode=Regex("(?:episode-|/ep-)(\\d+)",RegexOption.IGNORE_CASE).find(a.path)?.groupValues?.get(1) ?: query(a)["episode"]
                val reportedEpisode=Regex("episode\\s*(\\d+)",RegexOption.IGNORE_CASE).find(dom.title())?.groupValues?.get(1)
                val differentPath=a.path.trimEnd('/')!=b.path.trimEnd('/')
                val differentQuery=query(a).filterKeys { it !in selection }.any { (k,v) -> query(b)[k]!=v }
                val other=WebsiteDomains.site(target)
                if(differentPath || differentQuery || intendedEpisode!=null && reportedEpisode!=null && intendedEpisode.toIntOrNull()!=reportedEpisode.toIntOrNull() || reported!=null && intended!=null && reported!=intended || WebsiteDomains.site(raw)!=null && other!=null && WebsiteDomains.site(raw)!=other)
                    throw Blocked("the destination conflicts with the intended page")
                log("page inspection ${if(inspected.complete) "complete" else "incomplete"}; redirect identity uncertain; no alias registered; action=existing fallback using original URL")
                return Result(raw,raw,chain,false,"identity_uncertain_fallback")
            }
            return Result(raw,target,chain,true,"path_and_page_identity_matched",inspected.handler.orEmpty())
        }
        throw Blocked("redirect limit reached")
    }
}
