package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.json.JSONTokener

/** Metadata comes only from stdout. Warnings and other diagnostic text are never metadata. */
internal object WebsiteMetadata {
    fun parse(stdout: String): JSONObject {
        val text=stdout.trim().removePrefix("\uFEFF")
        fun candidate(value: String): JSONObject? = runCatching {
            val input=JSONTokener(value)
            val result=input.nextValue() as? JSONObject ?: return@runCatching null
            if(input.nextClean()!='\u0000') return@runCatching null
            val formats=result.optJSONArray("formats")
            val playable=(formats!=null && (0 until formats.length()).any { validUrl(formats.optJSONObject(it)?.optString("url").orEmpty()) }) || validUrl(result.optString("url"))
            if(!playable) null else result
        }.getOrNull()
        candidate(text)?.let { return it }
        // Some extractors print an incidental line to stdout. --dump-single-json emits its
        // metadata as one complete line, so accept only a complete playable metadata object.
        val matches=text.lineSequence().map(String::trim).filter { it.startsWith('{') }.mapNotNull(::candidate).take(2).toList()
        check(matches.size==1) { "yt-dlp did not return one playable metadata object on stdout" }
        return matches.single()
    }
    private fun validUrl(raw: String)=runCatching {
        val uri=java.net.URI(raw)
        uri.scheme in setOf("https","http") && !uri.host.isNullOrBlank() && uri.userInfo==null
    }.getOrDefault(false)
    fun outputKind(raw: String): String {
        val text=raw.trim().removePrefix("\uFEFF")
        return when {
            text.isEmpty() -> "empty"
            text.startsWith('{') -> "JSON object candidate"
            text.startsWith('[') -> "log or array prefix"
            text.startsWith("WARNING:") || text.startsWith("ERROR:") -> "diagnostic prefix"
            text.startsWith('<') -> "HTML/XML prefix"
            else -> "non-JSON prefix"
        }
    }
}
