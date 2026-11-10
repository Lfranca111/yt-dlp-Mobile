package com.linfranca.ytdlpmobile

import java.net.URI

/** Strong identity only: a CDN host alone never proves two tracks belong to the same video. */
internal object DownloadMediaPolicy {
    fun identity(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        if (host == "video.twimg.com" || host == "pbs.twimg.com") {
            Regex("/(?:amplify_video|ext_tw_video|tweet_video)(?:_thumb)?/(\\d+)(?:/|$)").find(path)?.let {
                return "twitter:" + it.groupValues[1]
            }
        }
        if (path.contains("/videoplayback")) {
            uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "id" }
                ?.substringAfter('=')?.takeIf(String::isNotBlank)?.let { return "youtube:$it" }
        }
        Regex("/chunklist_(\\d+)_(?:video|audio)_([A-Za-z0-9]+)_llhls\\.m3u8$").find(path)?.let {
            return "$host:${path.substringBeforeLast('/')}:${it.groupValues[1]}:${it.groupValues[2]}"
        }
        return null
    }
    fun matching(first: String, second: String): Boolean = identity(first)?.let { it == identity(second) } == true
    fun audioOnly(url: String): Boolean = MediaUrlClassifier.streamKind(url) == MediaUrlClassifier.StreamKind.AUDIO ||
        Regex("(?i)/(?:mp4a|aac|audio)/").containsMatchIn(URI(url).path.orEmpty())
    fun partial(url: String): Boolean = URI(url).rawQuery.orEmpty().split('&').any {
        it.substringBefore('=').lowercase() in setOf("bytestart", "byteend", "range")
    }
    fun group(url: String) = identity(url) ?: url.substringBefore('?')
}
