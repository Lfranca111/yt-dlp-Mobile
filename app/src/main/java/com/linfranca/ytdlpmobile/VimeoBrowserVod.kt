package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** A download plan for the finite video and audio the Vimeo player actually used. */
internal object VimeoBrowserVod {
    data class Observed(val url: String, val frame: String)
    data class Manifest(val url: String, val body: String)
    data class Selection(val root: String, val info: JSONObject)
    private data class Track(val url: String, val playlist: HlsDownloadPlan.Playlist)
    private data class TrackAddress(val asset: String, val kind: String)

    private val trackPath = Regex("^(.+)/(video|audio)/[^/]+(?:/[^/]+)*$")
    private val assetId = Regex("(?i)(?:^|/)(?:[0-9a-f]{16,}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|[0-9]{6,})(?:/|$)")
    private fun hostMatches(host: String, domain: String) = host == domain || host.endsWith(".$domain")
    private fun https(url: String): URI? = runCatching { URI(url) }.getOrNull()
        ?.takeIf { it.scheme == "https" && it.userInfo == null && it.host != null && it.port in listOf(-1, 443) }
    fun page(url: String) = https(url)?.host?.lowercase()?.let { hostMatches(it, "vimeo.com") } == true
    fun media(url: String) = https(url)?.host?.lowercase()?.let {
        hostMatches(it, "vimeocdn.com") || hostMatches(it, "vimeo.com")
    } == true
    private fun trackAddress(url: String): TrackAddress? {
        if (!media(url)) return null
        val uri = URI(url)
        val match = trackPath.matchEntire(uri.rawPath.orEmpty()) ?: return null
        val prefix = match.groupValues[1]
        if (!assetId.containsMatchIn(prefix)) return null
        return TrackAddress(uri.host.lowercase() + prefix, match.groupValues[2])
    }

    fun select(
        page: String, title: String, observations: List<Observed>, playerFrame: String, seconds: Double,
        read: (String) -> Manifest, headers: (String) -> Map<String, String>
    ): Selection? {
        if (!page(page) || playerFrame.isBlank() || !seconds.isFinite() || seconds <= 0) return null
        val urls = observations.filter { it.frame == playerFrame && media(it.url) }.map { it.url }.distinct().take(8)
        val parsed = mutableMapOf<String, Track?>()
        fun load(url: String): Track? {
            if (!media(url) || parsed.size >= 16 && url !in parsed) return null
            if (url in parsed) return parsed[url]
            val track = attempt {
                val m = read(url)
                check(media(m.url))
                Track(m.url, HlsDownloadPlan.parse(m.url, m.body))
            }
            parsed[url] = track
            return track
        }
        fun complete(track: Track?) = track?.takeIf {
            val p = it.playlist
            p.variants.isEmpty() && p.audio.isEmpty() && p.ended && !p.encrypted && p.segments > 0 &&
                p.duration.isFinite() && abs(p.duration - seconds) <= maxOf(2.0, seconds * .02) && media(p.firstSegment)
        }
        fun pair(root: String, video: Track?, audio: Track?, attributes: Map<String, String> = emptyMap()): Selection? {
            if (video == null || audio == null || video.url == audio.url ||
                abs(video.playlist.duration - audio.playlist.duration) > 2.0) return null
            fun format(track: Track, sound: Boolean) = JSONObject()
                .put("format_id", if (sound) "audio-0" else "video-0")
                .put("url", track.url).put("protocol", "m3u8_native").put("ext", if (sound) "m4a" else "mp4")
                .put("vcodec", if (sound) "none" else "unknown").put("acodec", if (sound) "unknown" else "none")
                .put("http_headers", JSONObject(headers(track.url)))
                .put("_app_first_segment_url", track.playlist.firstSegment)
                .put("_app_segment_count", track.playlist.segments).put("_app_duration", track.playlist.duration)
            val picture = format(video, false)
            attributes["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()?.let { picture.put("height", it) }
            val info = JSONObject().put("id", "verified-vimeo-hls")
                .put("title", title.ifBlank { "Vimeo video" }).put("webpage_url", page)
                .put("formats", JSONArray().put(picture).put(format(audio, true)))
                .put("is_live", false).put("duration", video.playlist.duration)
                .put("_app_expected_duration", video.playlist.duration)
                .put("_app_expected_segments", maxOf(video.playlist.segments, audio.playlist.segments))
                .put("_app_vimeo_browser_pair", true)
            return Selection(root, info)
        }
        // Master manifests provide the strongest evidence: use the audio group
        // declared for the specific video rendition, preferring its default.
        for (root in urls) {
            val master = load(root)?.playlist?.takeUnless { it.encrypted } ?: continue
            val variants = master.variants.sortedByDescending { it.attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L }
            for (variant in variants.take(4)) {
                val group = variant.attrs["AUDIO"] ?: continue
                val renditions = master.audio.filter { it["GROUP-ID"] == group }
                val audio = (renditions.singleOrNull() ?: renditions.singleOrNull { it["DEFAULT"] == "YES" })
                    ?.get("URI") ?: continue
                pair(root, complete(load(variant.url)), complete(load(audio)), variant.attrs)?.let { return it }
            }
        }
        // A player may request the tracks separately. Require the same active
        // frame and Vimeo asset path; never pair tracks by CDN host alone.
        for (root in urls) {
            val video = load(root)?.let { track -> complete(track)?.takeIf { trackAddress(track.url)?.kind == "video" } } ?: continue
            val asset = trackAddress(video.url)?.asset ?: continue
            val audio = urls.mapNotNull { url ->
                load(url)?.let { track ->
                    val address = trackAddress(track.url)
                    if (address?.asset == asset && address.kind == "audio") complete(track) else null
                }
            }.distinctBy { URI(it.url).rawPath }.singleOrNull() ?: continue
            pair(root, video, audio)?.let { return it }
        }
        return null
    }

    private inline fun <T> attempt(action: () -> T): T? = try { action() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
}
