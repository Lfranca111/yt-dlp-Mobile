package com.linfranca.ytdlpmobile

/** Classifies only the current downloader attempt, never previous handler failures. */
internal object DownloadFailurePolicy {
    fun rateLimited(text: String)=Regex("(?i)(?:HTTP(?:\\s+Error|/[0-9.]+)?\\s*[:=]?\\s*|status\\s*[:=]\\s*)429\\b").containsMatchIn(text)
    fun unsupported(text: String)=text.contains("Unsupported URL:",true)
    private val status=Regex("(?i)(?:HTTP(?:\\s+Error|/[0-9.]+)?\\s*[:=]?\\s*|status\\s*[:=]\\s*)(403|429|5[0-9]{2})\\b")
    fun access(text: String)=Regex("(?i)(?:HTTP(?:\\s+Error|/[0-9.]+)?\\s*[:=]?\\s*|status\\s*[:=]\\s*)403\\b|\\bforbidden\\b").containsMatchIn(text)
    fun parallelRetry(text: String,parallel: Boolean): Boolean {
        if(!parallel || unsupported(text)) return false
        val fragment=Regex("(?i)(?:retrying\\s+fragment|fragment\\s+(?:[0-9]+|#\\s*[0-9]+|failed|error|download)|unable to download[^\\n]*fragment)").containsMatchIn(text)
        val transport=status.containsMatchIn(text) || Regex("(?i)timed?\\s*out|timeout|connection\\s+reset|fragment\\s+failed").containsMatchIn(text)
        return fragment && transport
    }
}

/** Bounded local diagnostics for a single execute call. Full task history is retained separately. */
internal class DownloadAttemptLog(private val limit: Int=16000) {
    init { require(limit>0) }
    private val buffer=StringBuilder()
    @Synchronized fun append(line: String) {
        buffer.append(line).append('\n')
        if(buffer.length>limit) buffer.delete(0,buffer.length-limit)
    }
    @Synchronized fun failure(error: Throwable): String=buffer.toString()+"\n"+error.message.orEmpty()
}
