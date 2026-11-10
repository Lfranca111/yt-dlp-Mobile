package com.linfranca.ytdlpmobile

import android.content.Context
import java.net.HttpURLConnection
import java.net.URI

internal object MonitoringLinks {
    fun resolve(c: Context,raw: String,stopped: ()->Boolean): String {
        var url=MonitoringPolicy.normalize(raw)
        if(url!=raw) MonitoringStore.log(c,"built-in link normalized; exact host alias applied; path/query retained; values omitted")
        if(!MonitoringLinkPolicy.shortLink(url)) {
            MonitoringLinkPolicy.validateChannel(url)
            return PageRedirects.resolve(c,url,stopped,{ MonitoringStore.log(c,it) },extractorFallback=true).target
        }
        val visited=mutableSetOf<String>();val deadline=System.nanoTime()+20_000_000_000L
        repeat(5) {
            if(stopped()) throw java.util.concurrent.CancellationException("Monitoring check canceled")
            check(visited.add(url)) { "Share link redirect loop; paste the channel address from your browser" }
            val remaining=((deadline-System.nanoTime())/1_000_000L).toInt()
            check(remaining>0) { "Share link timed out; paste the channel address from your browser" }
            val connection=URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false;connection.connectTimeout=minOf(remaining,5000);connection.readTimeout=minOf(remaining,5000)
            connection.setRequestProperty("User-Agent","Mozilla/5.0")
            try {
                val status=connection.responseCode
                MonitoringStore.log(c,"built-in share-link resolution; hop=$it; HTTP=$status; no cookies or credentials forwarded")
                if(status in 300..399) {
                    val next=URI(url).resolve(connection.getHeaderField("Location") ?: error("Share link redirect has no destination")).toString()
                    url=MonitoringPolicy.normalize(next)
                    check(URI(url).scheme=="https") { "Share link redirected to an insecure or unsupported address" }
                    if(!MonitoringLinkPolicy.shortLink(url)) {
                        check(MonitoringPolicy.handler(url)!="General fallback") { "Share link did not identify a supported channel website" }
                        MonitoringLinkPolicy.validateChannel(url)
                        MonitoringStore.log(c,"built-in share-link resolved; handler=${MonitoringPolicy.handler(url)}; channel validation passed; values omitted")
                        return url
                    }
                } else error("Share link did not expose a redirect (HTTP $status). Open it and paste the final channel/profile address.")
            } finally { connection.disconnect() }
        }
        error("Too many share-link redirects; paste the final channel/profile address")
    }
}
