package com.linfranca.ytdlpmobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrlClassifierTest {
    @Test
    fun backgroundRecordingOnlyAcceptsRefreshableManifests() {
        assertTrue(MediaUrlClassifier.isContinuousManifest("https://cdn.example.com/live/master.m3u8?token=abc"))
        assertTrue(MediaUrlClassifier.isContinuousManifest("https://cdn.example.com/live/manifest.mpd"))
        assertFalse(MediaUrlClassifier.isContinuousManifest("https://cdn.example.com/live/segment001.ts"))
        assertFalse(MediaUrlClassifier.isContinuousManifest("https://cdn.example.com/live/video.mp4"))
        assertFalse(MediaUrlClassifier.isContinuousManifest("https://cdn.example.com/preroll/master.m3u8"))
        assertFalse(MediaUrlClassifier.isContinuousManifest("https://cdn.example.com/live/master.m3u8?e=100"))
    }
    @Test
    fun acceptsDirectMediaStreams() {
        assertTrue(MediaUrlClassifier.isDirectMediaUrl("https://cdn.example.com/videos/movie.mp4?token=abc"))
        assertTrue(MediaUrlClassifier.isDirectMediaUrl("https://cdn.example.com/hls/movie/master.m3u8?token=abc"))
        assertTrue(MediaUrlClassifier.isDirectMediaUrl("https://cdn.example.com/dash/manifest.mpd"))
        assertTrue(MediaUrlClassifier.isDirectMediaUrl("https://video.example.com/videoplayback?id=123"))
    }

    @Test
    fun rejectsApiWrappersAndNestedPreviewUrls() {
        assertFalse(MediaUrlClassifier.isDirectMediaUrl(
            "https://api.example.com/get_media?site=x&sVideoUrl=https://cdn.example.com/pre_videos/123/mp4_720"
        ))
        assertFalse(MediaUrlClassifier.isDirectMediaUrl("https://cdn.example.com/pre_videos/123/preview.mp4"))
        assertFalse(MediaUrlClassifier.isDirectMediaUrl("https://api.example.com/player?format=mp4"))
    }

    @Test
    fun rejectsAdvertisingAndTrackingMedia() {
        assertFalse(MediaUrlClassifier.isDirectMediaUrl("https://pubads.doubleclick.net/ads/clip.mp4"))
        assertFalse(MediaUrlClassifier.isDirectMediaUrl("https://ads.example.com/media/clip.mp4"))
        assertFalse(MediaUrlClassifier.isDirectMediaUrl("https://cdn.example.com/preroll/ad.mp4"))
        assertFalse(MediaUrlClassifier.isDirectMediaUrl("https://cdn.example.com/video.mp4?ad_type=preroll"))
        assertFalse(MediaUrlClassifier.isDirectMediaUrl("https://analytics.example.com/beacon.mp4"))
    }

    @Test
    fun activePlayerStreamWinsOverNetworkHistory() {
        val oldManifest = "https://cdn.example.com/hls/old/master.m3u8"
        val activeVideo = "https://cdn.example.com/videos/current.mp4"
        assertTrue(
            MediaUrlClassifier.score(activeVideo, true, 2) >
                MediaUrlClassifier.score(oldManifest, false, 1)
        )
    }

    @Test
    fun rejectsExpiredSignedStreams() {
        assertTrue(MediaUrlClassifier.isExpired("https://cdn.example.com/master.m3u8?e=100", 100))
        assertFalse(MediaUrlClassifier.isExpired("https://cdn.example.com/master.m3u8?e=1000", 100))
        assertFalse(MediaUrlClassifier.isExpired("https://cdn.example.com/master.m3u8?token=abc", 100))
    }

    @Test
    fun identifiesCombinedAndSeparateTracks() {
        assertEquals(MediaUrlClassifier.StreamKind.COMBINED,
            MediaUrlClassifier.streamKind("https://cdn.example.com/live/master.m3u8"))
        assertEquals(MediaUrlClassifier.StreamKind.VIDEO,
            MediaUrlClassifier.streamKind("https://cdn.example.com/live/video_1080p.m3u8"))
        assertEquals(MediaUrlClassifier.StreamKind.AUDIO,
            MediaUrlClassifier.streamKind("https://cdn.example.com/live/audio_aac.m3u8"))
        assertEquals(MediaUrlClassifier.StreamKind.UNKNOWN,
            MediaUrlClassifier.streamKind("https://cdn.example.com/live/chunklist.m3u8"))
        assertEquals(MediaUrlClassifier.StreamKind.AUDIO,
            MediaUrlClassifier.streamKind("https://cdn.example.com/live/audio/master.m3u8"))
    }

    @Test
    fun matchesSiblingCdnHostsAsOneStreamFamily() {
        assertTrue(MediaUrlClassifier.sameStreamFamily(
            "https://video.cdn.example.com/live/video.m3u8",
            "https://audio.cdn.example.com/live/audio.m3u8"
        ))
    }

    @Test
    fun browserDownloadRejectsSabrButKeepsOlderWorkingEndpoints() {
        assertFalse(MediaUrlClassifier.isDownloadCandidate("https://rr.example.com/videoplayback?sabr=1&itag=399"))
        assertFalse(MediaUrlClassifier.isDownloadCandidate("https://rr.example.com/videoplayback?ump=1&mime=video%2Fmp4"))
        assertTrue(MediaUrlClassifier.isDownloadCandidate("https://rr.example.com/videoplayback?id=123"))
        assertTrue(MediaUrlClassifier.isDownloadCandidate("https://rr.example.com/videoplayback?itag=18&mime=video%2Fmp4"))
        assertTrue(MediaUrlClassifier.isDownloadCandidate("https://cdn.example.com/movie/master.m3u8?e=9999999999"))
    }
}
