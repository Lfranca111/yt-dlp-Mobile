package com.linfranca.ytdlpmobile

import java.net.URI

object MediaUrlClassifier {
    enum class StreamKind { COMBINED, VIDEO, AUDIO, UNKNOWN }
    private val directMediaExtension = Regex(
        "\\.(mp4|m4v|webm|m3u8|mpd)(?:$|[?#])",
        RegexOption.IGNORE_CASE
    )

    private val blockedHostTokens = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "adservice",
        "adserver", "adnxs", "adsystem", "imasdk", "tracking", "analytics"
    )

    private val blockedPathTokens = listOf(
        "/ads/", "/ad/", "adserver", "advert", "preroll", "midroll",
        "postroll", "vast", "vmap", "tracking", "analytics", "beacon",
        "/pixel", "pre_videos", "pre-video", "preview", "thumbnail", "/thumb/",
        "/poster/", "promo_video"
    )

    private val blockedQueryTokens = listOf(
        "adformat=", "ad_type=", "adtype=", "is_ad=", "preroll=", "vast=", "vmap="
    )

    fun isDirectMediaUrl(rawUrl: String): Boolean {
        val url = rawUrl.trim().replace("\\u0026", "&").replace("\\/", "/")
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.lowercase()?.takeIf { it.contains('.') } ?: return false
        if (url.any(Char::isWhitespace)) return false

        val path = uri.rawPath.orEmpty().lowercase()
        val query = uri.rawQuery.orEmpty().lowercase()
        NativeMedia.directParts(host, path, query)?.let { return it }
        val hostLabels = host.split('.')
        val queryKeys = query.split('&')
            .map { it.substringBefore('=').lowercase() }
            .toSet()
        if (blockedHostTokens.any(host::contains)) return false
        if (hostLabels.any { it in setOf("ad", "ads", "advert", "advertising", "adserver") }) return false
        if (blockedPathTokens.any(path::contains)) return false
        if (blockedQueryTokens.any(query::contains)) return false
        if (queryKeys.any { it in setOf("ad", "ads", "ad_type", "adtype", "is_ad", "preroll", "vast", "vmap") }) return false

        // Only direct media endpoints qualify. API/wrapper URLs that merely carry a
        // nested media-looking parameter are intentionally excluded.
        return directMediaExtension.containsMatchIn(path) ||
            path.contains("/videoplayback") ||
            path.endsWith("/manifest") ||
            path.contains("/manifest/")
    }

    /** Preserve the working v0.4.0 candidate set; reject only explicit control traffic. */
    fun isDownloadCandidate(rawUrl: String): Boolean {
        if (!isDirectMediaUrl(rawUrl) || isExpired(rawUrl)) return false
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val path = uri.rawPath.orEmpty().lowercase()
        val query = uri.rawQuery.orEmpty().lowercase()
        NativeMedia.controlParts(path, query)?.let { return !it }
        val keys = query.split('&').map { it.substringBefore('=') }.toSet()
        if (path.contains("/videoplayback")) {
            if (keys.any { it in setOf("sabr", "ump", "sabr_spec", "sabr_contexts") }) return false
        }
        return true
    }

    /** A live recorder needs a playlist it can refresh, never a single segment or MP4 file. */
    fun isContinuousManifest(rawUrl: String): Boolean {
        if (!isDownloadCandidate(rawUrl)) return false
        val path = runCatching { URI(rawUrl).rawPath.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    fun isExpired(rawUrl: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L): Boolean {
        val query = runCatching { URI(rawUrl).rawQuery }.getOrNull().orEmpty()
        val expiry = query.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=').lowercase()
                if (key in setOf("e", "exp", "expires", "expire")) {
                    NativeMedia.decimal(part.substringAfter('=', ""))
                } else null
            }
            .firstOrNull() ?: return false
        return expiry <= nowEpochSeconds + EXPIRY_SAFETY_SECONDS
    }

    fun score(url: String, activePlayerUrl: Boolean, capturedAtMs: Long): Long {
        if (!isDirectMediaUrl(url)) return Long.MIN_VALUE
        val lower = url.lowercase()
        NativeMedia.score(lower, activePlayerUrl, capturedAtMs)?.let { return it }
        val formatScore = when {
            lower.contains("master.m3u8") -> 5_000L
            NativeMedia.extensions(lower, 8) -> 4_500L
            NativeMedia.extensions(lower, 16) -> 4_250L
            NativeMedia.extensions(lower, 7) -> 4_000L
            lower.contains("/videoplayback") -> 3_750L
            else -> 3_000L
        }
        val contentHint = when {
            lower.contains("/hls/") -> 400L
            lower.contains("/dash/") -> 350L
            lower.contains("/videos/") -> 300L
            else -> 0L
        }
        val activeBonus = if (activePlayerUrl) 10_000L else 0L
        return activeBonus + formatScore + contentHint + capturedAtMs.coerceAtLeast(0L) / 1_000_000L
    }

    fun streamKind(rawUrl: String): StreamKind {
        if (!isDirectMediaUrl(rawUrl)) return StreamKind.UNKNOWN
        val lower = runCatching { URI(rawUrl).let { uri ->
            uri.rawPath.orEmpty() + uri.rawQuery?.let { "?$it" }.orEmpty()
        } }
            .getOrDefault(rawUrl).lowercase()
            .replace("%2f", "/")
            .replace("%3d", "=")
            .replace("%3a", ":")
        val query = runCatching { URI(rawUrl).rawQuery.orEmpty() }.getOrDefault("")
        val itag = query.split('&').firstOrNull { it.substringBefore('=').equals("itag", true) }
            ?.substringAfter('=')?.toIntOrNull()

        NativeMedia.streamKind(lower, itag ?: -1)?.let { return when (it) { 0 -> StreamKind.COMBINED; 1 -> StreamKind.VIDEO; 2 -> StreamKind.AUDIO; else -> StreamKind.UNKNOWN } }
        if (itag in AUDIO_ONLY_ITAGS || AUDIO_HINT.containsMatchIn(lower)) return StreamKind.AUDIO
        if (itag in VIDEO_ONLY_ITAGS || VIDEO_HINT.containsMatchIn(lower)) return StreamKind.VIDEO
        // A file named master.m3u8 can still be an audio-only rendition.
        if (lower.contains("master.m3u8") || NativeMedia.extensions(lower, 16) ||
            lower.contains("/manifest/") || lower.endsWith("/manifest")) {
            return StreamKind.COMBINED
        }
        return StreamKind.UNKNOWN
    }

    fun sameStreamFamily(first: String, second: String): Boolean {
        val firstHost = runCatching { URI(first).host.orEmpty().lowercase() }.getOrDefault("")
        val secondHost = runCatching { URI(second).host.orEmpty().lowercase() }.getOrDefault("")
        if (firstHost.isBlank() || secondHost.isBlank()) return false
        if (firstHost == secondHost) return true
        fun site(host: String) = host.split('.').takeLast(2).joinToString(".")
        return site(firstHost) == site(secondHost)
    }

    private const val EXPIRY_SAFETY_SECONDS = 30L
    private val AUDIO_HINT = Regex("(?:^|[/_.?&=:-])(audio|aac|m4a|opus|mp3)(?:$|[/_.?&=:-])")
    private val VIDEO_HINT = Regex("(?:^|[/_.?&=:-])(video|avc|h264|h265|hevc|vp9|av1|\\d{3,4}p)(?:$|[/_.?&=:-])")
    private val AUDIO_ONLY_ITAGS = setOf(139, 140, 141, 171, 172, 249, 250, 251)
    private val VIDEO_ONLY_ITAGS = setOf(133, 134, 135, 136, 137, 138, 160, 242, 243, 244, 247, 248, 264, 266, 271, 272, 278, 298, 299, 302, 303, 308, 313, 315)
}
