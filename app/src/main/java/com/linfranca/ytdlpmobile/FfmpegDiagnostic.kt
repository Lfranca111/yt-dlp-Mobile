package com.linfranca.ytdlpmobile

/** Error details without the signed playback URLs and authentication headers. */
object FfmpegDiagnostic {
    private val authentication = Regex("(?i)(cookie|authorization|set-cookie)\\s*[:=]")
    private val url = Regex("(?i)(?:https?|file)://[^\\s'\"<>]+")
    private val local = Regex("(?i)(?:/data/(?:user|data)|/storage/)[^\\s'\"<>]+")
    private val secret = Regex("(?i)(?:session|token|sig|signature|key)=[^&\\s'\"<>]+")
    private val newlines = Regex("[\\r\\n]+")
    fun safeLine(line: String): String? {
        if (authentication.containsMatchIn(line)) return null
        val clean = line
            .replace(url, "[media URL]")
            .replace(local, "[local file]")
            .replace(secret, "[private value]")
            .replace(newlines, " ").trim()
        return clean.take(250).takeIf { it.isNotBlank() }
    }
}
