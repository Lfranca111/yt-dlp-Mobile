package com.linfranca.ytdlpmobile

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Exact, verified host aliases only. Never infer ownership from similar domain spelling. */
internal object WebsiteDomains {
    private val aliases=ConcurrentHashMap<String,String>()
    private val groups=ConcurrentHashMap<String,String>()
    fun host(value: String)=value.lowercase().removePrefix("www.").removePrefix("m.")
    fun site(raw: String): String?=runCatching {
        val h=host(URI(raw).host ?: return null)
        when(h) { "aniwaves.ru"->"AniWaves";"h123movies.com"->"h123movies";"wcostream.tv"->"WCOStream";else->aliases[h] }
    }.getOrNull()
    @Synchronized fun recognize(raw: String,handler: String) {
        require(handler in setOf("AniWaves","h123movies","WCOStream"))
        val root=when(handler) { "AniWaves"->"https://aniwaves.ru/";"h123movies"->"https://h123movies.com/";else->"https://wcostream.tv/" }
        require(site(raw)==null || site(raw)==handler) { "Page fingerprint conflicts with the known dedicated handler" }
        aliases[host(URI(raw).host)]=handler;remember(root,raw)
    }
    @Synchronized fun remember(original: String,target: String) {
        val a=host(URI(original).host);val b=host(URI(target).host)
        val site=site(original)
        if(site!=null && site(target)==null) aliases[b]=site
        val root=groups[a] ?: a;groups[a]=root;groups[b]=root
    }
    fun equivalent(a: String?,b: String?): Boolean {
        if(a==null || b==null) return false
        val x=host(a);val y=host(b)
        return x==y || groups[x]!=null && groups[x]==groups[y]
    }
}
