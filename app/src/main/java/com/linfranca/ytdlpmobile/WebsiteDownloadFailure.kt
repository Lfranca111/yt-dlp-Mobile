package com.linfranca.ytdlpmobile

/** Normal website downloads only. Browser Assist keeps its existing execution and recovery paths. */
internal class WebsiteDownloadFailure(private val site: String?,private val browserCaptured: Boolean,private val url: String) {
    data class Reason(val kind: String,val detail: String)
    private var preparation: Reason?=null
    private var media: Reason?=null
    val serialAni get()=!browserCaptured && site=="AniWaves"
    fun preparationFailed(reason: Reason) { preparation=reason }
    fun mediaFailed(error: Throwable,text: String) { media=describe(error,text) }
    fun shouldRetryTransport(text: String): Boolean = serialAni && !DownloadFailurePolicy.unsupported(text) &&
        (Regex("(?i)HTTP(?:\\s+Error)?\\s*[:=]?\\s*5[0-9]{2}\\b").containsMatchIn(text) ||
            Regex("(?i)timed?\\s*out|timeout|connection\\s+reset|temporary failure in name resolution").containsMatchIn(text))
    fun message(default: String,error: Throwable,lastAttempt: String,longServerWait: Boolean=false): String {
        if(browserCaptured) return default
        val reason=media ?: preparation ?: describe(error,lastAttempt)
        val waiting=if(longServerWait) " The server requested a longer wait; no early retry was made." else ""
        return when {
            reason.kind=="finalization" -> "The media transfer reached local finalization, but FFmpeg could not finish the output file. Downloaded media was retained; use Save partial video or export fragment diagnostics in Queue & history. No complete video was reported."
            site=="AniWaves" -> "AniWaves could not download automatically. ${reason.detail}.$waiting Continue using the existing Browser-assisted download option for this episode; select the intended SUB/DUB and server there."
            site=="h123movies" && (preparation!=null || media!=null) -> {
                val server=runCatching { java.net.URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=')=="ydlp_server" }?.substringAfter('=')?.toIntOrNull() }.getOrNull()
                "h123movies${server?.let { " Server $it" }.orEmpty()} could not download automatically. ${reason.detail}.$waiting Try another server using the normal download selector, or use the existing Browser-assisted download option."
            }
            else -> default
        }
    }
    companion object {
        /** Only fixed descriptions and exception types, never response payloads, URLs or tokens. */
        fun describe(error: Throwable,text: String=""): Reason {
            val causes=generateSequence(error) { it.cause }.take(8).toList()
            val combined=(listOf(text)+causes.map { it.message.orEmpty() }).joinToString("\n")
            return when {
                combined.contains("English audio",true) || combined.contains("English output audio",true) -> Reason("audio-language","The selected English audio is unavailable or could not be verified; no other language was substituted")
                PostprocessRecoveryPolicy.failure(combined) -> Reason("finalization","Local media finalization failed after transfer")
                causes.any { it is java.util.regex.PatternSyntaxException } -> Reason("parser","The website parser expression was rejected (index ${causes.filterIsInstance<java.util.regex.PatternSyntaxException>().first().index}); this is an app parser error")
                causes.any { it.javaClass.simpleName=="JSONException" } -> Reason("response","The player response could not be parsed")
                DownloadFailurePolicy.rateLimited(combined) -> Reason("rate-limit","The media server returned HTTP 429 (too many requests)")
                DownloadFailurePolicy.access(combined) -> Reason("access","The website rejected access (HTTP 403)")
                Regex("(?i)HTTP(?:\\s+Error)?\\s*[:=]?\\s*5[0-9]{2}\\b").containsMatchIn(combined) -> Reason("server","The website returned a server error (HTTP 5xx)")
                causes.any { it is java.net.SocketTimeoutException } || Regex("(?i)timed?\\s*out|timeout").containsMatchIn(combined) -> Reason("timeout","The website request timed out")
                causes.any { it is java.net.UnknownHostException } || combined.contains("No address associated with hostname",true) -> Reason("dns","The website host could not be resolved")
                combined.contains("Player source not available",true) -> Reason("unavailable","The selected server did not report available playback")
                combined.contains("No playable source",true) -> Reason("media-list","The server response did not include a playable media entry")
                combined.contains("different title identity",true) -> Reason("identity","The server returned a different title identity; it was not downloaded")
                combined.contains("Player source did not return media",true) || combined.contains("Embedded playback source unavailable",true) -> Reason("media-access","The selected playback address did not return accessible media")
                combined.contains("Invalid website/playback address",true) -> Reason("media-url","The server returned a missing or invalid playback address")
                combined.contains("changed",true) || combined.contains("configuration",true) -> Reason("configuration","The player configuration could not be resolved")
                combined.contains("Incomplete",true) || combined.contains("duration",true) -> Reason("incomplete","The downloaded media did not pass completion checks")
                else -> Reason("preparation","Playback failed (${error.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"),"").take(60)}); see the retained stage diagnostics")
            }
        }
    }
}
