package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

internal object RumbleMonitoring {
    private val site = CaptureSiteRoutes.Site.RUMBLE
    private const val cookieHelp = "Import cookies.txt from a signed-in Rumble session for direct downloads."

    fun accessHelp(url: String, failure: String, monitored: Boolean): String? {
        if (CaptureSiteRoutes.site(url) != site ||
            !failure.lineSequence().any { it.contains("[RumbleEmbed]", true) &&
                it.contains("JSON metadata", true) && DownloadFailurePolicy.access(it) }) return null
        return "Rumble rejected the player metadata request (403). $cookieHelp " +
            if (monitored) "Retry this item after importing it, or open Browser Assist from Monitoring."
            else "Browser Assist can use the playing video."
    }

    fun fromExtractor(page: String, info: JSONObject): List<MonitoringItem> {
        val owner = owner(page) ?: return emptyList()
        return extractorRows(page,info).mapNotNull { row ->
            val link = resolve(page, row.optString("webpage_url").ifBlank { row.optString("url") })
            val author = row.optString("channel_url").ifBlank { row.optString("uploader_url") }
            if (author.isNotBlank() && !owner(resolve(page, author)).equals(owner, true)) return@mapNotNull null
            if (row.optBoolean("is_live") ||
                row.optString("live_status") !in setOf("not_live", "was_live")) return@mapNotNull null
            item(link, row.optString("title"))
        }.distinctBy { it.id }
    }

    private fun extractorRows(page: String, info: JSONObject): List<JSONObject> {
        val creator = owner(page) ?: return emptyList()
        if (info.optString("extractor_key") != "RumbleChannel" ||
            !info.optString("id").equals(creator.substringAfter('/'), true) ||
            !owner(resolve(page, info.optString("webpage_url"))).equals(creator, true)) return emptyList()
        val entries=info.optJSONArray("entries") ?: return emptyList()
        return (0 until minOf(entries.length(),40)).mapNotNull { entries.optJSONObject(it) }
    }

    fun fromPage(page: String, html: String, extraction: JSONObject? = null): List<MonitoringItem> {
        val creator = owner(page) ?: return emptyList()
        val doc = Jsoup.parse(html, page)
        val identity = doc.select("link[rel=canonical][href], meta[property=og:url][content]").map {
            if (it.tagName() == "link") it.absUrl("href") else resolve(page, it.attr("content"))
        }
        if (identity.isEmpty() || identity.any { !owner(it).equals(creator, true) }) return emptyList()
        val listedIds = extraction?.let { extractorRows(page,it) }.orEmpty().mapNotNull { row ->
            val author=row.optString("channel_url").ifBlank { row.optString("uploader_url") }
            if (author.isNotBlank() && !owner(resolve(page,author)).equals(creator,true)) return@mapNotNull null
            if (row.optBoolean("is_live") || row.optString("live_status") in setOf("is_live","is_upcoming","post_live")) return@mapNotNull null
            val link=resolve(page,row.optString("webpage_url").ifBlank { row.optString("url") })
            CaptureSiteRoutes.uploadId(link,site)?.lowercase()
        }.toSet()
        val main = doc.selectFirst("main, [role=main]") ?: return emptyList()
        return main.select("a.videostream__link[href], a.video-item--a[href]").asSequence().mapNotNull { anchor ->
            val card = anchor.closest(".videostream, .videostream__item, .video-item, li, article") ?: return@mapNotNull null
            if (card.closest("aside, nav, [class*=recommend], [class*=suggest], [class*=related]") != null) return@mapNotNull null
            val authors = card.select("a[href]").mapNotNull { owner(it.absUrl("href")) }.distinct()
            val id=CaptureSiteRoutes.uploadId(anchor.absUrl("href"),site)?.lowercase() ?: return@mapNotNull null
            if (authors.any { !it.equals(creator, true) } || (authors.isEmpty() && id !in listedIds))
                return@mapNotNull null
            val badges = card.select("[class*=badge], [class*=status], [class*=duration], [class*=time]").eachText()
            if (badges.any { active.containsMatchIn(it.trim()) } ||
                card.selectFirst("[data-live=true], [data-is-live=true]") != null ||
                badges.none { duration.matches(it.trim()) }) return@mapNotNull null
            val title = card.selectFirst(".videostream__title, .video-item--title, h2, h3")?.text()
                ?: anchor.attr("title").ifBlank { anchor.attr("aria-label").ifBlank { anchor.text() } }
            item(anchor.absUrl("href"), title)
        }.distinctBy { it.id }.take(40).toList()
    }

    private val active = Regex("(?i)^(?:live(?: now)?|upcoming|premiere|scheduled|starting soon)$")
    private val duration = Regex("(?:[0-9]{1,3}:)?[0-9]{1,2}:[0-5][0-9]")
    private fun resolve(page: String, value: String): String =
        if (value.isBlank()) "" else runCatching { URI(page).resolve(value).toString() }.getOrDefault("")
    private fun owner(url: String): String? = CaptureSiteRoutes.live(url)
        ?.takeIf { it.site == site && '/' in it.id }?.id
    private fun item(url: String, title: String): MonitoringItem? {
        val id = CaptureSiteRoutes.uploadId(url, site) ?: return null
        return MonitoringItem("Capture:RUMBLE:${id.lowercase()}", "https://rumble.com"+URI(url).rawPath, title.take(160))
    }
}
