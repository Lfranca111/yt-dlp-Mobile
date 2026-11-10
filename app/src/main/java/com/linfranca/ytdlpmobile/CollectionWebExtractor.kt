package com.linfranca.ytdlpmobile

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.ArrayDeque

/** Bounded, site-specific listing/chapter traversal. Never treats all page images as comic pages. */
internal class CollectionWebExtractor(
    private val cancelled: () -> Boolean,
    private val get: (String) -> String,
    private val log: (String) -> Unit,
    private val batch: (Int, List<CollectionItem>) -> Unit
) {
    private data class Pending(val url: String, val folder: String, val depth: Int)
    fun discover(route: CollectionRoute) {
        val pending = ArrayDeque<Pending>()
        val visited = HashSet<String>()
        val mediaSeen = HashSet<String>()
        val folderCounts = HashMap<String, Int>()
        pending.add(Pending(route.uri.toString(), "", 0))
        var pages = 0
        var count = 0
        var failures = 0
        while (pending.isNotEmpty() && !cancelled()) {
            if (pages >= 1500) throw IllegalStateException("Collection reached the 1,500-page limit. Narrow the source link; saved files are kept.")
            val task = pending.removeFirst()
            if (!visited.add(task.url)) continue
            pages++
            try {
                val uri = URI(task.url)
                val type = CollectionSiteProfile.type(route.site, uri)
                val doc = Jsoup.parse(get(task.url), task.url)
                val title = doc.title().lowercase()
                check(!title.contains("site unavailable") && !title.contains("just a moment") &&
                    !title.contains("verify you") && !title.contains("access denied")) {
                    "The server returned a verification or access page, not collection content."
                }
                val leaf = CollectionSiteProfile.isLeaf(route.site, uri)
                var images = if (leaf) pageImages(route.site, doc) else emptyList()
                if (route.site in setOf("HentaiFox", "ComicPorn") && leaf && images.isEmpty()) images = galleryRegistry(doc)
                if (route.site == "nHentai" && leaf && images.isEmpty() && uri.path.trim('/').split('/').size == 2) images = runCatching { nhentai(uri) }.onFailure {
                    log("nHentai gallery metadata unavailable; trying the exposed reader links.")
                }.getOrDefault(emptyList())
                val eightData = if (route.site == "8Muses") eightData(doc) else null
                if (eightData != null) {
                    val pictures = eightData.optJSONArray("pictures")
                    images = (0 until (pictures?.length() ?: 0)).mapNotNull { i ->
                        pictures?.optJSONObject(i)?.optString("publicUri")?.takeIf { it.matches(Regex("[A-Za-z0-9._/-]+")) }
                            ?.let { "https://comics.8muses.com/image/fl/$it" }
                    }
                }
                // Hentai2Read's legacy reader exposes a chapter-only ordered image array.
                if (route.site == "Hentai2Read" && leaf && images.isEmpty()) {
                    val data = Regex("""["']images["']\s*:\s*(\[[^\]]+\])""").find(doc.html())?.groupValues?.get(1)
                    if (data != null) images = runCatching {
                        val a = JSONArray(data)
                        (0 until a.length()).mapNotNull { i ->
                            a.optString(i).takeIf(String::isNotBlank)?.let { raw ->
                                val absolute = if (raw.startsWith("http")) raw else "https://hentaicdn.com/hentai" + raw
                                absolute.takeIf(::isImage)
                            }
                        }
                    }.getOrDefault(emptyList())
                }
                if (images.isNotEmpty()) {
                    val offset = folderCounts[task.folder] ?: 0
                    val items = images.distinct().filter { mediaSeen.add(task.folder + "|" + it) }.mapIndexed { i, url ->
                        val id = URI(url).path.substringAfterLast('/').substringBeforeLast('.').take(80).ifBlank { "page-${i+1}" }
                        CollectionItem(id, url, if (route.site == "8Muses") "jpg" else URI(url).path.substringAfterLast('.').lowercase(), offset + i + 1,
                            task.folder, referer = task.url)
                    }
                    if (items.isNotEmpty()) { count += items.size; folderCounts[task.folder] = offset + items.size; batch(pages, items) }
                    log("${route.site}: $type page $pages, ${items.size} ordered original images.")
                }
                if (route.site == "Honeytoon" && doc.select(".comic-list__item--lock").isNotEmpty())
                    log("Honeytoon: locked episodes excluded; only readable pages exposed to this session are collected.")
                val children = childLinks(route.site, doc, uri, leaf && images.isNotEmpty()).toMutableList()
                if (eightData != null) {
                    val albums = eightData.optJSONArray("albums")
                    for (i in 0 until (albums?.length() ?: 0)) {
                        val item = albums?.optJSONObject(i) ?: continue
                        if (item.optBoolean("isPrivate")) { log("8Muses: private folder not requested."); continue }
                        val path = item.optString("permalink").takeIf { it.isNotBlank() && !it.contains("..") && !it.contains('?') } ?: continue
                        val prefix = if (uri.path.startsWith("/porn/")) "/porn/album/" else "/comics/album/"
                        children += "https://comics.8muses.com$prefix$path"
                    }
                    val page = eightData.optInt("page", 1)
                    if (page < eightData.optInt("pages", 1)) {
                        val base = uri.path.trimEnd('/').replace(Regex("/\\d+$"), "")
                        pending.addFirst(Pending("${uri.scheme}://${uri.host}$base/${page+1}" + (uri.rawQuery?.let { "?$it" } ?: ""), task.folder, task.depth))
                    }
                }
                val readers = readerLinks(route.site, doc, uri)
                for (reader in readers) if (reader !in visited) pending.addLast(Pending(reader, task.folder, task.depth))
                // Chapters are often displayed newest first; preserve explicit numeric order when available.
                val ordered = if (leaf) children.sortedWith(compareBy({ chapterOrder(doc, it) }, { children.indexOf(it) })) else children
                for (link in ordered) {
                    if (task.depth >= 12) { failures++; log("Skipped collection nested more than 12 folders."); continue }
                    val path = URI(link).path.trim('/').split('/')
                    val label = path.takeLast(2).joinToString("-")
                    val folder = if (task.folder.isBlank()) label else task.folder + "/" + path.last()
                    if (link !in visited) pending.add(Pending(link, folder, task.depth + 1))
                }
                nextPage(doc, uri)?.let { next ->
                    if (next !in visited) pending.addFirst(Pending(next, task.folder, task.depth))
                }
                if (leaf && images.isEmpty() && children.isEmpty() && readers.isEmpty()) {
                    failures++
                    log("${route.site}: no readable pages in $type page $pages; possible sign-in, locked episode, or changed layout. No cover/recommendation images substituted.")
                } else if (!leaf) log("${route.site}: listing page $pages, ${children.size} collection links.")
            } catch (error: InterruptedException) { throw error }
            catch (error: Exception) {
                failures++
                log("${route.site}: page $pages failed: ${safeError(error.message)}")
                if (pages == 1) throw IllegalStateException(safeError(error.message), error)
            }
        }
        if (count == 0 && !cancelled()) throw IllegalStateException("No downloadable comic pages found. The source may require sign-in, have locked content, or use an unsupported reader layout. See the collection log.")
        log("${route.site}: discovered $count files across $pages pages; failed or unavailable pages=$failures.")
        if (failures > 0 && !cancelled()) throw IllegalStateException("Partial collection: $failures pages unavailable; downloaded files were kept. See the log.")
    }

    private fun nhentai(uri: URI): List<String> {
        val id = uri.path.split('/').getOrNull(2)?.takeIf { it.all(Char::isDigit) } ?: return emptyList()
        log("nHentai: reader exposed no originals; trying gallery metadata.")
        val modern = runCatching { JSONObject(get("https://nhentai.net/api/v2/galleries/$id")) }.getOrNull()
        val pagesV2 = modern?.optJSONArray("pages") ?: modern?.optJSONObject("data")?.optJSONArray("pages")
        if (pagesV2 != null && pagesV2.length() > 0) {
            return (0 until pagesV2.length()).mapNotNull { i ->
                val path = pagesV2.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() && !it.contains("..") } ?: return@mapNotNull null
                val full = if (path.startsWith("https://")) path else "https://i.nhentai.net/" + path.trimStart('/')
                full.takeIf { isImage(it) && URI(it).host.orEmpty().let { h -> h == "i.nhentai.net" || h.matches(Regex("i[1-4]\\.nhentai\\.net")) } }
            }
        }
        val json = JSONObject(get("https://nhentai.net/api/gallery/$id"))
        val media = json.optString("media_id").takeIf { it.matches(Regex("\\d+")) } ?: return emptyList()
        val pages = json.optJSONObject("images")?.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).mapNotNull { i ->
            val ext = when (pages.optJSONObject(i)?.optString("t")) { "j" -> "jpg"; "p" -> "png"; "g" -> "gif"; "w" -> "webp"; else -> null }
            ext?.let { "https://i.nhentai.net/galleries/$media/${i+1}.$it" }
        }
    }

    companion object {
        private val junk = Regex("(?i)(?:^|[/_.-])(thumb(?:nail)?s?|cover|avatar|logo|banner|advert|placeholder)(?:[/_.-]|$)")
        fun isImage(url: String) = runCatching {
            val u = URI(url)
            u.scheme == "https" && u.userInfo == null && Regex("(?i)\\.(?:jpe?g|png|webp|gif|avif)$").containsMatchIn(u.path.orEmpty())
        }.getOrDefault(false)
        fun pageImages(site: String, doc: Document): List<String> {
            val selector = when (site) {
                "nHentai" -> "#image-container img, .reader-container img"
                "Doujins" -> ".doujin img[data-file], .doujin img[data-src], .doujin-image img, img.doujin, img.doujin-image, img[data-file]"
                "HentaiFox" -> "#image-container img, #gimg, .reading-content img, .reader-container img"
                "Hentai2Read" -> "#reader img, #reader-images img, .reader-images img, .reading-content img, img.chapter-img, img.js-page-image, img#image"
                "8Muses" -> ".picture-view img, #picture-view img, img.picture, .reader img"
                "AllPornComic" -> ".reading-content .page-break img, .reading-content img.wp-manga-chapter-img"
                "ILikeComix" -> ".entry-content .gallery img, .entry-content .wp-block-gallery img, .entry-content img.alignnone, .entry-content img.size-full"
                "MyHentaiGallery" -> "#image img, #main-image, img#gallery-image, .gallery-image img, img.gallery-image, .comic-thumb img"
                "Honeytoon" -> ".comic-reader img, .reader-content img, .episode-content img, .comic-page__images img, .comic-images img"
                "ComicPorn" -> "#image-container img, #gimg, .reading-content img, .gallery-image img"
                else -> return emptyList()
            }
            return doc.select(selector).filter { el ->
                !el.parents().any { parent -> Regex("(?i)(related|recommend|advert|sidebar)").containsMatchIn(parent.className()+" "+parent.id()) }
            }.mapNotNull { el ->
                val raw = sequenceOf("data-original", "data-file", "data-src", "data-lazy-src", "src")
                    .map { el.attr(it).trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") } ?: return@mapNotNull null
                runCatching { URI(doc.baseUri()).resolve(raw).toString() }.getOrNull()
                    ?.let { if (site == "MyHentaiGallery" && el.parents().any { p -> p.hasClass("comic-thumb") }) it.replace("/thumbnail/", "/original/") else it }
                    ?.takeIf { isImage(it) && !junk.containsMatchIn(URI(it).path) }
            }.distinct()
        }
        internal fun galleryRegistry(doc: Document): List<String> {
            // These readers publish a gallery-scoped page/format table. Only use
            // that table and the gallery thumbnail directory, never every image.
            val script = doc.select("script").joinToString("\n") { it.data() }
            val raw = Regex("""parseJSON\(\s*'([^']+)'\s*\)""").find(script)?.groupValues?.get(1)
                ?.replace("\\'", "'")?.replace("\\/", "/") ?: return emptyList()
            val table = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
            val first = doc.selectFirst(".inner_thumb img[data-src], .th_img img[data-src], .gallery_thumbs img[data-src]") ?: return emptyList()
            val thumb = first.absUrl("data-src")
            val base = thumb.substringBeforeLast('/', "") + "/"
            if (!base.startsWith("https://")) return emptyList()
            val pages = table.optJSONObject("fl") ?: table
            if (pages.length() !in 1..5000) return emptyList()
            return (1..pages.length()).mapNotNull { i ->
                val ext = when (pages.optString(i.toString()).substringBefore(',')) {
                    "j" -> "jpg"; "p" -> "png"; "g" -> "gif"; "w" -> "webp"; "a" -> "avif"; else -> return@mapNotNull null
                }
                "$base$i.$ext"
            }
        }
        private fun chapterOrder(doc: Document, url: String): Double {
            val text = doc.select("a[href]").firstOrNull { it.absUrl("href").substringBefore('#') == url }?.text().orEmpty()
            if (text.contains("prologue", true)) return -1.0
            val explicit = Regex("(?i)(?:chapter|episode|ch\\.)\\s*(\\d+(?:[.-]\\d+)?)").find(text)
            return explicit?.groupValues?.get(1)?.replace('-', '.')?.toDoubleOrNull() ?: chapterNumber(url)
        }
        internal fun eightData(doc: Document): JSONObject? {
            val raw = doc.selectFirst("script#ractive-public")?.data()?.trim(' ', '!', '\n', '\r', '\t') ?: return null
            val decoded = org.jsoup.parser.Parser.unescapeEntities(raw, false).map { c ->
                if (c.code in 33..126) (33 + (c.code - 33 + 47) % 94).toChar() else c
            }.joinToString("")
            return runCatching { JSONObject(decoded) }.getOrNull()
        }
        fun readerLinks(site: String, doc: Document, current: URI): List<String> {
            val p = current.path.trim('/').split('/')
            val prefix = when (site) {
                "nHentai" -> if (p.firstOrNull() == "g" && p.size >= 2) "/g/${p[1]}/" else return emptyList()
                "HentaiFox", "ComicPorn" -> if (p.firstOrNull() in setOf("gallery", "g", "view") && p.size >= 2) "/g/${p[1]}/" else return emptyList()
                "Hentai2Read" -> if (p.firstOrNull() == "reader" && p.size >= 3) "/reader/${p[1]}/${p[2]}/" else return emptyList()
                else -> return emptyList()
            }
            return doc.select("a[href]").mapNotNull {
                runCatching { URI(it.absUrl("href")) }.getOrNull()?.takeIf { u ->
                    u.host == current.host && u.scheme == "https" && u.path.startsWith(prefix) &&
                        u.path.removePrefix(prefix).trim('/').matches(Regex("(?:page-)?\\d+")) && u.path != current.path
                }?.toString()
            }.distinct().sortedBy { Regex("(\\d+)/?$").find(URI(it).path)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        }
        fun childLinks(site: String, doc: Document, current: URI, hasImages: Boolean): List<String> {
            if (hasImages) return emptyList()
            val currentPath = current.path.orEmpty().trimEnd('/')
            val currentType = CollectionSiteProfile.type(site, current)
            if (currentType in setOf("Gallery", "Chapter")) return emptyList()
            return doc.select("a[href]").filter { el ->
                // Exclude menus, promoted cards, comments, and related work on an individual comic.
                !el.parents().any { it.hasClass("comic-list__item--lock") } &&
                !el.parents().any { p -> p.tagName() in setOf("nav", "header", "footer") ||
                    Regex("(?i)(related|recommend|advert|comment|sidebar)").containsMatchIn(p.className() + " " + p.id()) }
            }.mapNotNull { a ->
                runCatching { URI(a.absUrl("href").substringBefore('#')) }.getOrNull()?.takeIf { u ->
                    u.scheme == "https" && u.userInfo == null && CollectionSiteProfile.site(u.host.orEmpty()) == site &&
                        (CollectionSiteProfile.isLeaf(site, u) || childListing(site, current, u)) && u.path.orEmpty().trimEnd('/') != currentPath &&
                        when {
                            currentType !in setOf("Comic", "Comic or folder") -> true
                            site == "Hentai2Read" -> u.path.startsWith("/reader/" + current.path.trim('/').substringAfterLast('/') + "/") ||
                                u.path.startsWith(currentPath + "/")
                            site == "8Muses" -> u.path.startsWith(currentPath + "/")
                            else -> u.path.startsWith(currentPath + "/")
                        }
                }?.toString()
            }.distinct()
        }
        private fun childListing(site: String, parent: URI, child: URI): Boolean {
            val p=parent.path.trim('/').split('/'); val c=child.path.trim('/').split('/')
            val f=p.firstOrNull(); val t=c.firstOrNull()
            if (p.size != 1 && !(site == "8Muses" && parent.path.trim('/') == "porn")) return false
            return when (site) {
                "nHentai", "HentaiFox", "ComicPorn" -> c.size == 2 && mapOf("tags" to "tag", "artists" to "artist",
                    "groups" to "group", "characters" to "character", "parodies" to "parody", "languages" to "language")[f] == t
                "Doujins" -> f in setOf("series", "tags", "artists") &&
                    (t == f && c.size == 2 || f == "series" && c.size == 1 && Regex("-\\d+$").containsMatchIn(t.orEmpty()))
                "Honeytoon" -> f == "genres" && t == "genres" && c.size == 2
                "Hentai2Read" -> f == "tags" && t in setOf("tag", "category", "content") && c.size >= 2
                "AllPornComic" -> f in setOf("genresa", "advanced-searcha") && t in setOf("porncomic-category", "porncomic-genre", "characters", "series", "porncomicartist") && c.size >= 2
                else -> false
            }
        }
        fun nextPage(doc: Document, current: URI): String? {
            val next = doc.select("a[rel=next], a.next, a.next-page, .pager-next a, .pagination a, .pager a")
                .firstOrNull { it.attr("rel") == "next" || it.hasClass("next") || it.hasClass("next-page") ||
                    it.parents().any { p -> p.hasClass("pager-next") } || it.text().trim().lowercase() in setOf("next", "next ›", "next »", "›", "»") }
                ?: return null
            val target = runCatching { URI(next.absUrl("href")) }.getOrNull() ?: return null
            if (target.scheme != "https" || target.host != current.host || target.userInfo != null) return null
            // Query pagination inherits the user's filters even if the site's next URL drops them.
            if (target.path == current.path) {
                val q = linkedMapOf<String, String>()
                for (raw in listOf(current.rawQuery, target.rawQuery)) raw.orEmpty().split('&').filter(String::isNotBlank).forEach {
                    q[it.substringBefore('=')] = it.substringAfter('=', "")
                }
                return "${target.scheme}://${target.rawAuthority}${target.rawPath}?" + q.entries.joinToString("&") { "${it.key}=${it.value}" }
            }
            val root = current.path.replace(Regex("/page/\\d+/?$"), "").trimEnd('/')
            return target.toString().takeIf { target.path.trimEnd('/').startsWith(root + "/page/") }
        }
        private fun chapterNumber(url: String): Double {
            val tail = URI(url).path.trim('/').substringAfterLast('/')
            if (tail == "prologue") return -1.0
            return Regex("(?:chapter-)?(\\d+(?:[.-]\\d+)?)").find(tail)?.groupValues?.get(1)?.replace('-', '.')?.toDoubleOrNull() ?: Double.MAX_VALUE
        }
        private fun safeError(message: String?) = message.orEmpty().replace(Regex("https?://\\S+"), "[address omitted]").take(350)
    }
}
