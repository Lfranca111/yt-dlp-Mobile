package com.linfranca.ytdlpmobile

import org.jsoup.nodes.Element
import java.net.URI

/** Only compare variants explicitly advertised for the same reader image. */
internal object CollectionImageQuality {
    private val candidate = Regex("""^\s*(\S+)\s+(\d+(?:\.\d+)?)([wx])\s*$""")
    data class Choice(val url: String, val previous: String = "") {
        val identity: String get() = previous.ifBlank { url }
    }

    fun canUsePrevious(site: String, status: Int): Boolean =
        status == 404 || (status in setOf(403, 410) &&
            (site == "Tailspace" || site in CollectionSiteProfile.sites.values))

    fun choose(image: Element, baseline: String, resolve: (String) -> String?): Choice {
        // A media-qualified picture source can show a different crop; do not cross those sets.
        val sets = listOf(image.attr("srcset"), image.attr("data-srcset")) +
            image.parent()?.takeIf { it.tagName() == "picture" }?.children().orEmpty()
                .filter { it.tagName() == "source" && !it.hasAttr("media") }
                .flatMap { listOf(it.attr("srcset"), it.attr("data-srcset")) }
        val base = URI(baseline)
        val ext = base.path.substringAfterLast('.').lowercase()
        var upgrade: String? = null
        var ratio = 1.0
        for (set in sets) {
            val variants = set.split(',').mapNotNull { field ->
                val m = candidate.matchEntire(field) ?: return@mapNotNull null
                val size = m.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
                val url = resolve(m.groupValues[1]) ?: return@mapNotNull null
                val target = URI(url)
                if (!base.host.equals(target.host, true) || base.port != target.port ||
                    !target.path.substringAfterLast('.').equals(ext, true)) return@mapNotNull null
                Triple(url, size, m.groupValues[3])
            }
            val current = variants.firstOrNull { it.first == baseline } ?: continue
            val best = variants.filter { it.third == current.third }.maxByOrNull { it.second } ?: continue
            val increase = best.second / current.second
            if (increase > ratio && best.first != baseline) {
                ratio = increase
                upgrade = best.first
            }
        }
        return upgrade?.let { Choice(it, baseline) } ?: Choice(baseline)
    }
}
