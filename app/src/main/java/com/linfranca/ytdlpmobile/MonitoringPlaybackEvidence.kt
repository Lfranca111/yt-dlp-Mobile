package com.linfranca.ytdlpmobile

import org.json.JSONObject

/** Evidence from this browser load only; history inspection never renews a resource timestamp. */
internal object MonitoringPlaybackEvidence {
    data class Resource(val url: String,val at: Long)
    private val errors=setOf("NotAllowedError","NotSupportedError","AbortError","SecurityError","InvalidStateError","PlaybackError")
    fun resources(player: JSONObject,now: Long): List<Resource> {
        val rows=player.optJSONArray("resources") ?: return emptyList()
        return (0 until rows.length().coerceAtMost(500)).mapNotNull { n ->
            val row=rows.optJSONObject(n) ?: return@mapNotNull null
            val url=row.optString("url");val at=row.optLong("at",0)
            if(url.length>8192 || now-at !in 0L..30_000L || !MediaUrlClassifier.isContinuousManifest(url)) null else Resource(url,at)
        }.groupBy { it.url }.values.map { rowsForUrl -> rowsForUrl.maxBy { it.at } }
    }
    fun playError(player: JSONObject)=player.optString("playError").takeIf { it in errors }.orEmpty()
    fun stage(player: JSONObject,hasManifest: Boolean,liveIndicator: Boolean=false): String = when {
        player.optBoolean("drm") -> "protected-player"
        player.optBoolean("ad") -> "advertisement"
        !hasManifest && !player.optBoolean("playing") && playError(player).isNotBlank() -> "playback-refused"
        !hasManifest && player.optInt("videoCount")==0 -> "no-player-element"
        !hasManifest && !player.optBoolean("playing") -> "player-not-started"
        !hasManifest -> "no-continuous-stream"
        !player.optBoolean("playing") && !liveIndicator -> "playback-not-confirmed"
        else -> "ready"
    }
    fun summary(player: JSONObject,manifestCount: Int): String =
        "stage=${stage(player,manifestCount>0)}; playing=${player.optBoolean("playing")}; videoCount=${player.optInt("videoCount")}; frameCount=${player.optInt("frameCount")}; readyState=${player.optInt("readyState")}; playPending=${player.optBoolean("playPending")}; playError=${playError(player).ifBlank { "none" }}; mediaError=${player.optInt("mediaError").coerceIn(0,4)}; continuousStreams=$manifestCount"
    fun waiting(stage: String): String = when(stage) {
        "no-player-element" -> "No video element found in the page; an embedded player may need Browser Assist"
        "playback-refused" -> "The background player refused automatic playback; review the playback diagnostic"
        "player-not-started", "playback-not-confirmed" -> "The background player has not confirmed playback"
        "no-continuous-stream" -> "Player detected, but no fresh continuous stream address was found"
        "advertisement" -> "Waiting for the advertisement to finish"
        "protected-player" -> "Protected player requires manual review"
        else -> "Waiting for stable live playback evidence"
    }
}
