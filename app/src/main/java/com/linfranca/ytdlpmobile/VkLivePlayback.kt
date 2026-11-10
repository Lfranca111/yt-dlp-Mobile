package com.linfranca.ytdlpmobile

import org.json.JSONObject
import java.net.URI

internal object VkLivePlayback {
    data class Candidate(val id: String,val title: String,val urls: List<String>)
    fun fromStream(stream: JSONObject, fromApi: Boolean, allowed: (String)->Boolean): Candidate? {
        if ((fromApi && stream.opt("isOnline") != true) || stream.opt("isOnline") == false ||
            stream.optBoolean("isEnded") || stream.optBoolean("isPlaybackDisabled") ||
            stream.opt("isPublic") == false || stream.opt("hasAccess") == false) return null
        val row=stream.optJSONArray("data")?.takeIf { it.length()==1 }?.optJSONObject(0) ?: return null
        val id=row.optString("vid").takeIf { it.matches(Regex("[0-9]{5,24}")) } ?: return null
        val urls=listOf("playerUrls","sharedPlayerUrls").flatMap { key ->
            val a=row.optJSONArray(key) ?: return@flatMap emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }.mapNotNull {
            it.optString("url").takeIf { url -> it.optString("type")=="live_cmaf" &&
                allowed(url) && URI(url).rawPath.substringAfterLast('/').startsWith("dash_$id.") }
        }.distinct().take(2)
        return urls.takeIf { it.isNotEmpty() }?.let { Candidate(id,stream.optString("title").ifBlank { row.optString("title") },it) }
    }

    fun requestHeaders(page: String,media: String,agent: String): Map<String,String> =
        mapOf("User-Agent" to agent,"Cache-Control" to "no-cache") +
            MediaRequestPolicy.referrer(page,media).takeIf(String::isNotBlank)?.let { mapOf("Referer" to it) }.orEmpty()
}

/** Keep the last observed request stage without recording addresses or credentials. */
internal class VkDashStartupTrace {
    private val opening=Regex("(?:Opening|DASH request for url) ['\"](https?://[^'\"\\s]+)",RegexOption.IGNORE_CASE)
    @Volatile private var stage="DASH playlist"
    @Volatile private var issue=""
    fun observe(line: String) {
        opening.find(line)?.groupValues?.get(1)?.let { address ->
            val path=runCatching { URI(address).path.orEmpty().lowercase() }.getOrDefault("")
            stage=when {
                path.endsWith(".mpd") || "stream.manifest" in path -> "DASH playlist"
                Regex("(?:^|[/_.-])init(?:ialization)?(?:$|[/_.-]|[0-9])").containsMatchIn(path) -> "initialization segment"
                path.endsWith(".m4s") || path.endsWith(".mp4") || "dash request for url" in line.lowercase() -> "DASH segment (type unconfirmed)"
                else -> "DASH request (type unconfirmed)"
            }
        }
        val lower=line.lowercase()
        if ("failed to open an initialization section" in lower) {
            stage="initialization segment"
            issue="$stage: could not open"
            return
        }
        val problem=when {
            "timed out" in lower || "connection timeout" in lower -> "request timed out"
            "invalid data" in lower -> "invalid media data"
            "failed to resolve" in lower || "name or service not known" in lower -> "DNS failure"
            else -> Regex("(?:http(?: error)?|server returned)\\s+([45][0-9]{2})\\b").find(lower)?.let { "HTTP "+it.groupValues[1] }
        }
        if(problem!=null) issue="$stage: $problem"
    }
    fun summary(): String = "last observed stage=$stage; issue=${issue.ifBlank { "none reported" }}"
}
