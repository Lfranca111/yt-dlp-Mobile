package com.linfranca.ytdlpmobile

import java.net.URI

/** Preserve the audio/video relationship with a single HLS input to FFmpeg. */
internal object PairedHlsMaster {
    fun build(video: String, audio: String): String {
        require(sequenceOf(video, audio).all { url ->
            val uri = URI(url)
            uri.scheme in setOf("https", "http") && uri.host != null &&
                url.none { it == '\r' || it == '\n' || it == '"' }
        }) { "Invalid HLS playlist URL." }
        NativeMedia.master(video, audio)?.let { return it }
        return """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="recording-audio",NAME="Audio",DEFAULT=YES,AUTOSELECT=YES,URI="$audio"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,AUDIO="recording-audio"
            $video
        """.trimIndent() + "\n"
    }
}
