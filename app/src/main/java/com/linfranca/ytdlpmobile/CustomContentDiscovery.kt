package com.linfranca.ytdlpmobile

import java.net.URI
import org.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** General declarative discovery. No website names, executable recipes or replayed API requests. */
internal object CustomContentDiscovery {
    val urlAttributes=listOf("href","data-href","data-url","data-permalink","itemid")
    val idAttributes=listOf("data-id","data-post-id","data-video-id","data-item-id")
    data class Candidate(val url: String,val title: String,val author: String="",val thumbnail: String="",val video: Boolean=false,val method: String="link",val selector: String="",val publishedAt: Long=0) {
        fun json()=JSONObject().put("url",url).put("title",title).put("author",author).put("thumbnail",thumbnail).put("video",video).put("method",method).put("selector",selector).put("publishedAt",publishedAt)
    }
    fun stable(raw: String,base: String): String? = runCatching {
        val u=URI(base).resolve(raw);val b=URI(CustomUploadPolicy.canonical(base))
        require(u.scheme=="https" && u.userInfo==null)
        val normalized=URI(CustomUploadPolicy.canonical(u.toString()))
        require(normalized.host==b.host && normalized.port==b.port)
        require(!Regex("(?i)(token|auth|cookie|signature|api.?key|password)=").containsMatchIn(u.rawQuery.orEmpty()))
        require(!Regex("(?i)\\.(mp4|webm|m3u8|mpd|m4s|ts|jpg|jpeg|png|gif|webp)(/|$)").containsMatchIn(u.path))
        val result=CustomUploadPolicy.canonical(u.toString())
        require(result!=CustomUploadPolicy.canonical(base));result
    }.getOrNull()
    fun examples(raw: String,base: String): List<String> {
        val tokens=Regex("https://[^\\s<>\"']+").findAll(raw).map { it.value.trimEnd(')',']',',',';') }.toList()
        require(tokens.size in 2..20) { "Paste 2 to 20 full HTTPS post/video page links, one per line" }
        val values=tokens.map { stable(it,base) ?: error("Use same-website item pages without credentials or temporary media URLs") }.distinct()
        require(values.size>=2) { "Choose two different items; tracking/fragment variants count as the same example" }
        CustomUploadPolicy.suggest(values);return values
    }
    val recordKinds=setOf("video","title","text","full_text","description","headline","caption","desc","body","content","name")
    fun recordKind(value: JSONObject): String {
        val supplied=value.optString("recordKind")
        if(supplied in recordKinds) return supplied
        val media=value.opt("video")
        if(value.optString("@type").contains("video",true) || media==true || media is JSONObject || (media is JSONArray && media.length()>0) || value.optString("video_url").isNotBlank()) return "video"
        return recordKinds.filter { it!="video" }.firstOrNull { value.opt(it) is String && value.optString(it).isNotBlank() }.orEmpty()
    }
    data class Mapping(val attribute: String,val template: String,val recordKind: String="")
    fun inferMapping(examples: List<String>,html: String): Mapping? {
        val doc=Jsoup.parse(html)
        val structuredIds=mutableSetOf<String>();val kinds=mutableMapOf<String,String>();var visited=0
        fun ids(v: Any?,depth: Int) {
            if(depth>12 || visited++>5000) return
            if(v is JSONArray) (0 until minOf(v.length(),500)).forEach { ids(v.opt(it),depth+1) }
            if(v is JSONObject) {
                listOf("id","id_str","post_id","video_id","identifier").forEach { k -> v.optString(k).takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }?.let { id -> structuredIds.add(id);kinds[id]=recordKind(v) } }
                v.keys().asSequence().take(100).forEach { k -> val child=v.opt(k);if(child is JSONArray || child is JSONObject) ids(child,depth+1) }
            }
        }
        doc.select("script[type=application/json],script[type=application/ld+json]").take(30).forEach { script -> if(script.data().length<=500000) runCatching { ids(JSONTokener(script.data()).nextValue(),0) } }
        for(attr in idAttributes+"") {
            val values=if(attr.isBlank()) structuredIds else doc.select("[$attr]").take(1000).map { it.attr(attr) }.filter { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }.toSet()
            val pairs=examples.mapNotNull { raw ->
                val uri=URI(raw)
                val id=uri.path.trim('/').substringAfterLast('/').takeIf { it in values }
                    ?: CustomUploadPolicy.query(uri).values.firstOrNull { it in values }
                id?.let { it to raw.replace(Regex("(?<=[/=])"+Regex.escape(it)+"(?=([&#/]|$))"),"{id}") }
            }
            if(pairs.size==examples.size && pairs.map { it.first }.distinct().size>=2 && pairs.map { it.second }.distinct().size==1 && pairs.first().second.contains("{id}"))
                if(attr.isNotBlank()) return Mapping(attr,pairs.first().second)
                else if(pairs.map { kinds[it.first].orEmpty() }.distinct().size==1 && !kinds[pairs.first().first].isNullOrBlank())
                    return Mapping(attr,pairs.first().second,kinds[pairs.first().first]!!)
        }
        return null
    }
    fun resolveId(id: String,template: String,base: String): String? {
        if(!id.matches(Regex("[A-Za-z0-9_-]{1,100}")) || !template.contains("{id}")) return null
        return stable(template.replace("{id}",id),base)
    }
    fun publication(raw: String): Long = runCatching {
        val number=raw.toDoubleOrNull()
        if(number!=null && number.isFinite() && number>0 && number<1e15) (if(number<1e12) number*1000 else number).toLong()
        else runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrElse { java.time.LocalDate.parse(raw).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
    }.getOrDefault(0)
    private fun card(e: Element)=e.closest("article,[role=article],li,[data-post-id],[data-video-id],[data-item-id]") ?: e.parent() ?: e
    private fun description(e: Element,c: Element): String = c.selectFirst("[itemprop=headline],[itemprop=description],figcaption,h1,h2,h3,h4")?.text().orEmpty()
        .ifBlank { c.text() }.ifBlank { e.attr("title") }.ifBlank { e.attr("aria-label") }.ifBlank { c.selectFirst("img[alt]")?.attr("alt").orEmpty() }.take(300)
    fun discover(j: JSONObject,html: String,page: String=j.getString("url")): Pair<List<Candidate>,String> {
        val base=j.getString("url");val doc=Jsoup.parse(html,page);val results=mutableListOf<Candidate>();var rejected=0
        val roots=doc.select(j.getString("linkSelector"))
        val nodes=roots.flatMap { root -> listOf(root)+root.select(urlAttributes.joinToString(",") { "[$it]" }+","+idAttributes.joinToString(",") { "[$it]" }) }.distinct().take(2000)
        fun add(candidate: Candidate) {
            val url=stable(candidate.url,base)
            val u=runCatching { URI(url) }.getOrNull()
            if(url==null || u==null || !u.path.startsWith(j.getString("pathPrefix")) || !CustomUploadPolicy.matchesPath(u.path,j.optString("pathPattern")) ||
                CustomUploadPolicy.keys(j).any { it !in CustomUploadPolicy.query(u) } || (j.optBoolean("videoOnly") && !candidate.video)) { rejected++;return }
            val canonical=CustomUploadPolicy.canonical(url,CustomUploadPolicy.keys(j))
            results.add(candidate.copy(url=canonical))
        }
        nodes.forEach { e ->
            if(e.closest("nav,header,footer,[role=navigation],[role=menu]")!=null) return@forEach
            val c=card(e)
            val raw=urlAttributes.firstNotNullOfOrNull { a -> e.attr(a).takeIf { it.isNotBlank() } }
                ?: j.optString("idAttribute").takeIf { it in idAttributes }?.let { attr -> resolveId(e.attr(attr),j.optString("urlTemplate"),base) }
                ?: return@forEach
            val author=c.selectFirst("[rel=author],[itemprop=author],[data-author]")?.let { it.attr("data-author").ifBlank { it.text() } }.orEmpty().take(120)
            val image=(c.selectFirst("video[poster]") ?: c.select("img").lastOrNull())?.let { it.absUrl("poster").ifBlank { it.absUrl("src") }.ifBlank { it.absUrl("data-src") } }.orEmpty()
            val video=c.selectFirst("video,[itemtype*=VideoObject],[aria-label*=Play],[aria-label*=play],[data-video-id]")!=null
            add(Candidate(URI(page).resolve(raw).toString(),description(e,c),author,image,video,if(e.hasAttr("href")) "link/card" else "attribute/id",publishedAt=publication(c.selectFirst("time[datetime]")?.attr("datetime").orEmpty())))
        }
        // Only JSON documents are parsed. JavaScript assignment scripts are never evaluated here.
        var visits=0
        fun walk(value: Any?,depth: Int) {
            if(depth>12 || visits++>5000 || results.size>600) return
            if(value is JSONArray) { (0 until minOf(value.length(),500)).forEach { walk(value.opt(it),depth+1) };return }
            if(value !is JSONObject) return
            val type=value.optString("@type").ifBlank { value.optString("type") }
            val media=value.opt("video")
            val video=type.contains("video",true) || media==true || media is JSONObject || (media is JSONArray && media.length()>0) || (media is String && media.startsWith("https://")) || value.optString("video_url").isNotBlank()
            val id=listOf("id","id_str","post_id","video_id","identifier").firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) } }
            val raw=listOf("url","permalink","webpage_url","canonical_url").firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.startsWith("/") || it.startsWith("https://") } }
                ?: id?.takeIf { j.optString("jsonRecordKind").isNotBlank() && recordKind(value)==j.optString("jsonRecordKind") }?.let { resolveId(it,j.optString("urlTemplate"),base) }
            if(raw!=null) {
                val author=value.optJSONObject("author")?.let { it.optString("name").ifBlank { it.optString("username") } } ?: value.optString("author")
                val title=listOf("title","headline","description","text","name").firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() && !it.startsWith('{') } }.orEmpty().take(300)
                add(Candidate(URI(page).resolve(raw).toString(),title,author.take(120),value.optString("thumbnail").ifBlank { value.optString("thumbnailUrl") },video,"structured/observed",publishedAt=listOf("publishedAt","datePublished","uploadDate","published_at","timestamp").map { publication(value.optString(it)) }.firstOrNull { it>0 } ?: 0))
            }
            value.keys().asSequence().take(100).forEach { key -> val child=value.opt(key);if(child is JSONObject || child is JSONArray) walk(child,depth+1) }
        }
        if(j.optBoolean("structured",true)) doc.select("script[type=application/json],script[type=application/ld+json]").take(30).forEach { script ->
            val text=script.data();if(text.length<=500000) runCatching { walk(JSONTokener(text).nextValue(),0) }
        }
        val distinct=results.distinctBy { it.url }.take(200)
        return distinct to "General discovery: ${distinct.size} unique matched items; rejected=$rejected; methods=${distinct.map { it.method }.distinct().joinToString()}; history not exhausted"
    }
    fun items(j: JSONObject,html: String,page: String): MonitoringResult {
        val (values,detail)=discover(j,html,page)
        check(values.isNotEmpty()) { "No verified item addresses matched the learned rules. Try different examples or open an item and copy its page link. Baseline retained." }
        return MonitoringResult(items=values.map { MonitoringItem("custom:"+it.url,it.url,it.title,publishedAt=it.publishedAt,thumbnail=it.thumbnail) },detail=detail)
    }
}
