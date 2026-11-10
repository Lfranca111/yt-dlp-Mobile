package com.linfranca.ytdlpmobile

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** The saved URL teaches a layout; every rule supplies its own channel. Never mutate the template. */
internal object CustomSourceBinding {
    fun bind(template: JSONObject,url: String,mode: String): JSONObject {
        CustomSources.validate(template)
        val example=URI(template.getString("url"));val target=URI(url)
        require(target.scheme=="https" && WebsiteDomains.equivalent(target.host,example.host) && target.port==example.port) {
            "This custom source is for ${example.host}. Use a channel on that same website or set up another source."
        }
        require(mode in setOf("uploads","live","both")) { "Choose uploads, live or both" }
        val j=JSONObject(template.toString())
        val uploads=template.optString("uploadValidated")==CustomSetupPolicy.fingerprint(template,"uploads")
        val live=template.optString("liveValidated")==CustomSetupPolicy.fingerprint(template,"live")
        // Rebase only explicit channel path prefixes. Do not guess CSS classes or cross-site layouts.
        val old=example.rawPath.orEmpty().trimEnd('/').split('/').filter { it.isNotBlank() }
        val new=target.rawPath.orEmpty().trimEnd('/').split('/').filter { it.isNotBlank() }
        var suffix=0
        while(suffix<minOf(old.size,new.size) && old[old.lastIndex-suffix]==new[new.lastIndex-suffix]) suffix++
        val from="/"+old.dropLast(suffix).joinToString("/")
        val to="/"+new.dropLast(suffix).joinToString("/")
        fun path(value: String): String = if(from!="/" && to!="/" && from!=to &&
            (value==from || value.startsWith("$from/"))) to+value.removePrefix(from) else value
        for(key in listOf("pathPrefix","pathPattern")) if(j.has(key)) j.put(key,path(j.optString(key)))
        if(from!="/" && to!="/" && from!=to) {
            for(key in listOf("linkSelector","liveLinkSelector","liveSelector","offlineSelector","nextSelector")) {
                val value=j.optString(key)
                if(value.contains(from)) {
                    require(to.none { it=='\'' || it=='"' || it=='\\' }) { "This channel path needs its own selector setup" }
                    j.put(key,value.replace("$from/","$to/").replace(from+"\'",to+"\'").replace(from+"\"",to+"\""))
                }
            }
        }
        fun address(value: String): String = runCatching {
            val u=URI(value)
            if(u.scheme==example.scheme && u.rawAuthority==example.rawAuthority) {
                val raw=path(u.rawPath.orEmpty())
                target.scheme+"://"+target.rawAuthority+raw+(u.rawQuery?.let { "?$it" } ?: "")+(u.rawFragment?.let { "#$it" } ?: "")
            } else value
        }.getOrDefault(value)
        // URI templates contain literal {id}; preserve that token while parsing their origin/path.
        if(j.optString("urlTemplate").isNotBlank()) j.put("urlTemplate",address(j.getString("urlTemplate").replace("{id}","BINDINGITEMTOKEN")).replace("BINDINGITEMTOKEN","{id}"))
        j.optJSONArray("examples")?.let { a -> j.put("examples",JSONArray((0 until a.length()).map { address(a.getString(it)) })) }
        j.put("url",url).put("mode",mode)
        // Carry validation of the same declarative layout, not a claim of a successful new-channel check.
        if(uploads) j.put("uploadValidated",CustomSetupPolicy.fingerprint(j,"uploads"))
        if(live) j.put("liveValidated",CustomSetupPolicy.fingerprint(j,"live"))
        CustomSources.validate(j)
        return j
    }
    fun resetChannel(r: MonitoringRule) {
        r.baseline=false;r.seen.clear();r.customState=JSONObject();r.liveSession="";r.offlineChecks=0
        r.last=0;r.failures=0;r.notified="";r.status="Channel or detection rules changed; awaiting a fresh check"
    }
}
