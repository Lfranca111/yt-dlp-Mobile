package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class YouTubeLiveRecoveryTest {
    private val page="https://m.youtube.com/watch?v=abcdefghijk"
    private val master="https://manifest.googlevideo.com/api/manifest/hls_variant/expire/4102444800/master.m3u8"
    private val base=master.substringBeforeLast('/')+"/"
    private fun fmt(height: Int,audio: String)=JSONObject().put("url",base+"$height.m3u8").put("height",height)
        .put("vcodec","avc1").put("acodec",audio).put("protocol","m3u8_native")
    private fun player(id: String="abcdefghijk")=JSONObject().put("youtube",JSONObject().put("videoId",id).put("status","OK").put("liveNow",true).put("manifest",master))
    private fun media(seq: Int)="#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$seq\n#EXTINF:2,\ns$seq.ts\n"
    private val muxed="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720,CODECS=\"avc1.4D401F,mp4a.40.2\"\n720.m3u8\n"
    private val paired="""
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="wrong",DEFAULT=YES,URI="wrong.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="right",DEFAULT=YES,URI="audio.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=2000,RESOLUTION=1920x1080,CODECS="avc1",AUDIO="right"
        video.m3u8
    """.trimIndent()
    @Test fun completeLowerResolutionWinsOverSilentHigherResolution() {
        val result=YouTubeLivePolicy.complete(listOf(fmt(1080,"none"),fmt(720,"mp4a"),fmt(480,"mp4a")))
        assertEquals(720,result!!.getInt("height"))
        assertNull(YouTubeLivePolicy.complete(listOf(fmt(1080,"none"))))
        assertNull(YouTubeLivePolicy.complete(listOf(fmt(720,"mp4a").put("has_drm",true))))
    }
    @Test fun playerDataRequiresTheExactCurrentLiveVideo() {
        assertEquals(master,YouTubeLivePolicy.advertised(page,player()))
        assertNull(YouTubeLivePolicy.advertised(page,player("other_video")))
        assertNull(YouTubeLivePolicy.advertised(page,player().put("ad",true)))
        assertNull(YouTubeLivePolicy.advertised(page,player().put("drm",true)))
        val ended=player();ended.getJSONObject("youtube").put("liveNow",false)
        assertNull(YouTubeLivePolicy.advertised(page,ended))
        assertNull(YouTubeLivePolicy.advertised("https://evil.example/watch?v=abcdefghijk",player()))
    }
    @Test fun staleOrUntrustedManifestAddressesAreRejected() {
        assertTrue(YouTubeLivePolicy.manifest(master))
        assertFalse(YouTubeLivePolicy.manifest(master.replace("4102444800","1")))
        assertFalse(YouTubeLivePolicy.manifest(master.replace("4102444800","bad")))
        assertFalse(YouTubeLivePolicy.manifest(master.replace("https:","http:")))
        assertFalse(YouTubeLivePolicy.manifest(master.replace("manifest.googlevideo.com","manifest.googlevideo.com.evil.example")))
        assertFalse(YouTubeLivePolicy.manifest(master.replace("manifest.googlevideo.com","user@manifest.googlevideo.com")))
        assertFalse(YouTubeLivePolicy.manifest("https://rr1.googlevideo.com/videoplayback?sabr=1"))
    }
    @Test fun selectedVariantUsesItsOwnAudioGroup() {
        val p=YouTubeLiveHls.select(master,paired)
        assertEquals(base+"video.m3u8",p.video);assertEquals(base+"audio.m3u8",p.audio)
        assertFalse(p.audio.contains("wrong"))
        assertEquals("",YouTubeLiveHls.select(master,muxed).audio)
    }
    @Test fun masterWithoutVerifiedAudioIsNotAccepted() {
        assertThrows(IllegalStateException::class.java) { YouTubeLiveHls.select(master,muxed.replace(",mp4a.40.2","")) }
        assertThrows(IllegalStateException::class.java) { YouTubeLiveHls.select(master,paired.replace("AUDIO=\"right\"","AUDIO=\"missing\"")) }
    }
    @Test fun advancingMuxedPlaylistCanBeVerifiedWithoutABrowserManifestRequest() {
        var count=0;var waits=0
        val result=YouTubeLiveHls.verify(master,{ url -> if(url==master) muxed else media(10+count++) },{ waits++ })
        assertEquals(base+"720.m3u8",result.video);assertEquals(1,waits)
    }
    @Test fun separateAudioMustAdvanceAsWellAsVideo() {
        val reads=mutableMapOf<String,Int>()
        assertThrows(IllegalStateException::class.java) {
            YouTubeLiveHls.verify(master,{ url ->
                if(url==master) paired else { val n=reads.getOrDefault(url,0);reads[url]=n+1;media(if(url.endsWith("audio.m3u8")) 1 else n+1) }
            },{})
        }
        reads.clear()
        val p=YouTubeLiveHls.verify(master,{ url -> if(url==master) paired else { val n=reads.getOrDefault(url,0);reads[url]=n+1;media(n+1) } },{})
        assertEquals(base+"audio.m3u8",p.audio)
    }
    @Test fun completedProtectedAndCanceledPlaybackDoesNotStart() {
        assertThrows(IllegalStateException::class.java) { YouTubeLiveHls.verify(master,{ url -> if(url==master) muxed else media(1)+"#EXT-X-ENDLIST" },{}) }
        assertThrows(IllegalStateException::class.java) { YouTubeLiveHls.verify(master,{ url -> if(url==master) muxed else media(1)+"#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"" },{}) }
        assertThrows(java.util.concurrent.CancellationException::class.java) { YouTubeLiveHls.verify(master,{ url -> if(url==master) muxed else media(1) },{ throw java.util.concurrent.CancellationException() }) }
    }
}
