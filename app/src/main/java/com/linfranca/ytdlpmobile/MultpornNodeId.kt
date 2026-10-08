package com.linfranca.ytdlpmobile

import java.net.URI
import java.util.Locale

/** Only accepts IDs tied to the requested comic page, not unrelated numeric links in the page. */
internal object MultpornNodeId {
    data class Result(val id: String?, val source: String?, val diagnostics: List<String>)

    fun find(linkHeader: String?, html: String): Result {
        val notes = mutableListOf<String>()
        val headerEntries = linkHeader.orEmpty().split(Regex(",\\s*(?=<)"))
        val headerShortlinks = headerEntries.filter { relation(it)?.contains("shortlink") == true }
            .mapNotNull { Regex("""<([^>]+)>""").find(it)?.groupValues?.get(1) }
        for (link in headerShortlinks) {
            nodeId(link)?.let { return Result(it, "HTTP shortlink", notes) }
        }
        if (linkHeader != null) notes += if (headerShortlinks.isEmpty())
            "HTTP Link header had no shortlink relation; checking page metadata."
        else "HTTP shortlink did not have a numeric node ID; checking page metadata."

        val htmlShortlinks = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.value }
            .filter { relation(it)?.contains("shortlink") == true }
            .mapNotNull { attribute(it, "href") }.toList()
        for (link in htmlShortlinks) {
            nodeId(link)?.let { return Result(it, "HTML shortlink", notes) }
        }
        notes += if (htmlShortlinks.isEmpty()) "HTML had no shortlink tag."
            else "HTML shortlink did not have a numeric node ID."

        // Drupal's own current-page metadata is a fallback when its shortlinks are aliases.
        val normalHtml = html.replace("\\/", "/")
        val currentPath = Regex("""["']currentPath["']\s*:\s*["']/?node/(\d{1,18})["']""", RegexOption.IGNORE_CASE)
            .find(normalHtml)?.groupValues?.get(1)
        if (currentPath != null) return Result(currentPath, "page currentPath", notes)
        val body = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
        val bodyId = body?.let { attribute(it, "data-history-node-id") }
            ?.takeIf { it.length in 1..18 && NativeMedia.asciiDigits(it) }
        if (bodyId != null) return Result(bodyId, "page body node ID", notes)
        notes += "Page metadata had no current node ID."
        return Result(null, null, notes)
    }

    private fun nodeId(raw: String): String? = runCatching {
        val url = URI("https://multporn.net/").resolve(raw.replace("&amp;", "&"))
        if (NativeMedia.site(url.host?.lowercase(Locale.ROOT).orEmpty()) != 6)
            return@runCatching null
        val path = url.path.orEmpty()
        if (!path.startsWith('/')) return@runCatching null
        val id = path.removePrefix("/node/").let { if (it == path) it.removePrefix("/") else it }.removeSuffix("/")
        id.takeIf { it.length in 1..18 && NativeMedia.asciiDigits(it) }
    }.getOrNull()

    private fun relation(tag: String): Set<String>? {
        val raw = attribute(tag, "rel") ?: Regex("""(?:^|[;\s])rel\s*=\s*([^;\s>]+)""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1) ?: return null
        return raw.lowercase(Locale.ROOT).split(Regex("\\s+")).toSet()
    }

    private fun attribute(tag: String, name: String): String? = NativeMedia.htmlAttribute(tag, name)
}
