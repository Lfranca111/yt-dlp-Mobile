package com.linfranca.ytdlpmobile

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

/** Resolves only public video attachments on the exact submitted post. */
internal object SocialPostDownload {
    data class Route(val name: String, val endpoint: String, val postId: String, val actor: String = "")
    fun route(raw: String): Route? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return when (uri.host?.lowercase()) {
            "bsky.app" -> {
                val m = Regex("/profile/([A-Za-z0-9.:-]{1,253})/post/([A-Za-z0-9_-]{1,64})").matchEntire(path) ?: return null
                val at = "at://${m.groupValues[1]}/app.bsky.feed.post/${m.groupValues[2]}"
                Route("Bluesky", "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?depth=0&parentHeight=0&uri=" + URLEncoder.encode(at, "UTF-8"), m.groupValues[2], m.groupValues[1])
            }
            "instagram.com", "www.instagram.com" -> {
                val m = Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{1,64})").matchEntire(path) ?: return null
                Route("Instagram", "https://www.instagram.com$path/", m.groupValues[1])
            }
            "threads.com", "www.threads.com", "threads.net", "www.threads.net" ->
                pageRoute("Threads", uri, Regex("/@[A-Za-z0-9_.]+/post/([A-Za-z0-9_-]+)"))
            "facebook.com", "www.facebook.com", "m.facebook.com" ->
                pageRoute("Facebook", uri, Regex("/(?:reel|videos)/([0-9]+)"))
            "reddit.com", "www.reddit.com" ->
                pageRoute("Reddit", uri, Regex("/r/[A-Za-z0-9_]+/comments/([a-z0-9]+)(?:/[^/]+)?"))
            "snapchat.com", "www.snapchat.com" ->
                pageRoute("Snapchat", uri, Regex("/(?:@[A-Za-z0-9_.-]+/)?spotlight/([A-Za-z0-9_-]{1,256})"))
            "tumblr.com", "www.tumblr.com" ->
                pageRoute("Tumblr", uri, Regex("/[A-Za-z0-9_-]+/([0-9]+)(?:/[^/]+)?"))
            "tiktok.com", "www.tiktok.com" ->
                pageRoute("TikTok", uri, Regex("/@[A-Za-z0-9_.-]+/video/([0-9]+)"))
            "pinterest.com", "www.pinterest.com" ->
                pageRoute("Pinterest", uri, Regex("/pin/([0-9]+)"))
            "mastodon.social" -> {
                val m = Regex("/@[A-Za-z0-9_.@-]{1,253}/([0-9]{1,24})").matchEntire(path) ?: return null
                Route("Mastodon", "https://mastodon.social/api/v1/statuses/${m.groupValues[1]}", m.groupValues[1])
            }
            else -> null
        }
    }
    fun routeWithShare(raw: String, stopped: () -> Boolean): Route? {
        route(raw)?.let { return it }
        var current = runCatching { URI(raw) }.getOrNull() ?: return null
        val family = when (current.host?.lowercase()) {
            "www.tiktok.com", "tiktok.com" -> "TikTok".takeIf { current.path.orEmpty().matches(Regex("/t/[A-Za-z0-9_-]+/?")) }
            "vm.tiktok.com", "vt.tiktok.com" -> "TikTok"
            "www.reddit.com", "reddit.com" -> "Reddit".takeIf { current.path.orEmpty().matches(Regex("/r/[A-Za-z0-9_]+/s/[A-Za-z0-9_-]+/?")) }
            "redd.it" -> "Reddit"
            "www.facebook.com", "facebook.com", "m.facebook.com" -> "Facebook".takeIf { current.path.orEmpty().matches(Regex("/share/(?:r/|v/)?[A-Za-z0-9_-]+/?")) }
            "fb.watch" -> "Facebook"
            "pin.it" -> "Pinterest"
            "t.snapchat.com" -> "Snapchat".takeIf { current.path.orEmpty().matches(Regex("/[A-Za-z0-9_-]{1,128}/?")) }
            "www.snapchat.com", "snapchat.com" -> "Snapchat".takeIf {
                current.path.orEmpty().matches(Regex("/(?:t|s)/[A-Za-z0-9_-]{1,128}/?"))
            }
            else -> null
        } ?: return null
        val hosts = when (family) {
            "TikTok" -> setOf("www.tiktok.com", "tiktok.com", "vm.tiktok.com", "vt.tiktok.com")
            "Reddit" -> setOf("www.reddit.com", "reddit.com", "redd.it")
            "Facebook" -> setOf("www.facebook.com", "facebook.com", "m.facebook.com", "fb.watch")
            "Snapchat" -> setOf("www.snapchat.com", "snapchat.com", "t.snapchat.com")
            else -> setOf("www.pinterest.com", "pinterest.com", "pin.it")
        }
        val seen = HashSet<URI>()
        repeat(4) {
            if (stopped() || current.scheme != "https" || current.userInfo != null || current.port != -1 ||
                current.host?.lowercase() !in hosts || !seen.add(current)) return null
            route(current.toString())?.takeIf { it.name == family }?.let { return it }
            val conn = current.toURL().openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                if (conn.responseCode !in setOf(301, 302, 303, 307, 308)) return null
                val location = conn.getHeaderField("Location") ?: return null
                current = current.resolve(location)
            } finally { conn.disconnect() }
        }
        return null
    }

    private fun pageRoute(name: String, uri: URI, pattern: Regex): Route? {
        val path = uri.rawPath.orEmpty()
        val match = pattern.matchEntire(path.trimEnd('/')) ?: return null
        return Route(name, "https://${uri.host}$path", match.groupValues[1])
    }

    private val attributes = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private fun attrs(tag: String): Map<String, String> = attributes.findAll(tag).associate {
        it.groupValues[1].lowercase() to android.text.Html.fromHtml(
            it.groupValues[2].ifEmpty { it.groupValues[3] }, 0).toString()
    }

    private fun approved(url: String, domain: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        return url.takeIf { uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            (host == domain || host.endsWith(".$domain")) }
    }

    internal fun pageVideo(route: Route, page: String): String? {
        if (route.name == "Instagram") return instagramVideo(page, route.postId)
        if (route.name == "Reddit") {
            return Regex("""<shreddit-player\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(page)
                .map { attrs(it.value) }.filter { it["post-id"] == "t3_${route.postId}" }
                .mapNotNull { approved(it["src"].orEmpty(), "v.redd.it") }.distinct().toList().singleOrNull()
        }
        if (route.name == "Pinterest") {
            val relay = Regex("""window\.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__\("[^"]+",\s*(?=\{)""")
            return relay.findAll(page).mapNotNull { match ->
                val data = runCatching {
                    org.json.JSONTokener(page.substring(match.range.last + 1)).nextValue() as? JSONObject
                }.getOrNull()?.optJSONObject("data")?.optJSONObject("v3GetPinQueryv2")
                    ?.optJSONObject("data") ?: return@mapNotNull null
                if (data.optString("entityId") != route.postId) return@mapNotNull null
                val urls = data.optJSONObject("videos")?.optJSONArray("videoUrls") ?: return@mapNotNull null
                (0 until urls.length()).asSequence().mapNotNull { approved(urls.optString(it), "pinimg.com") }
                    .firstOrNull { URI(it).path.startsWith("/videos/iht/hls/") && URI(it).path.endsWith(".m3u8") }
            }.distinct().toList().singleOrNull()
        }
        if (route.name == "TikTok") {
            val script = Regex("""<script\b[^>]*id=["']__UNIVERSAL_DATA_FOR_REHYDRATION__["'][^>]*>(.*?)</script>""",
                RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1) ?: return null
            val scope = JSONObject(script).optJSONObject("__DEFAULT_SCOPE__") ?: return null
            val item = (scope.optJSONObject("webapp.video-detail") ?: scope.optJSONObject("webapp.reflow.video.detail"))
                ?.optJSONObject("itemInfo")?.optJSONObject("itemStruct") ?: return null
            if (item.optString("id") != route.postId) return null
            val video = item.optJSONObject("video") ?: return null
            return sequenceOf(video.optString("playAddr"), video.optString("downloadAddr"))
                .mapNotNull { approved(it, "tiktokcdn.com") ?: approved(it, "tiktokv.com") ?: approved(it, "us.tiktok.com") }.firstOrNull()
        }

        val head = page.substringBefore("</head>", "")
        val tags = Regex("""<(?:meta|link)\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(head)
            .map { attrs(it.value) }.toList()
        val identities = tags.mapNotNull {
            when {
                it["property"] == "og:url" -> it["content"]
                it["rel"] == "canonical" -> it["href"]
                else -> null
            }
        }
        if (identities.isEmpty() || identities.any {
                val resolved = this.route(it)
                resolved?.name != route.name || resolved?.postId != route.postId
            }) return null

        if (route.name == "Threads") return instagramVideo(page, route.postId)
        val cdn = when (route.name) {
            "Facebook" -> "fbcdn.net"
            "Snapchat" -> "sc-cdn.net"
            "Tumblr" -> "tumblr.com"
            "Pinterest" -> "pinimg.com"
            else -> return null
        }
        return tags.asSequence().filter { it["property"] in setOf("og:video:secure_url", "og:video:url", "og:video") }
            .mapNotNull { approved(it["content"].orEmpty(), cdn) }
            .filter { route.name != "Tumblr" || URI(it).host?.endsWith(".media.tumblr.com") == true ||
                URI(it).host == "video.tumblr.com" }
            .distinct().toList().singleOrNull()
    }

    fun media(route: Route, json: JSONObject): String? {
        val url = if (route.name == "Bluesky") {
            val post = json.optJSONObject("thread")?.optJSONObject("post") ?: return null
            if (!post.optString("uri").endsWith("/app.bsky.feed.post/${route.postId}")) return null
            val author = post.optJSONObject("author") ?: return null
            if (!route.actor.equals(author.optString("handle"), ignoreCase = true) &&
                route.actor != author.optString("did")) return null
            val outer = post.optJSONObject("embed") ?: return null
            val embed = if (outer.optString("\$type") == "app.bsky.embed.recordWithMedia#view")
                outer.optJSONObject("media") ?: return null else outer
            if (embed.optString("\$type") != "app.bsky.embed.video#view") return null
            embed.optString("playlist")
        } else {
            if (json.optString("id") != route.postId || json.optString("visibility") !in setOf("public", "unlisted")) return null
            val items = json.optJSONArray("media_attachments") ?: return null
            val videos = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .filter { it.optString("type") == "video" }
            videos.singleOrNull()?.optString("url") ?: return null
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
        val hosts = if (route.name == "Bluesky") setOf("video.bsky.app", "video.cdn.bsky.app") else setOf("files.mastodon.social")
        return url.takeIf { uri.host in hosts }
    }
    private fun instagramVideo(page: String, postId: String): String? {
        val scripts = Regex("""<script\b[^>]*\btype=["']application/json["'][^>]*>(.*?)</script\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        var best: String? = null
        var bestPixels = -1L
        fun visit(node: Any?, depth: Int) {
            if (depth > 64) return
            when (node) {
                is JSONObject -> {
                    if (node.optString("code") == postId && node.isNull("carousel_media")) {
                        val versions = node.optJSONArray("video_versions")
                        for (i in 0 until (versions?.length() ?: 0)) {
                            val entry = versions?.optJSONObject(i) ?: continue
                            val url = entry.optString("url")
                            val uri = runCatching { URI(url) }.getOrNull() ?: continue
                            val host = uri.host?.lowercase() ?: continue
                            if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
                                (!host.endsWith(".cdninstagram.com") && !host.endsWith(".fbcdn.net"))) continue
                            val pixels = entry.optInt("width").coerceAtLeast(0).toLong() *
                                entry.optInt("height").coerceAtLeast(0)
                            if (pixels > bestPixels) {
                                bestPixels = pixels
                                best = url
                            }
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) visit(node.opt(keys.next()), depth + 1)
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), depth + 1)
            }
        }
        for (script in scripts.findAll(page)) {
            val body = script.groupValues[1]
            if (!body.contains("video_versions") || !body.contains(postId)) continue
            runCatching { JSONObject(body) }.getOrNull()?.let { visit(it, 0) }
        }
        return best
    }

    fun resolve(route: Route, stopped: () -> Boolean): String? {
        if (stopped()) return null
        var endpoint=route.endpoint
        val seen=HashSet<String>()
        repeat(if(route.name=="Snapchat") 4 else 1) {
            if(stopped() || !seen.add(endpoint)) return null
            val conn = URI(endpoint).toURL().openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("Accept", if (route.name in setOf("Bluesky", "Mastodon")) "application/json" else "text/html")
                val status=conn.responseCode
                if(route.name=="Snapchat" && status in setOf(301,302,303,307,308)) {
                    val location=conn.getHeaderField("Location") ?: return null
                    val next=this.route(URI(endpoint).resolve(location).toString()) ?: return null
                    if(next.name!="Snapchat" || next.postId!=route.postId) return null
                    endpoint=next.endpoint
                    return@repeat
                }
                if(status != 200) return null
                val data = java.io.ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val bytes = ByteArray(8192)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                    while (true) {
                        if (stopped() || android.os.SystemClock.elapsedRealtime() > deadline) return null
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (data.size() + count > 2_000_000) return null
                        data.write(bytes, 0, count)
                    }
                }
                val body = data.toString("UTF-8")
                return if (route.name in setOf("Bluesky", "Mastodon")) media(route, JSONObject(body)) else pageVideo(route, body)
            } finally { conn.disconnect() }
        }
        return null
    }

    /** Check the actual file header before setting an explicit extension. Never edit the signed URL. */
    fun snapchatDownloadInfo(route: Route, media: String, userAgent: String, stopped: ()->Boolean,
                             log: (String)->Unit): String? {
        if(route.name!="Snapchat" || stopped() || approved(media,"sc-cdn.net")==null) return null
        val conn=URI(media).toURL().openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects=false
            conn.connectTimeout=4000
            conn.readTimeout=4000
            conn.setRequestProperty("Range","bytes=0-11")
            conn.setRequestProperty("Accept-Encoding","identity")
            conn.setRequestProperty("Referer",route.endpoint)
            if(userAgent.isNotBlank()) conn.setRequestProperty("User-Agent",userAgent)
            val status=conn.responseCode
            val mime=conn.contentType.orEmpty().substringBefore(';').trim().lowercase()
            val rangeOk=status==200 || status==206 && conn.getHeaderField("Content-Range").orEmpty().startsWith("bytes 0-")
            if(!rangeOk || mime !in setOf("video/mp4","application/mp4","application/octet-stream","")) {
                log("Snapchat media verification; HTTP=$status; MP4 response not verified; URL omitted")
                return null
            }
            val bytes=ByteArray(12)
            val read=conn.inputStream.use { input ->
                var n=0
                while(n<bytes.size && !stopped()) {
                    val count=input.read(bytes,n,bytes.size-n)
                    if(count<=0) break
                    n+=count
                }
                n
            }
            val size=java.nio.ByteBuffer.wrap(bytes).int
            if(stopped() || read!=12 || size !in 16..65536 ||
                !bytes.copyOfRange(4,8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))) {
                log("Snapchat media verification; HTTP=$status; MP4 file header not verified; URL omitted")
                return null
            }
            val headers=JSONObject().put("Referer",route.endpoint)
            if(userAgent.isNotBlank()) headers.put("User-Agent",userAgent)
            log("Snapchat media verification; HTTP=$status; MP4 file header verified; signed URL unchanged")
            return JSONObject().put("id",route.postId).put("title","Snapchat Spotlight ${route.postId}")
                .put("webpage_url",route.endpoint).put("url",media).put("ext","mp4")
                .put("protocol","https").put("format_id","spotlight-mp4").put("http_headers",headers)
                .put("formats",org.json.JSONArray().put(JSONObject().put("url",media).put("ext","mp4")
                    .put("protocol","https").put("format_id","spotlight-mp4").put("http_headers",headers))).toString()
        } finally { conn.disconnect() }
    }
}
