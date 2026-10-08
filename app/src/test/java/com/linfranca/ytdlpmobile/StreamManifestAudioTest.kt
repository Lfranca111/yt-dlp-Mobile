package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamManifestAudioTest {
    @Test fun pairsSeparateAudioAndVideoFromMaster() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Main",DEFAULT=YES,URI="sound/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,CODECS="avc1.42E01E",AUDIO="a"
            video/index.m3u8
        """.trimIndent()
        val result = StreamManifestAudio.inspect("https://cdn.example.com/live/master.m3u8", master)
        assertEquals(StreamManifestAudio.Status.SEPARATE, result.status)
        assertEquals("https://cdn.example.com/live/video/index.m3u8", result.videoUrl)
        assertEquals("https://cdn.example.com/live/sound/index.m3u8", result.audioUrl)
    }

    @Test fun mediaPlaylistAndVideoOnlyMasterDoNotClaimAudio() {
        val segmentList = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\nsegment.ts"
        assertEquals(StreamManifestAudio.Status.UNKNOWN,
            StreamManifestAudio.inspect("https://cdn.example.com/video.m3u8", segmentList).status)
        val videoOnlyMaster = "#EXTM3U\n#EXT-X-STREAM-INF:CODECS=\"avc1.42E01E\"\nvideo.m3u8"
        val result = StreamManifestAudio.inspect("https://cdn.example.com/master.m3u8", videoOnlyMaster)
        assertEquals(StreamManifestAudio.Status.UNKNOWN, result.status)
        assertNull(result.audioUrl)
    }

    @Test fun multiplexedCodecAnnouncementCountsAsAudio() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:CODECS=\"avc1.42E01E,mp4a.40.2\"\ncombined.m3u8"
        assertEquals(StreamManifestAudio.Status.INCLUDED,
            StreamManifestAudio.inspect("https://cdn.example.com/master.m3u8", master).status)
    }

    @Test fun pairsAudioRenditionReportedByLowLatencyVideoPlaylist() {
        val base = "https://cdn.example.com/v1/streams/origin.room/chunklist_2_video_123_llhls.m3u8?session=test"
        val playlist = """
            #EXTM3U
            #EXT-X-RENDITION-REPORT:URI="/v1/streams/origin.room/chunklist_0_video_123_llhls.m3u8?session=test",LAST-MSN=100
            #EXT-X-RENDITION-REPORT:URI="/v1/streams/origin.ad/chunklist_4_audio_123_llhls.m3u8?session=test",LAST-MSN=100
            #EXT-X-RENDITION-REPORT:URI="/v1/streams/origin.room/chunklist_4_audio_123_llhls.m3u8?session=test",LAST-MSN=100
            #EXT-X-RENDITION-REPORT:URI="/v1/streams/origin.room/chunklist_5_audio_123_llhls.m3u8?session=test",LAST-MSN=100
        """.trimIndent()
        val result = StreamManifestAudio.inspect(base, playlist)
        assertEquals(StreamManifestAudio.Status.SEPARATE, result.status)
        assertEquals(base, result.videoUrl)
        assertEquals("https://cdn.example.com/v1/streams/origin.room/chunklist_4_audio_123_llhls.m3u8?session=test", result.audioUrl)
    }

    @Test fun rejectsOtherSessionsAndStreamIds() {
        val playlist = """
            #EXTM3U
            #EXT-X-RENDITION-REPORT:URI="chunklist_4_audio_123_llhls.m3u8?session=other"
            #EXT-X-RENDITION-REPORT:URI="chunklist_4_audio_456_llhls.m3u8?session=test"
        """.trimIndent()
        assertEquals(StreamManifestAudio.Status.UNKNOWN,
            StreamManifestAudio.inspect("https://cdn.example.com/live/chunklist_2_video_123_llhls.m3u8?session=test", playlist).status)
    }
}
