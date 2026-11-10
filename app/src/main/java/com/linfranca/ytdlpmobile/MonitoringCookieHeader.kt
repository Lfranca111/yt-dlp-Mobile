package com.linfranca.ytdlpmobile

/** Imported entries have already been filtered for target host/path/expiry by the existing jar reader. */
internal object MonitoringCookieHeader {
    fun merge(imported: String,browser: String): String {
        val cookies=linkedMapOf<String,String>()
        for(header in listOf(imported,browser)) for(part in header.split(';')) {
            val at=part.indexOf('=');if(at<=0) continue
            val name=part.substring(0,at).trim();val value=part.substring(at+1).trim()
            if(name.isNotEmpty() && !part.contains('\n') && !part.contains('\r')) cookies[name]=value
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
