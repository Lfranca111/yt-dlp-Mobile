package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Map;

public class LiveHlsRecordingPlanTest {
    private static final String MEDIA = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:2,\ns5.ts\n";
    @Test public void masterIsResolvedBeforeLiveDecision() throws Exception {
        var pages = Map.of("https://test/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=10\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=20\nhigh.m3u8\n",
            "https://test/high.m3u8", MEDIA);
        var plan = LiveHlsRecordingPlan.resolve("https://test/master.m3u8", pages::get);
        assertEquals(LiveHlsRecordingPlan.Kind.LIVE, plan.kind);
        assertEquals("https://test/high.m3u8", plan.video);
        assertEquals("", plan.audio);
    }
    @Test public void endedSelectedPlaylistKeepsExtractorDespiteLiveLookingMaster() throws Exception {
        var pages = Map.of("https://test/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=20\nv.m3u8\n",
            "https://test/v.m3u8", MEDIA + "#EXT-X-ENDLIST\n");
        assertEquals(LiveHlsRecordingPlan.Kind.EXISTING,
            LiveHlsRecordingPlan.resolve("https://test/master.m3u8", pages::get).kind);
    }
    @Test public void matchesChosenAudioGroupRatherThanFirstUnrelatedTrack() throws Exception {
        var pages = Map.of("https://test/master.m3u8", "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"ad\",URI=\"ad.m3u8\"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"main\",URI=\"a.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=20,AUDIO=\"main\"\nv.m3u8\n",
            "https://test/v.m3u8", MEDIA, "https://test/a.m3u8", MEDIA);
        var plan = LiveHlsRecordingPlan.resolve("https://test/master.m3u8", pages::get);
        assertEquals(LiveHlsRecordingPlan.Kind.LIVE, plan.kind);
        assertEquals("https://test/a.m3u8", plan.audio);
    }
    @Test public void customFormatDoesNotFollowPlaceholderSegments() throws Exception {
        var plan = LiveHlsRecordingPlan.resolve("https://test/v.m3u8", u -> MEDIA + "#EXT-X-MOUFLON:PSCH:v2:sample\n");
        assertEquals(LiveHlsRecordingPlan.Kind.CUSTOM, plan.kind);
        assertFalse(plan.detail.contains("https://"));
    }
    @Test public void encryptedAndPartOnlyPlaylistsAreNotMisclassified() throws Exception {
        assertEquals(LiveHlsRecordingPlan.Kind.EXISTING, LiveHlsRecordingPlan.resolve("https://test/v.m3u8",
            u -> MEDIA + "#EXT-X-KEY:METHOD=AES-128,URI=\"secret.key\"\n").kind);
        assertEquals(LiveHlsRecordingPlan.Kind.EXISTING, LiveHlsRecordingPlan.resolve("https://test/v.m3u8",
            u -> "#EXTM3U\n#EXT-X-PART:DURATION=0.5,URI=\"p.m4s\"\n").kind);
    }
    @Test public void staleBlockingReloadDirectivesAreRemovedWithoutDroppingCredentials() throws Exception {
        var plan = LiveHlsRecordingPlan.resolve("https://test/v.m3u8?token=example&_HLS_msn=5&_HLS_part=2", u -> {
            assertEquals("https://test/v.m3u8?token=example", u);
            return MEDIA;
        });
        assertEquals(LiveHlsRecordingPlan.Kind.LIVE, plan.kind);
        assertEquals("https://test/v.m3u8?token=example", plan.video);
    }

}
