package com.linfranca.ytdlpmobile

import java.net.URI
import org.jsoup.Jsoup

internal object TailspaceComicImages {
    private val label = Regex("""(?:^|,\s*)page\s+(\d{1,4})\s*$""", RegexOption.IGNORE_CASE)
    private val preview = Regex("""(?:^|[-_.])(?:mini|thumb(?:nail)?|preview|placeholder)(?:[-_.]|$)""", RegexOption.IGNORE_CASE)

    data class Page(val image: CollectionImageQuality.Choice, val width: Int, val reader: Boolean) {
        val isPreview: Boolean get() = preview.containsMatchIn(URI(image.url).path.substringAfterLast('/'))
        fun betterThan(other: Page): Boolean {
            if (isPreview != other.isPreview) return !isPreview
            // Prefer an explicit reader image over a duplicate decoration with no reliable size.
            if (reader != other.reader) return reader
            if (width != other.width) return width > other.width
            return image.previous.isNotBlank() && other.image.previous.isBlank()
        }
    }

    fun read(html: String): Map<Int, Page> {
        val pages = linkedMapOf<Int, Page>()
        for (img in Jsoup.parse(html).select("img[alt]")) {
            val number = label.find(img.attr("alt"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (number !in 1..5000) continue
            val sources = listOfNotNull(fullPageUrl(img.attr("src")), fullPageUrl(img.attr("data-src")))
            val baseline = sources.firstOrNull { !preview.containsMatchIn(URI(it).path.substringAfterLast('/')) }
                ?: sources.firstOrNull() ?: continue
            val image = CollectionImageQuality.choose(img, baseline, ::fullPageUrl)
            val width = img.attr("width").toIntOrNull()?.coerceAtLeast(0) ?: 0
            mergeOne(pages, number, Page(image, width, img.hasClass("comicPage")))
        }
        return pages
    }

    fun merge(into: MutableMap<Int, Page>, from: Map<Int, Page>) =
        from.forEach { (number, page) -> mergeOne(into, number, page) }

    private fun mergeOne(into: MutableMap<Int, Page>, number: Int, page: Page) {
        val previous = into[number]
        if (previous == null || page.betterThan(previous)) into[number] = page
    }

    private fun fullPageUrl(raw: String): String? {
        val url = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val path = url.path ?: return null
        if (!url.scheme.equals("https", true) || !url.host.equals("pics.tailspace.com", true) ||
            url.userInfo != null || url.port !in listOf(-1, 443) || !path.startsWith("/comics/")) return null
        return url.toASCIIString()
    }
}
