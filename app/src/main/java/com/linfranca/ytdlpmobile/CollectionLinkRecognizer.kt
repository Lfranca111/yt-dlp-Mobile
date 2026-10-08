package com.linfranca.ytdlpmobile

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Identifies pasted webpage links without making a network request or starting a download. */
internal object CollectionLinkRecognizer {
    data class Match(val site: String, val pageType: String, val hint: String)

    fun recognize(input: String): Match? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        val parts = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        val first = parts.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            if (pair.isBlank()) null else runCatching {
                val key = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8.name())
                key.lowercase(Locale.ROOT) to value
            }.getOrNull()
        }.toMap()

        val searchHint = "Search terms are already in this link. Collection downloads will be added in a later version."
        val collectionHint = "Collection downloads will be added in a later version."
        val homeHint = "Site recognized. Paste a comic, album, gallery, or search-results page link."

        return when (NativeMedia.site(host)) {
            1, 2, 3 -> {
                val site = when {
                    host.contains("e621") -> "E621"
                    host.contains("e6ai") -> "E6AI"
                    else -> "E926"
                }
                when {
                    first == "posts" && parts.size > 1 -> Match(site, "Individual post", collectionHint)
                    first == "posts" || query.containsKey("tags") -> Match(site, "Search results", searchHint)
                    else -> Match(site, "Site page", homeHint)
                }
            }
            4 -> when {
                first == "images" && parts.size > 1 -> Match("Furbooru", "Individual image", collectionHint)
                first == "tags" && parts.size > 1 -> Match("Furbooru", "Tag results", searchHint)
                first == "search" || query.containsKey("q") || query.containsKey("tags") ->
                    Match("Furbooru", "Search results", searchHint)
                else -> Match("Furbooru", "Site page", homeHint)
            }
            5 -> when {
                query["s"]?.lowercase(Locale.ROOT) == "view" && query.containsKey("id") ->
                    Match("Rule34", "Individual post", collectionHint)
                query.containsKey("tags") || (query["page"] == "post" && query["s"] == "list") ->
                    Match("Rule34", "Search results (images and videos)", searchHint)
                else -> Match("Rule34", "Site page", homeHint)
            }
            6 -> when {
                parts.size < 2 -> Match("Multporn", "Site page", homeHint)
                first in setOf("category", "characters", "category_hentai", "characters_hentai") ->
                    Match("Multporn", "Comic listing", "Downloads the comics linked from every listing page.")
                first in setOf("comics", "hentai_manga", "gay_porn_comics", "gif", "humor") ->
                    Match("Multporn", "Comic", collectionHint)
                first in setOf("pictures", "hentai", "rule_63", "games") ->
                    Match("Multporn", "Image gallery", collectionHint)
                first == "video" -> Match("Multporn", "Video page", "Site recognized. Video collection support will be considered separately.")
                else -> Match("Multporn", "Site page", homeHint)
            }
            7 -> when {
                first == "c" && parts.size > 1 -> Match("Tailspace", "Comic", collectionHint)
                first == "artist" && parts.size >= 4 && parts[2].equals("post", true) ->
                    Match("Tailspace", "Artist post", collectionHint)
                else -> Match("Tailspace", "Site page", homeHint)
            }
            8 -> when {
                parts.isEmpty() -> Match("Yiffer", "Site page", homeHint)
                first == "comics" && parts.size > 1 -> Match("Yiffer", "Comic", collectionHint)
                first != "api" -> Match("Yiffer", "Comic", collectionHint)
                else -> Match("Yiffer", "Site page", homeHint)
            }
            9 -> when {
                first in setOf("album", "albums") && parts.size > 1 ->
                    Match("Luscious", "Album", collectionHint)
                first == "pictures" && parts.size > 1 ->
                    Match("Luscious", "Picture or album page", collectionHint)
                else -> Match("Luscious", "Site page", homeHint)
            }
            else -> null
        }
    }
}
