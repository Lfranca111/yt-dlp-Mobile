package com.linfranca.ytdlpmobile

import java.net.URI
import org.json.JSONObject

/** YouTube player evidence is accepted only for the exact requested live video. */
internal object YouTubeLivePolicy {
    private val id=Regex("[A-Za-z0-9_-]{11}")
    fun videoId(page: String): String? = runCatching {
        val u=URI(page)
        if(u.scheme!="https" || u.userInfo!=null || u.port !in setOf(-1,443)) return@runCatching null
        val host=u.host?.lowercase()
        val candidate=when(host) {
            "youtube.com","www.youtube.com","m.youtube.com" -> when {
                u.path=="/watch" -> CustomUploadPolicy.query(u)["v"]
                u.path.startsWith("/live/") -> u.path.removePrefix("/live/").trimEnd('/')
                else -> null
            }
            "youtu.be" -> u.path.trim('/')
            else -> null
        }
        candidate?.takeIf { id.matches(it) }
    }.getOrNull()
    fun manifest(url: String,nowSeconds: Long=System.currentTimeMillis()/1000): Boolean = runCatching {
        val u=URI(url);val host=u.host?.lowercase().orEmpty()
        val parts=u.path.split('/')
        val expiryIndex=parts.indexOf("expire")
        val expiry=expiryIndex.takeIf { it>=0 }?.let { parts.getOrNull(it+1)?.toLongOrNull() }
        if(expiryIndex>=0 && expiry==null) return@runCatching false
        url.length<=16384 && u.scheme=="https" && u.userInfo==null && u.port in setOf(-1,443) &&
            (host=="googlevideo.com" || host.endsWith(".googlevideo.com")) &&
            u.path.startsWith("/api/manifest/hls_") && MediaUrlClassifier.isContinuousManifest(url) &&
            (expiry==null || expiry>nowSeconds+30)
    }.getOrDefault(false)
    fun advertised(page: String,player: JSONObject,nowSeconds: Long=System.currentTimeMillis()/1000): String? {
        val expected=videoId(page) ?: return null
        val evidence=player.optJSONObject("youtube") ?: return null
        if(player.optBoolean("drm") || player.optBoolean("ad") || evidence.optString("videoId")!=expected ||
            evidence.optString("status")!="OK" || !evidence.optBoolean("liveNow")) return null
        return evidence.optString("manifest").takeIf { manifest(it,nowSeconds) }
    }
    fun complete(formats: List<JSONObject>): JSONObject? = formats.filter {
        it.optString("protocol").startsWith("m3u8") && !it.optBoolean("has_drm") &&
            it.optString("vcodec") !in setOf("","none","unknown") && it.optString("acodec") !in setOf("","none","unknown") &&
            manifest(it.optString("url"))
    }.maxByOrNull { it.optInt("height").coerceAtMost(10000).toLong()*1_000_000+it.optDouble("tbr",0.0).toLong() }
    fun masters(full: JSONObject,formats: List<JSONObject>): List<String> =
        (listOf(full.optString("hls_manifest_url"),full.optString("hlsManifestUrl"))+formats.map { it.optString("manifest_url") })
            .filter { manifest(it) }.distinct().take(2)
}
