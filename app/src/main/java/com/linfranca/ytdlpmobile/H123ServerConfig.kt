package com.linfranca.ytdlpmobile

import java.net.URI
import java.net.URLEncoder

/** Parse public website server definitions, never execute their scripts.
 * Literal braces must be escaped: Android ICU rejects bare closing braces accepted by desktop Java. */
internal object H123ServerConfig {
    data class Server(val number: Int,val url: String)
    private val hosts=setOf("moviesapi.to","vidcore.io","yapgrid.com","vidfast.vc","vsembed.ru","embedmaster.link")
    fun parse(script: String,suffix: String): List<Server> {
        require(suffix.matches(Regex("movie/\\d+|tv/\\d+/\\d+/\\d+")))
        val tv=suffix.startsWith("tv/");val kind=if(tv) "tv" else "movie"
        val entry=Regex("""(?:^|[,{}])\s*([1-6])\s*:\s*\{\s*movie\s*:\s*["'](https://[^"']+)["']\s*,\s*tv\s*:\s*["'](https://[^"']+)["']\s*\}""")
        val options=Regex("""([1-6])\s*:\s*\{([^{}]*)\}""").findAll(script).mapNotNull { m ->
            val pairs=Regex("""(\w+)\s*:\s*["']([^"']*)["']""").findAll(m.groupValues[2]).map { it.groupValues[1] to it.groupValues[2] }.toList()
            pairs.takeIf { it.isNotEmpty() && it.all { p -> p.first in setOf("autoplay","autoPlay","sub","lang","ds_lang","welcome_page") && p.second.matches(Regex("[A-Za-z0-9_-]{1,20}")) } }?.let { m.groupValues[1].toInt() to it }
        }.toMap()
        return entry.findAll(script).mapNotNull { m ->
            val n=m.groupValues[1].toInt();val base=m.groupValues[if(tv) 3 else 2];val u=runCatching { URI(base) }.getOrNull() ?: return@mapNotNull null
            if(u.host !in hosts || u.userInfo!=null || u.port!=-1 || !u.path.endsWith("/$kind/") || u.rawQuery!=null || u.fragment!=null) return@mapNotNull null
            val query=options[n].orEmpty().joinToString("&") { (k,v)->"$k=${URLEncoder.encode(v,"UTF-8")}" }
            Server(n,base+suffix.substringAfter('/')+if(query.isBlank()) "" else "?$query")
        }.distinctBy { it.number }.sortedBy { it.number }.toList()
    }
}
