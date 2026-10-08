package com.linfranca.ytdlpmobile

import android.util.Xml
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

internal data class CollectionCredentials(
    val e6User: String = "", val e6Key: String = "", val furbooruKey: String = "",
    val rule34User: String = "", val rule34Key: String = ""
)

internal data class CollectionItem(val id: String, val url: String, val extension: String,
    val order: Int = 0, val folder: String = "", val fallbackUrl: String = "")

internal data class CollectionRoute(val site: String, val title: String, val uri: URI, val parts: List<String>, val query: Map<String, String>) {
    companion object {
        fun from(link: String): CollectionRoute {
            val match = CollectionLinkRecognizer.recognize(link)
                ?: throw IllegalArgumentException("Paste a supported http:// or https:// website link.")
            // Input may use http; all supported source requests use HTTPS.
            val uri = URI(link.trim().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://"))
            val parts = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
            val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase(Locale.ROOT) to
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val title = when (match.site) {
                "E621", "E6AI", "E926" -> query["tags"] ?: parts.getOrNull(1) ?: ""
                "Furbooru" -> query["q"] ?: parts.getOrNull(1) ?: ""
                "Rule34" -> query["tags"] ?: query["id"] ?: ""
                "Luscious" -> (if (parts.firstOrNull() == "pictures") parts.getOrNull(2)
                    else parts.getOrNull(1)).orEmpty()
                "Yiffer" -> if (parts.firstOrNull() == "comics") parts.getOrNull(1).orEmpty() else parts.firstOrNull().orEmpty()
                "Tailspace" -> if (parts.firstOrNull() == "c")
                    java.net.URLDecoder.decode(parts.getOrNull(1).orEmpty(), "UTF-8")
                    else "${parts.getOrNull(1).orEmpty()}-${parts.getOrNull(3).orEmpty()}"
                else -> parts.getOrNull(1).orEmpty()
            }
            if (title.isBlank() || match.pageType == "Site page")
                throw IllegalArgumentException("Paste a search, post, comic, gallery, or album link rather than the site home page.")
            if (match.site == "Multporn" && match.pageType == "Video page")
                throw IllegalArgumentException("This site's video pages are not available through its comic image list.")
            return CollectionRoute(match.site, title, uri, parts, query)
        }
    }
}

/** Discover a page at a time. No list of an entire large search is kept in memory. */
internal class CollectionSources(
    private val credentials: CollectionCredentials,
    private val cancelled: () -> Boolean,
    private val onConnection: (HttpURLConnection?) -> Unit,
    private val onEvent: (String) -> Unit = {},
    private val onBatch: (Int, List<CollectionItem>) -> Unit
) {
    private val whitespacePattern = Regex("""\s+""")
    private val declaredPageCount = Regex("""numberOfPages[^0-9]{0,10}(\d{1,4})""")
    private val pageLabelPattern = Regex("""(?:^|,\s*)page\s+(\d{1,4})\s*$""", RegexOption.IGNORE_CASE)
    private val pictureCountPattern = Regex("""(?:(\d+)\s+gifs?\s*/\s*)?(\d{1,5})\s+pictures?""", RegexOption.IGNORE_CASE)
    private val readerIndexPattern = Regex("""(?:^|&)index=(\d+)""")
    private val readerReplacePattern = Regex("""([?&]index=)\d+""")
    private val thumbnailSizePattern = Regex("""\.\d+x\d+(?=\.[a-zA-Z0-9]+(?:\?|$))""")
    private val ogImagePattern = Regex("""<meta\b[^>]*\bproperty=["']og:image["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val sourceTagPattern = Regex("""<source\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val anchorTagPattern = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val imageTagPattern = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val userAgent = "yt-dlp Mobile/0.6.20 (Android; collection downloader)"

    fun discover(route: CollectionRoute) {
        when (route.site) {
            "E621", "E6AI", "E926" -> e6(route)
            "Furbooru" -> furbooru(route)
            "Rule34" -> rule34(route)
            "Multporn" -> multporn(route)
            "Yiffer" -> yiffer(route)
            "Tailspace" -> tailspace(route)
            "Luscious" -> luscious(route)
            else -> error("Unsupported collection source.")
        }
    }

    private fun e6(route: CollectionRoute) {
        val host = route.site.lowercase(Locale.ROOT) + ".net"
        val headers = if (credentials.e6User.isNotBlank() && credentials.e6Key.isNotBlank()) {
            val value = Base64.getEncoder().encodeToString(
                "${credentials.e6User}:${credentials.e6Key}".toByteArray(Charsets.UTF_8))
            mapOf("Authorization" to "Basic $value")
        } else emptyMap()
        val id = route.parts.takeIf { it.firstOrNull() == "posts" }?.getOrNull(1)
        if (id != null) {
            require(id.all(Char::isDigit)) { "Invalid post ID." }
            val post = JSONObject(get("https://$host/posts/$id.json", headers)).getJSONObject("post")
            onBatch(1, e6Items(JSONArray().put(post)))
            return
        }
        val tags = route.query["tags"]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("This search link has no tags.")
        paged(750) { page ->
            val response = JSONObject(get("https://$host/posts.json?tags=${enc(tags)}&limit=320&page=$page", headers))
            if (response.has("message")) throw IllegalStateException(response.optString("message"))
            val posts = response.getJSONArray("posts")
            PageResult(posts.length(), e6Items(posts))
        }
    }

    private fun e6Items(posts: JSONArray): List<CollectionItem> = buildList {
        for (i in 0 until posts.length()) {
            val post = posts.optJSONObject(i) ?: continue
            val file = post.optJSONObject("file") ?: continue
            val url = file.optString("url").takeIf { it.startsWith("https://") } ?: continue
            add(CollectionItem(post.optString("id"), url, file.optString("ext")))
        }
    }

    private fun furbooru(route: CollectionRoute) {
        val key = credentials.furbooruKey.takeIf(String::isNotBlank)?.let { "&key=${enc(it)}" }.orEmpty()
        val id = route.parts.takeIf { it.firstOrNull() == "images" }?.getOrNull(1)
        if (id != null) {
            require(id.all(Char::isDigit)) { "Invalid image ID." }
            val auth = key.removePrefix("&").takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
            val response = JSONObject(get("https://furbooru.org/api/v1/json/images/$id$auth"))
            onBatch(1, furbooruItems(JSONArray().put(response.getJSONObject("image"))))
            return
        }
        val query = (route.query["q"] ?: route.query["tags"]
            ?: route.parts.takeIf { it.firstOrNull() == "tags" }?.getOrNull(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8").replace('_', ' ') })?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("This search link has no query.")
        paged(5000) { page ->
            val response = JSONObject(get("https://furbooru.org/api/v1/json/search/images?q=${enc(query)}&page=$page&per_page=50$key"))
            val images = response.getJSONArray("images")
            PageResult(images.length(), furbooruItems(images))
        }
    }

    private fun furbooruItems(images: JSONArray): List<CollectionItem> = buildList {
        for (i in 0 until images.length()) {
            val image = images.optJSONObject(i) ?: continue
            if (image.optBoolean("hidden_from_users")) continue
            val url = image.optJSONObject("representations")?.optString("full")
                ?.takeIf { it.startsWith("https://") } ?: continue
            add(CollectionItem(image.optString("id"), url, image.optString("format")))
        }
    }

    private fun rule34(route: CollectionRoute) {
        val (userId, apiKey) = rule34Credentials()
        val auth = "&user_id=${enc(userId)}&api_key=${enc(apiKey)}"
        val id = route.query["id"]?.takeIf { route.query["s"] == "view" }
        if (id != null) {
            require(id.all(Char::isDigit)) { "Invalid post ID." }
            onBatch(1, rule34Items(rule34Response(get(
                "https://api.rule34.xxx/index.php?page=dapi&s=post&q=index&json=1&id=$id$auth"))))
            return
        }
        val tags = route.query["tags"]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("This search link has no tags.")
        // Rule34's pid is zero based. The desktop script began at one and missed the first page.
        paged(5000, firstPage = 0) { page ->
            val posts = rule34Response(get(
                "https://api.rule34.xxx/index.php?page=dapi&s=post&q=index&pid=$page&limit=1000&json=1&tags=${enc(tags)}$auth"))
            PageResult(posts.length(), rule34Items(posts))
        }
    }

    private fun rule34Credentials(): Pair<String, String> {
        // Account Options offers a ready-made fragment such as
        // &api_key=...&user_id=... . Accept it in the key field as well as
        // separate values, without ever printing the secret in diagnostics.
        val fields = HashMap<String, String>()
        for (input in listOf(credentials.rule34Key, credentials.rule34User)) {
            val fragment = input.substringAfter('?', input).trim().trimStart('&')
            for (part in fragment.split('&')) {
                val name = part.substringBefore('=')
                if (name == "api_key" || name == "user_id")
                    fields[name] = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                        .replace(whitespacePattern, "")
            }
        }
        val userId = (fields["user_id"] ?: credentials.rule34User.trim())
            .replace(whitespacePattern, "")
        val apiKey = (fields["api_key"] ?: credentials.rule34Key.trim())
            .replace(whitespacePattern, "")
        require(NativeMedia.asciiDigits(userId) && apiKey.isNotBlank() &&
            !apiKey.contains('&') && !apiKey.contains('=')) {
            "Rule34 needs a numeric user ID and API key. Paste the complete &api_key=…&user_id=… text into the API key field, or enter each value separately."
        }
        onEvent(if (fields.containsKey("api_key"))
            "Rule34 combined credentials parsed; sending user ID and API key (values hidden)."
            else "Rule34 separate credentials parsed; sending user ID and API key (values hidden).")
        return userId to apiKey
    }

    private fun rule34Response(body: String): JSONArray = try { JSONArray(body) }
        catch (_: org.json.JSONException) {
            throw IllegalStateException(if (body.contains("Missing authentication", true))
                "Rule34 says authentication is missing even though the app sent a user ID and API key. Check that API access is enabled in Account Options."
                else if (body.contains("authentication", true))
                "Rule34 did not accept the API credentials. Check your account's API Access Credentials."
                else "Rule34 returned an unexpected response instead of a post list.")
        }

    private fun rule34Items(posts: JSONArray): List<CollectionItem> = buildList {
        for (i in 0 until posts.length()) {
            val post = posts.optJSONObject(i) ?: continue
            val url = post.optString("file_url").takeIf { it.startsWith("https://") } ?: continue
            add(CollectionItem(post.optString("id"), url, extension(url)))
        }
    }

    private fun multporn(route: CollectionRoute) {
        if (route.parts.firstOrNull() in setOf("category", "characters", "category_hentai", "characters_hentai")) {
            multpornListing(route)
            return
        }
        val type = when (route.parts.firstOrNull()) {
            "comics", "hentai_manga", "gay_porn_comics", "gif", "humor" -> "field_com_pages"
            "pictures", "hentai" -> "field_img"
            "rule_63" -> "field_rule_63_img"
            "games" -> "field_screenshots"
            else -> throw IllegalArgumentException("This Multporn page type has no known image list.")
        }
        val (page, headerLink) = requestRetried(route.uri.toString(), null, emptyMap())
        val resolution = MultpornNodeId.find(headerLink, page)
        resolution.diagnostics.forEach(onEvent)
        val nodeId = resolution.id ?: throw IllegalStateException(
            "The comic page did not expose a node ID. It may be a verification page, or the site changed its page metadata.")
        onEvent("Multporn node ID found in ${resolution.source}; loading comic pages.")
        val xml = get("https://multporn.net/juicebox/xml/field/node/$nodeId/$type/full")
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val files = mutableListOf<CollectionItem>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "image") {
                parser.getAttributeValue(null, "linkURL")?.let { raw ->
                    val url = URL(URL("https://multporn.net/"), raw).toString()
                    files += CollectionItem(files.size.plus(1).toString(), url, extension(url), files.size + 1)
                }
            }
            event = parser.next()
        }
        if (files.isEmpty()) throw IllegalStateException("The gallery returned no images.")
        onBatch(1, files)
    }

    private fun multpornListing(route: CollectionRoute) {
        val seen = HashSet<String>()
        var listingPage = 0
        var emptyPages = 0
        while (listingPage < 500 && !cancelled()) {
            val pageUrl = if (listingPage == 0) route.uri.toString() else
                "${route.uri.scheme}://${route.uri.host}${route.uri.path}?page=0,$listingPage"
            val html = get(pageUrl)
            val links = anchorLinks(html, pageUrl)
                .filter { link ->
                    val target = URI(link)
                    val parts = target.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
                    target.host?.let { it == "multporn.net" || it == "www.multporn.net" } == true &&
                        parts.size >= 2 && parts.first() in
                        setOf("comics", "hentai_manga", "gay_porn_comics", "gif", "humor", "pictures", "hentai", "rule_63", "games")
                }
                .distinct().filter(seen::add)
            onEvent("Listing page ${listingPage + 1}: ${links.size} new comics.")
            if (links.isEmpty()) {
                if (++emptyPages >= 2) break
            } else emptyPages = 0
            for ((index, link) in links.withIndex()) {
                if (cancelled()) return
                val comic = runCatching { CollectionRoute.from(link) }.getOrNull() ?: continue
                onEvent("Comic ${seen.size - links.size + index + 1}: ${comic.title}")
                // Reuse the direct comic/gallery extractor, keeping its own page order.
                val nested = CollectionSources(credentials, cancelled, onConnection, onEvent) { _, files ->
                    onBatch(seen.size - links.size + index + 1,
                        files.map { it.copy(folder = comic.title) })
                }
                try { nested.multporn(comic) }
                catch (error: InterruptedException) { throw error }
                catch (error: Exception) { onEvent("Comic ${comic.title} failed: ${error.message}") }
            }
            listingPage++
        }
        if (listingPage == 500) onEvent("Listing reached the 500-page safety limit.")
    }

    private fun tailspace(route: CollectionRoute) {
        val page = get(route.uri.toString())
        if (route.parts.firstOrNull() == "artist") {
            val images = imageTags(page).mapNotNull { tag ->
                val url = attribute(tag, "src") ?: return@mapNotNull null
                url.takeIf { it.startsWith("https://pics.tailspace.com/post-media/") }
            }.distinct().toList()
            if (images.isEmpty()) throw IllegalStateException("No post media found on this Tailspace page.")
            onBatch(1, images.mapIndexed { index, url ->
                CollectionItem("post-${route.parts.last()}-${index + 1}", url, extension(url), index + 1)
            })
            return
        }
        // Comic pages expose a numbered image alt and an adjacent media URL.
        val base = route.uri.toString().substringBefore('?')
        val first = tailspacePages(page).ifEmpty {
            onEvent("Comic page images were not embedded in the overview; loading page 1.")
            tailspacePages(get("$base?page=1"))
        }
        if (first.isEmpty()) throw IllegalStateException("No comic pages found on this Tailspace page.")
        // The rendered description may contain comments such as "2 pages added".
        // Read the comic's page count from its embedded data instead.
        val declaredCount = declaredPageCount
            .find(page)?.groupValues?.get(1)?.toIntOrNull()
        val count = maxOf(declaredCount ?: 0, first.keys.max())
        require(count in 1..5000) { "Comic page count is outside the supported range." }
        val urls = first.toMutableMap()
        for (number in 1..count) {
            if (cancelled()) return
            if (number !in urls) {
                urls.putAll(tailspacePages(get("$base?page=$number")))
            }
            val url = urls[number] ?: throw IllegalStateException("Comic page $number of $count was not available.")
            onBatch(number, listOf(CollectionItem(number.toString(), url, extension(url), number)))
        }
    }

    private fun tailspacePages(html: String): Map<Int, String> = buildMap {
        for (tag in imageTags(html)) {
            val page = pageLabelPattern
                .find(attribute(tag, "alt").orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val src = attribute(tag, "src") ?: continue
            if (src.startsWith("https://pics.tailspace.com/comics/")) put(page, src)
        }
    }

    private fun yiffer(route: CollectionRoute) {
        val slug = route.title
        val info = JSONObject(get("https://yiffer.xyz/api/comics/${pathEnc(slug)}"))
        val count = info.optInt("numberOfPages")
        if (count !in 1..5000) throw IllegalStateException("The comic has no valid page count.")
        for (start in 1..count step 30) {
            if (cancelled()) return
            val entries = (start..minOf(start + 29, count)).map { number ->
                CollectionItem(number.toString(), "https://static.yiffer.xyz/comics/${pathEnc(slug)}/${number.toString().padStart(3, '0')}.jpg", "jpg", number)
            }
            onBatch((start - 1) / 30 + 1, entries)
        }
    }

    private fun luscious(route: CollectionRoute) {
        val albumPart = if (route.parts.firstOrNull() == "pictures") route.parts.getOrNull(2)
            else route.parts.getOrNull(1)
        val albumId = albumPart?.substringAfterLast('_')?.takeIf { it.all(Char::isDigit) }
            ?: throw IllegalArgumentException("Album ID not found in the link.")
        if (route.parts.firstOrNull() == "pictures") {
            val html = get(route.uri.toString())
            val image = lusciousImage(html, albumId)
                ?: throw IllegalStateException("This picture page has no usable image address.")
            val pictureId = route.parts.getOrNull(4)?.takeIf { it.all(Char::isDigit) }
                ?: throw IllegalArgumentException("Picture ID not found in the link.")
            onBatch(1, listOf(lusciousItem(pictureId, image, 1)))
            return
        }
        try {
            if (lusciousApi(albumId)) return
            onEvent("Album API returned no original images; trying the website reader.")
        } catch (error: InterruptedException) { throw error }
        catch (error: Exception) {
            onEvent("Album API unavailable: ${error.message}; trying the website reader.")
        }
        val base = "https://www.luscious.net/albums/${pathEnc(albumPart)}/"
        if (route.parts.getOrNull(2) == "read")
            onEvent("Reader link recognized; collecting the complete album from its canonical page.")
        val html = get(base)
        val countMatch = pictureCountPattern
            .find(html)
        val count = countMatch?.let {
            (it.groupValues[1].toIntOrNull() ?: 0) + (it.groupValues[2].toIntOrNull() ?: 0)
        }
        // The album HTML normally renders a portion of a large album. Its reader
        // accepts a numeric index; request each index instead of stopping at the
        // first 45 thumbnails or trusting an API response with null media fields.
        val readerLinks = anchorLinks(html, base).filter {
            URI(it).path.contains("/albums/") && URI(it).path.contains("/read/") &&
                it.contains("index=")
        }.distinct()
        if (readerLinks.isEmpty())
            onEvent("The album overview has no reader links; trying indexed reader pages directly.")
        val indices = readerLinks.mapNotNull { URI(it).rawQuery?.let { q ->
            readerIndexPattern.find(q)?.groupValues?.get(1)?.toIntOrNull()
        } }.distinct().sorted()
        // Without reader links, the first "pictures" count may belong to a
        // recommended album card rather than this album.
        val expected = count?.takeIf { readerLinks.isNotEmpty() && it in 1..5000 }
        onEvent("Album $albumId: ${expected?.toString() ?: "unknown"} pictures reported; ${indices.size} reader links initially visible.")
        val readerTemplate = if (route.parts.getOrNull(2) == "read" && route.query.containsKey("index"))
            route.uri.toString() else readerLinks.firstOrNull()
                ?: "$base" + "read/?index=0&view=slideshow&sorting=date_newest"
        var misses = 0
        val seenMedia = HashSet<String>()
        for (index in 0 until (expected ?: 5000)) {
            if (cancelled()) return
            val reader = readerLinks.firstOrNull { Regex("""[?&]index=$index(?:&|$)""").containsMatchIn(it) }
                ?: readerTemplate.replace(readerReplacePattern) { match ->
                    match.groupValues[1] + index
                }
            try {
                val image = lusciousImage(get(reader), albumId)
                    ?: throw IllegalStateException("Reader index $index had no usable media.")
                if (!seenMedia.add(image))
                    throw IllegalStateException("Reader index $index repeated an earlier image.")
                onBatch(index + 1, listOf(lusciousItem("$albumId-$index", image, index + 1)))
                misses = 0
            } catch (error: InterruptedException) { throw error }
            catch (error: Exception) {
                onEvent("Reader index $index failed: ${error.message}")
                if (++misses >= 3) {
                    if (expected == null && seenMedia.isNotEmpty()) {
                        onEvent("Reader ended after ${seenMedia.size} images; no album page count was available.")
                        break
                    }
                    throw IllegalStateException("Album reader stopped at index $index; remaining images were not saved.")
                }
            }
        }
    }

    private fun lusciousApi(albumId: String): Boolean {
        // The desktop downloader uses PictureListInsideAlbum, which lists
        // original media independently of the reader links rendered in HTML.
        val query = """query PictureListInsideAlbum(${ '$' }input: PictureListInput!) {
            picture { list(input: ${ '$' }input) {
                info { page has_next_page total_pages total_items }
                items { id url_to_original url thumbnails { width height url } }
            } }
        }"""
        var delivered = 0
        for (page in 1..100) {
            if (cancelled()) return true
            val input = JSONObject().put("filters", JSONArray().put(
                JSONObject().put("name", "album_id").put("value", albumId)))
                .put("display", "position").put("items_per_page", 50).put("page", page)
            val request = JSONObject().put("id", "6")
                .put("operationName", "PictureListInsideAlbum")
                .put("query", query)
                .put("variables", JSONObject().put("input", input))
            val response = JSONObject(post(
                "https://members.luscious.net/graphql/nobatch/?operationName=PictureListInsideAlbum",
                request.toString()))
            val list = response.optJSONObject("data")?.optJSONObject("picture")?.optJSONObject("list")
                ?: throw IllegalStateException(response.optJSONArray("errors")?.optJSONObject(0)
                    ?.optString("message")?.takeIf(String::isNotBlank) ?: "No album picture list in API response")
            val items = list.optJSONArray("items") ?: JSONArray()
            val found = buildList {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val thumbnails = item.optJSONArray("thumbnails")
                    val thumbnail = (0 until (thumbnails?.length() ?: 0))
                        .mapNotNull { thumbnails?.optJSONObject(it) }
                        .maxByOrNull { it.optInt("width") * it.optInt("height") }
                        ?.optString("url")
                    val url = sequenceOf(item.optString("url_to_original"), item.optString("url"), thumbnail)
                        .filterNotNull().firstOrNull { lusciousAlbumMedia(it, albumId) }
                        ?: continue
                    val order = (page - 1) * 50 + i + 1
                    add(CollectionItem(item.optString("id").ifBlank { "$albumId-$order" },
                        url, extension(url), order))
                }
            }
            onEvent("Album API page $page: ${found.size} matching images, ${items.length()} entries.")
            if (items.length() > 0 && found.isEmpty() && delivered == 0) return false
            if (found.isNotEmpty()) {
                onBatch(page, found)
                delivered += found.size
            }
            val info = list.optJSONObject("info")
            val lastPage = info?.optInt("total_pages")?.takeIf { it > 0 }
            if (lastPage != null && page >= lastPage ||
                info?.let { it.has("has_next_page") && !it.optBoolean("has_next_page") } == true ||
                items.length() == 0)
                break
        }
        if (delivered > 0) onEvent("Album API provided $delivered images.")
        return delivered > 0
    }

    private fun lusciousItem(id: String, image: String, order: Int): CollectionItem {
        val original = image.replace(thumbnailSizePattern, "")
        return CollectionItem(id, original, extension(original), order,
            fallbackUrl = if (original != image) image else "")
    }

    private fun lusciousAlbumMedia(url: String, albumId: String): Boolean =
        url.startsWith("https://ah-img.luscious.net/") &&
            runCatching { URI(url).path.orEmpty().trim('/').split('/').getOrNull(1) == albumId }
                .getOrDefault(false)

    private fun lusciousImage(html: String, albumId: String): String? {
        // Reader pages also show filmstrip thumbnails and recommended albums.
        // The displayed image has a numeric picture ID in its alt attribute.
        val main = imageTags(html).firstOrNull { tag ->
            attribute(tag, "alt")?.all(Char::isDigit) == true &&
                attribute(tag, "src")?.let { lusciousAlbumMedia(it, albumId) } == true
        }?.let { attribute(it, "src") }
        if (main != null) return main
        val og = ogImagePattern
            .find(html)?.value?.let { attribute(it, "content") }
        if (og?.let { lusciousAlbumMedia(it, albumId) } == true) return og
        val sources = sourceTagPattern.findAll(html)
        for (source in sources) {
            val url = attribute(source.value, "srcset")?.split(',')?.lastOrNull()
                ?.trim()?.substringBefore(' ')
            if (url?.let { lusciousAlbumMedia(it, albumId) } == true) return url
        }
        return null
    }

    private fun anchorLinks(html: String, base: String): List<String> {
        val tags = anchorTagPattern.findAll(html).map { it.value }.toList().toTypedArray()
        return NativeMedia.htmlAttributes(tags, "href").mapNotNull { it }.mapNotNull { raw ->
            runCatching { URI(base).resolve(raw.replace("&amp;", "&")).toString() }
                .getOrNull()?.takeIf { it.startsWith("https://") }
        }
    }

    private fun imageTags(html: String): Sequence<String> =
        imageTagPattern.findAll(html).map { it.value }

    private fun attribute(tag: String, name: String): String? =
        NativeMedia.htmlAttribute(tag, name)?.replace("&amp;", "&")

    private data class PageResult(val count: Int, val items: List<CollectionItem>)

    private fun paged(maxPages: Int, firstPage: Int = 1, load: (Int) -> PageResult) {
        var previousPage = emptySet<String>()
        for (page in firstPage until firstPage + maxPages) {
            if (cancelled()) return
            val result = load(page)
            if (result.count == 0) return
            val unique = result.items.distinctBy { it.id }
            if (unique.isEmpty()) {
                Thread.sleep(1000)
                continue
            }
            val pageIds = unique.mapTo(HashSet()) { it.id }
            if (pageIds == previousPage) return
            onBatch(page + 1 - firstPage, unique)
            previousPage = pageIds
            Thread.sleep(1000)
        }
        throw IllegalStateException("The source reached its page limit; narrow the search.")
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()) =
        requestRetried(url, null, headers).first
    private fun post(url: String, body: String) = requestRetried(url, body, emptyMap()).first

    private class HttpResponseError(val code: Int, val retryAfter: Int) :
        IllegalStateException("The website returned HTTP $code." +
            if (code == 401 || code == 403) " Check the account/API key or site access." else "")

    private fun requestRetried(url: String, body: String?, headers: Map<String, String>): Pair<String, String?> {
        repeat(3) { attempt ->
            try { return request(url, body, headers) }
            catch (error: HttpResponseError) {
                if (attempt == 2 || (error.code != 429 && error.code !in 500..599)) throw error
                if (cancelled()) throw InterruptedException("Collection download stopped.")
                Thread.sleep(error.retryAfter.takeIf { it in 1..60 }?.times(1000L)
                    ?: (attempt + 1) * 2000L)
            }
        }
        error("Request retry limit reached.")
    }

    private fun request(url: String, body: String?, headers: Map<String, String>): Pair<String, String?> {
        if (cancelled()) throw InterruptedException("Collection download stopped.")
        val connection = URL(url).openConnection() as HttpURLConnection
        onConnection(connection)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json, application/xml, text/html, */*")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw HttpResponseError(code, connection.getHeaderField("Retry-After")?.toIntOrNull() ?: 0)
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16_384)
                while (true) {
                    if (cancelled()) throw InterruptedException("Collection download stopped.")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8_000_000) throw IllegalStateException("The website response is too large.")
                    output.write(buffer, 0, count)
                }
            }
            // A server can send several Link fields (canonical, shortlink, etc.).
            // getHeaderField("Link") exposes only one of them on some connections.
            val links = connection.headerFields.entries
                .filter { (key, _) -> key.equals("Link", ignoreCase = true) }
                .flatMap { (_, values) -> values.orEmpty() }
                .joinToString(", ").takeIf(String::isNotBlank)
            return output.toString("UTF-8") to links
        } finally {
            connection.disconnect()
            onConnection(null)
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun pathEnc(value: String) = enc(value).replace("+", "%20")
    private fun extension(url: String): String {
        val lastPart = URL(url).path.substringAfterLast('/')
        return lastPart.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
    }
}
