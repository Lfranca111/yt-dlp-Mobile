package com.linfranca.ytdlpmobile

import java.net.URI

/** Only a playlist's contents can establish whether a named "master" actually includes audio. */
object StreamManifestAudio {
    enum class Status { INCLUDED, SEPARATE, UNKNOWN }
    data class Inspection(val status: Status, val videoUrl: String? = null, val audioUrl: String? = null)

    fun inspect(url: String, text: String): Inspection {
        if (!text.trimStart().startsWith("#EXTM3U")) return Inspection(Status.UNKNOWN)
        val lines = NativeMedia.lines(text, false)
        val media = lines.filter { it.startsWith("#EXT-X-MEDIA:", true) &&
            audioType.containsMatchIn(it.substringAfter(':')) }.toTypedArray()
        val audioUrl = NativeMedia.attributes(media, "URI").firstOrNull { it != null }?.let { resolve(url, it) }
        if (audioUrl != null) {
            var variant: String? = null
            for (index in lines.indices) {
                if (!lines[index].startsWith("#EXT-X-STREAM-INF:", true)) continue
                var next = index + 1
                while (next < lines.size && lines[next].isBlank()) next++
                if (next < lines.size && !lines[next].startsWith("#")) {
                    variant = resolve(url, lines[next])
                    break // Original behavior uses the first qualifying variant, even if its URI fails.
                }
            }
            if (variant != null && variant != audioUrl) return Inspection(Status.SEPARATE, variant, audioUrl)
            return Inspection(Status.UNKNOWN)
        }
        val reports = lines.filter { it.startsWith("#EXT-X-RENDITION-REPORT:", true) }.toTypedArray()
        val siblingAudio = NativeMedia.attributes(reports, "URI").asSequence()
            .mapNotNull { it?.let { relative -> resolve(url, relative) } }
            .firstOrNull { isMatchingAudioRendition(url, it) }
        if (siblingAudio != null) return Inspection(Status.SEPARATE, url, siblingAudio)
        val streamInfos = lines.filter { it.startsWith("#EXT-X-STREAM-INF:", true) }.toTypedArray()
        val codecs = NativeMedia.attributes(streamInfos, "CODECS")
        if (codecs.any { codec -> codec != null && audioCodecs.any { NativeMedia.find(codec, it, true) >= 0 } })
            return Inspection(Status.INCLUDED)
        return Inspection(Status.UNKNOWN)
    }

    private val audioType = Regex("(?:^|,)TYPE=AUDIO(?:,|$)", RegexOption.IGNORE_CASE)
    private val audioCodecs = arrayOf("mp4a", "opus", "vorbis", "ac-3", "ec-3")
    private val renditionName = Regex("(?i)^chunklist_\\d+_(video|audio)_([A-Za-z0-9]+)_llhls\\.m3u8$")
    private val sessionPattern = Regex("(?:^|&)session=([^&]+)", RegexOption.IGNORE_CASE)

    private fun resolve(base: String, relative: String): String? = runCatching {
        URI(base).resolve(relative).toString().takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }.getOrNull()

    internal fun isMatchingAudioRendition(video: String, audio: String): Boolean = runCatching {
        val source = URI(video)
        val candidate = URI(audio)
        val pattern = renditionName
        val videoName = pattern.matchEntire(source.path.substringAfterLast('/')) ?: return@runCatching false
        val audioName = pattern.matchEntire(candidate.path.substringAfterLast('/')) ?: return@runCatching false
        val session = sessionPattern
        val videoSession = session.find(source.rawQuery.orEmpty())?.groupValues?.get(1)
        val audioSession = session.find(candidate.rawQuery.orEmpty())?.groupValues?.get(1)
        source.scheme == candidate.scheme && source.host.equals(candidate.host, true) &&
            source.path.substringBeforeLast('/') == candidate.path.substringBeforeLast('/') &&
            videoName.groupValues[1].equals("video", true) &&
            audioName.groupValues[1].equals("audio", true) &&
            videoName.groupValues[2] == audioName.groupValues[2] &&
            videoSession == audioSession
    }.getOrDefault(false)
}
