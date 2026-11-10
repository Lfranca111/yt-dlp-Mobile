package com.linfranca.ytdlpmobile

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Pure identity and discovery decisions; an unseen URL alone is never proof of a new upload. */
internal object CustomUploadPolicy {
    private val tracking=setOf("pp","si","feature","ref","ref_src","ref_url","fbclid","gclid","igsh","igshid","t","start","time_continue","index","list","source","spm","from","share","share_id")
    fun query(uri: URI): Map<String,String> = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.mapNotNull {
        runCatching { URLDecoder.decode(it.substringBefore('='),"UTF-8") to URLDecoder.decode(it.substringAfter('=',""),"UTF-8") }.getOrNull()
    }.toMap()
    private fun useful(key: String)=key.lowercase() !in tracking && !key.startsWith("utm_",true)
    fun canonical(raw: String,keys: List<String> = emptyList()): String {
        val u=URI(raw.removePrefix("custom:"));var host=u.host?.lowercase() ?: error("Video link has no website")
        if(host in setOf("youtube.com","m.youtube.com","www.youtube.com")) host="www.youtube.com"
        val q=query(u).filterKeys { useful(it) && (keys.isEmpty() || it in keys) }
        val kept=if(host=="www.youtube.com" && u.path=="/watch" && q.containsKey("v")) q.filterKeys { it=="v" } else q
        val encoded=kept.toSortedMap().entries.joinToString("&") { URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8") }
        return "https://"+host+(if(u.port>=0 && u.port!=443) ":${u.port}" else "")+u.rawPath.orEmpty().ifBlank { "/" }+(if(encoded.isBlank()) "" else "?$encoded")
    }
    fun keys(j: JSONObject): List<String> = j.optJSONArray("identityKeys")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    data class Suggestion(val prefix: String,val pattern: String,val keys: List<String>)
    fun suggest(examples: List<String>): Suggestion {
        val urls=examples.distinct().map(::URI)
        require(urls.size>=2) { "Select at least two different videos so the app can compare their links" }
        require(urls.map { it.host }.distinct().size==1) { "Choose examples from the same website" }
        val paths=urls.map { it.path }
        val queries=urls.map(::query)
        val varying=queries.first().keys.filter { k -> useful(k) && queries.all { it[k]!=null } && queries.map { it[k] }.distinct().size>1 }
        if(paths.distinct().size==1) {
            require(varying.isNotEmpty()) { "These examples do not expose different video identities. Choose video titles instead of channel links." }
            return Suggestion(paths.first(),paths.first(),varying)
        }
        val parts=paths.map { it.trim('/').split('/') }
        val pattern=if(parts.map { it.size }.distinct().size==1) "/"+parts.first().indices.joinToString("/") { i -> if(parts.map { it[i] }.distinct().size==1) parts[0][i] else "*" } else ""
        var prefix=paths.first().substringBeforeLast('/',"")+"/"
        while(paths.any { !it.startsWith(prefix) } && prefix!="/") prefix=prefix.trimEnd('/').substringBeforeLast('/',"")+"/"
        require(prefix!="/" || (pattern.isNotBlank() && pattern!="/*")) { "The examples have no specific video pattern. Choose examples from one video list, or ask for site support." }
        return Suggestion(prefix,pattern,varying)
    }
    fun matchesPath(path: String,pattern: String): Boolean {
        if(pattern.isBlank()) return true
        val a=path.trim('/').split('/');val b=pattern.trim('/').split('/')
        return a.size==b.size && a.indices.all { b[it]=="*" || a[it]==b[it] }
    }
    data class Decision(val item: MonitoringItem,val kind: String,val reason: String)
    data class Outcome(val state: JSONObject,val seen: Set<String>,val decisions: List<Decision>,val baseline: Boolean) {
        val fresh get()=decisions.filter { it.kind=="new" }.map { it.item }
        val uncertain get()=decisions.count { it.kind=="uncertain" }
        fun summary()="Found ${decisions.size} videos; existing=${decisions.count { it.kind=="existing" }}; new=${fresh.size}; uncertain=$uncertain. "+
            (if(baseline) "Baseline established; no existing videos queued. " else "")+
            (if(!state.optBoolean("coverage")) "Historical coverage incomplete; undated items need a validated newest-first head or review." else "Captured baseline reviewed.")
    }
    fun decide(old: JSONObject,known: Set<String>,items: List<MonitoringItem>,now: Long,coverage: Boolean,newestFirst: Boolean,headValidated: Boolean=false): Outcome {
        require(items.isNotEmpty()) { "No matching videos. Existing baseline was retained." }
        val state=JSONObject(old.toString());val first=state.optInt("version")!=2 || !state.optBoolean("initialized")
        val extending=!first && !state.optBoolean("coverage") && coverage
        val cutoff=if(first) now else state.optLong("cutoff",now)
        val seen=known.toMutableSet();val pending=state.optJSONObject("pending") ?: JSONObject()
        val previousHead=state.optJSONArray("head")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val ordered=items.map { it.id }
        val anchor=previousHead.firstOrNull()?.let(ordered::indexOf) ?: -1
        // Require at least two old head entries in their prior order before trusting an undated new prefix.
        val surviving=previousHead.filter { it in ordered }
        val stable=surviving.size>=2 && surviving.map(ordered::indexOf).zipWithNext().all { it.first<it.second }
        val ready=state.optBoolean("coverage") || coverage
        val decisions=items.distinctBy { it.id }.mapIndexed { index,item ->
            val d=when {
                first || extending -> Decision(item,"existing",if(first) "initial baseline or safe migration; no download" else "reviewed baseline extension; no download")
                item.id in seen -> Decision(item,"existing",if(pending.has(item.id)) "already recorded as uncertain; review retained" else "stable identity already known")
                item.publishedAt>0 && item.publishedAt<=cutoff -> Decision(item,"existing","publication time is before initial baseline")
                item.publishedAt>now+300000 -> Decision(item,"uncertain","publication time is in the future")
                item.publishedAt>cutoff -> Decision(item,"new","publication time is after baseline cutoff")
                (ready || headValidated) && newestFirst && stable && anchor>0 && index<anchor -> Decision(item,"new","new prefix before stable head; user confirmed newest-first order")
                else -> Decision(item,"uncertain","newly visible item without sufficient evidence of a new upload")
            }
            if(first || extending) pending.remove(item.id)
            if(d.kind=="uncertain") pending.put(item.id,JSONObject().put("id",item.id).put("url",item.url).put("title",item.title).put("reason",d.reason))
            seen.add(item.id);d
        }
        state.put("version",2).put("initialized",true).put("cutoff",cutoff).put("coverage",ready)
            .put("head",JSONArray(if(first || extending || ((ready || headValidated) && newestFirst && stable && anchor>=0)) ordered.take(12) else previousHead))
            .put("pending",pending)
        return Outcome(state,seen,decisions,first || extending)
    }
}
