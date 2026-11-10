package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MonitoringPreparationTest {
    private fun hls(sequence: Int,extra: String="") = """
        #EXTM3U
        #EXT-X-TARGETDURATION:2
        #EXT-X-MEDIA-SEQUENCE:$sequence
        #EXTINF:2,
        s$sequence.ts
        $extra
    """.trimIndent()
    @Test fun realMultilineHlsRequiresAnAdvancingSequence() {
        assertEquals(10L,CustomLivePolicy.sequence(hls(10)))
        assertTrue(CustomLivePolicy.manifestLive(hls(10),hls(11),false))
        assertFalse(CustomLivePolicy.manifestLive(hls(10),hls(10),false))
        assertFalse(CustomLivePolicy.manifestLive(hls(11),hls(10),false))
        assertFalse(CustomLivePolicy.manifestLive(hls(10),hls(11,"#EXT-X-ENDLIST"),true))
        assertFalse(CustomLivePolicy.manifestLive(hls(10,"#EXT-X-PLAYLIST-TYPE:VOD"),hls(11),true))
        assertFalse(CustomLivePolicy.manifestLive("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8",hls(11),false))
    }
    @Test fun dynamicDashRequiresPublicationChangeAndRejectsProtection() {
        val first="<MPD type='dynamic' publishTime='one'><Period/></MPD>"
        val second=first.replace("one","two")
        assertTrue(CustomLivePolicy.manifestLive(first,second,false))
        assertFalse(CustomLivePolicy.manifestLive(first,first,false))
        assertFalse(CustomLivePolicy.manifestLive(first,second.replace("<Period/>","<ContentProtection/>"),false))
        assertFalse(CustomLivePolicy.dynamicDash("<MPD type='static'/>"))
    }
    @Test fun resourceHistoryUsesOriginalTimeAndOnlyContinuousMedia() {
        val now=100_000L
        val rows=JSONArray().put(JSONObject().put("url","https://cdn.example/live.m3u8").put("at",now-1000))
            .put(JSONObject().put("url","https://cdn.example/old.m3u8").put("at",now-31000))
            .put(JSONObject().put("url","https://cdn.example/future.m3u8").put("at",now+1000))
            .put(JSONObject().put("url","https://cdn.example/s.ts").put("at",now-1000))
            .put(JSONObject().put("url","https://cdn.example/ads/live.m3u8").put("at",now-1000))
        val player=JSONObject().put("resources",rows)
        assertEquals(listOf("https://cdn.example/live.m3u8"),MonitoringPlaybackEvidence.resources(player,now).map { it.url })
        assertTrue(MonitoringPlaybackEvidence.resources(player,now+32001).isEmpty())
        rows.put(JSONObject().put("url","https://cdn.example/live.m3u8").put("at",now-100))
        assertEquals(now-100,MonitoringPlaybackEvidence.resources(player,now).single().at)
    }
    @Test fun missingEvidenceReportsSpecificStageWithoutAcceptingPlayback() {
        val p=JSONObject().put("videoCount",1).put("playing",false).put("playError","NotAllowedError")
        assertEquals("playback-refused",MonitoringPlaybackEvidence.stage(p,false))
        assertEquals("playback-not-confirmed",MonitoringPlaybackEvidence.stage(p,true))
        p.put("playing",true)
        assertEquals("ready",MonitoringPlaybackEvidence.stage(p,true))
        assertEquals("no-continuous-stream",MonitoringPlaybackEvidence.stage(p,false))
        p.put("ad",true)
        assertEquals("advertisement",MonitoringPlaybackEvidence.stage(p,true))
        p.put("drm",true)
        assertEquals("protected-player",MonitoringPlaybackEvidence.stage(p,true))
    }
    @Test fun diagnosticDoesNotIncludeRawPlaybackErrorOrResourceAddresses() {
        val p=JSONObject().put("playError","secret https://private.example?token=123").put("resources",JSONArray().put("private"))
        assertFalse(MonitoringPlaybackEvidence.summary(p,0).contains("private"))
        assertFalse(MonitoringPlaybackEvidence.summary(p,0).contains("secret"))
    }
    @Test fun heldPreparationShowsCountdownAndReasonWithoutChangingQueueState() {
        val p=JSONObject().put("title","Live").put("state","held").put("monitor","job").put("start_failures",2)
            .put("retry_at",115_000L).put("detail","Player did not start")
        assertTrue(QueuePresentation.label(p,100_000L).contains("0:15"))
        assertTrue(QueuePresentation.label(p,116_000L).contains("retry due"))
        assertTrue(QueuePresentation.label(p,100_000L).contains("Player did not start"))
        assertEquals("held",p.getString("state"))
        p.put("monitor","")
        assertTrue(QueuePresentation.label(p,100_000L).contains("held —"))
    }
}
