package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class VimeoBrowserVodTest {
    private val page = "https://vimeo.com/256258003"
    private val asset = "https://vod-adaptive-ak.vimeocdn.com/playlist/830f35cf-0598-4ace-a4ed-fb7959a0c012/sep"
    private val video = "$asset/video/rendition/playlist.m3u8"
    private val audio = "$asset/audio/rendition/playlist.m3u8"
    private val otherAudio = audio.replace("830f35cf", "2e41022e")
    private val master = "$asset/playlist.m3u8"
    private val frame = "player-frame"
    private val vod = """#EXTM3U
        |#EXT-X-VERSION:7
        |#EXT-X-MAP:URI="init.mp4"
        |#EXTINF:6.0,
        |segment1.m4s
        |#EXTINF:6.0,
        |segment2.m4s
        |#EXT-X-ENDLIST
    """.trimMargin()
    private fun select(
        manifests: Map<String, String>, observed: List<VimeoBrowserVod.Observed>,
        pageUrl: String = page, duration: Double = 12.0
    ) = VimeoBrowserVod.select(pageUrl, "Test", observed, frame, duration,
        { VimeoBrowserVod.Manifest(it, manifests.getValue(it)) }, { mapOf("Referer" to page) })
    private fun observed(vararg urls: String) = urls.map { VimeoBrowserVod.Observed(it, frame) }

    @Test fun masterUsesAudioGroupOfSelectedVideoAndRejectsUnrelatedRendition() {
        val body = """#EXTM3U
            |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="wrong",NAME="other",DEFAULT=YES,URI="$otherAudio"
            |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="correct",NAME="default",DEFAULT=YES,URI="$audio"
            |#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720,AUDIO="correct"
            |$video
        """.trimMargin()
        val pair = requireNotNull(select(mapOf(master to body, video to vod, audio to vod), observed(master)))
        val formats = pair.info.getJSONArray("formats")
        assertEquals(master, pair.root)
        assertEquals(2, formats.length())
        assertEquals(video, formats.getJSONObject(0).getString("url"))
        assertEquals("none", formats.getJSONObject(0).getString("acodec"))
        assertEquals(audio, formats.getJSONObject(1).getString("url"))
        assertEquals("none", formats.getJSONObject(1).getString("vcodec"))
        assertEquals(720, formats.getJSONObject(0).getInt("height"))
    }

    @Test fun separatelyObservedPlaylistsNeedSameAssetAndActiveFrame() {
        val playlists = mapOf(video to vod, audio to vod, otherAudio to vod)
        val pair = requireNotNull(select(playlists, observed(video, otherAudio, audio)))
        assertEquals(video, pair.root)
        assertEquals(12.0, pair.info.getDouble("_app_expected_duration"), 0.0)
        assertNull(select(playlists, observed(video, otherAudio)))
        assertNull(select(playlists, observed(video) + VimeoBrowserVod.Observed(audio, "another-frame")))
        assertNull(select(playlists, observed(video, audio), "https://vimeo.com.example.org/256258003"))
    }

    @Test fun liveTruncatedProtectedAndAmbiguousTracksAreNotQueuedAsComplete() {
        for (incomplete in listOf(vod.replace("#EXT-X-ENDLIST", ""), vod.replace("#EXTINF:6.0", "#EXTINF:1.0"),
            vod.replace("#EXT-X-VERSION:7", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\""))) {
            assertNull(select(mapOf(video to vod, audio to incomplete), observed(video, audio)))
        }
        val second = audio.replace("/rendition/", "/second/")
        assertNull(select(mapOf(video to vod, audio to vod, second to vod), observed(video, audio, second)))
        assertNull(select(mapOf(video to vod, audio to vod), observed(video, audio), duration = 100.0))
    }

    @Test fun foreignMasterTrackIsNotRequested() {
        val foreign = "https://vimeocdn.com.example.org/audio/playlist.m3u8"
        val body = """#EXTM3U
            |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="default",URI="$foreign"
            |#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="g"
            |$video
        """.trimMargin()
        assertNull(VimeoBrowserVod.select(page, "Test", observed(master), frame, 12.0, {
            assertNotEquals(foreign, it)
            VimeoBrowserVod.Manifest(it, if (it == master) body else vod)
        }, { emptyMap() }))
    }
}
