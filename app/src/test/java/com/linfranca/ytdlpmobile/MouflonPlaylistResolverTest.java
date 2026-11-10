package com.linfranca.ytdlpmobile;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.URI;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class MouflonPlaylistResolverTest {
    @Test public void latencyReferencesDoNotChangeResolvedMediaOrTimeline() throws Exception {
        observe(1); observe(2);
        String original=playlist(20,"a");
        String baseline=resolver.rewrite(URI.create(root+"123_720p.m3u8"),original);
        String withReferences=original.replace("#EXT-X-MOUFLON:URI:",
            "#EXT-X-MOUFLON:EXT-REF:123;privateLatencyPayload\n#EXT-X-MOUFLON:URI:");
        String resolved=resolver.rewrite(URI.create(root+"123_720p.m3u8"),withReferences);
        assertEquals(baseline,resolved);
        assertFalse(String.join("\n",logs).contains("privateLatencyPayload"));
        assertTrue(logs.stream().anyMatch(s -> s.contains("mouflon_ext_ref_handled")));
    }
    @Test public void latencyReferencesCannotSubstituteForVerifiedBrowserMapping() throws Exception {
        String body=playlist(20,"a").replace("#EXT-X-MOUFLON:URI:",
            "#EXT-X-MOUFLON:EXT-REF:123;payload\n#EXT-X-MOUFLON:URI:");
        try { resolver.rewrite(URI.create(root+"123_720p.m3u8"),body); fail(); }
        catch(MouflonPlaylistResolver.Pending expected) { }
    }
    @Test public void unknownTagIdentifiesStageAndLineWithoutPayload() throws Exception {
        try {
            resolver.rewrite(URI.create(root+"123_720p.m3u8"),"#EXTM3U\n#EXT-X-MOUFLON:NEW:secret-value\n");
            fail();
        } catch(IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tag=NEW"));
            assertTrue(expected.getMessage().contains("line=2"));
            assertFalse(expected.getMessage().contains("secret-value"));
        }
        assertTrue(logs.stream().anyMatch(s -> s.contains("stage=tag inspection")));
    }
    @Test public void compatibleSourcesStillRequireApprovedMediaHost() {
        assertTrue(MouflonPlaylistResolver.compatibleMaster(master));
        assertFalse(MouflonPlaylistResolver.compatibleMaster("https://unknown.example/hls/123/master/a.m3u8"));
        assertFalse(MouflonPlaylistResolver.observedMaster(master));
        MouflonPlaylistResolver.observe(master,Map.of());
        assertTrue(MouflonPlaylistResolver.observedMaster(master));
    }
    private final String root="https://media-hls.doppiocdn.media/b-hls-1/123/";
    private final String master="https://edge-hls.doppiocdn.media/hls/123/master/123_auto.m3u8";
    private final List<String> logs=new ArrayList<>();
    private MouflonPlaylistResolver resolver;
    @Before public void setup() {
        MouflonPlaylistResolver.clearObservations();
        resolver=new MouflonPlaylistResolver(master,logs::add);
    }
    private String actual(int seq,String quality,String part) {
        return root+"123_"+quality+"_"+seq+"_abcdefghijklmnop_"+(1000+seq)+part+".mp4";
    }
    private String encrypted(int seq,String quality,String part,int mask) {
        byte[] data="abcdefghijklmnop".getBytes(StandardCharsets.US_ASCII);
        for(int i=0;i<data.length;i++) data[i]^=(byte)(mask+i);
        String token=new StringBuilder(Base64.getEncoder().withoutPadding().encodeToString(data)).reverse().toString();
        return root+"123_"+quality+"_"+seq+"_"+token+"_"+(1000+seq)+part+".mp4";
    }
    private String playlist(int mask,String scheme) {
        return "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-MOUFLON:PSCH:v2:"+scheme+"\n"+
            "#EXT-X-MEDIA-SEQUENCE:1\n#EXT-X-MAP:URI=\""+root+"init.mp4\"\n"+
            "#EXT-X-PROGRAM-DATE-TIME:2026-10-10T12:00:00Z\n#EXT-X-DISCONTINUITY\n"+
            "#EXTINF:2.000\n#EXT-X-MOUFLON:URI:"+encrypted(1,"720p_av1","",mask)+"\nmedia.mp4\n"+
            "#EXTINF:2.000\n#EXT-X-MOUFLON:URI:"+encrypted(2,"720p_av1","",mask)+"\nmedia.mp4\n"+
            "#EXT-X-MOUFLON:URI:"+encrypted(3,"720p_av1","_part0",mask)+"\n"+
            "#EXT-X-PART:DURATION=0.500,URI=\"media.mp4\",INDEPENDENT=YES\n";
    }
    private void observe(int seq) { MouflonPlaylistResolver.observe(actual(seq,"720p_av1",""),Map.of()); }
    @Test public void resolvesHeldOutPartsAndPreservesTimeline() throws Exception {
        observe(1); observe(2);
        String result=resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"test-scheme"));
        assertTrue(result.contains(actual(3,"720p_av1","_part0")));
        assertTrue(result.contains("#EXT-X-MEDIA-SEQUENCE:1"));
        assertTrue(result.contains("#EXT-X-DISCONTINUITY"));
        assertTrue(result.contains("#EXT-X-PROGRAM-DATE-TIME:2026-10-10T12:00:00Z"));
        assertTrue(result.contains("#EXT-X-MAP:URI=\""+root+"init.mp4\""));
        assertFalse(result.contains("MOUFLON"));
        assertFalse(result.contains("URI=\"media.mp4\""));
        assertTrue(logs.stream().anyMatch(s -> s.contains("mapping_verified")));
        assertFalse(String.join("\n",logs).contains(root));
        assertFalse(String.join("\n",logs).contains("test-scheme"));
    }
    @Test public void oneSequenceIsInsufficientEvenWithSeveralParts() throws Exception {
        observe(1);
        MouflonPlaylistResolver.observe(actual(1,"720p_av1","_part0"),Map.of());
        try { resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a")); fail(); }
        catch(MouflonPlaylistResolver.Pending expected) { }
    }
    @Test public void wrongQualityTimestampAndModelCannotTrainMapping() throws Exception {
        MouflonPlaylistResolver.observe(actual(1,"1080p_av1",""),Map.of());
        MouflonPlaylistResolver.observe(actual(2,"720p_av1","").replace("_1002", "_9999"),Map.of());
        MouflonPlaylistResolver.observe(actual(2,"720p_av1","").replace("/123/123_", "/999/999_"),Map.of());
        try { resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a")); fail(); }
        catch(MouflonPlaylistResolver.Pending expected) { }
    }
    @Test public void rotationRelearnsWithoutHardcodedKeys() throws Exception {
        observe(1); observe(2);
        resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a"));
        String result=resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(80,"b"));
        assertTrue(result.contains(actual(3,"720p_av1","_part0")));
        assertEquals(2,logs.stream().filter(s -> s.contains("mapping_verified")).count());
    }
    @Test public void conflictInvalidatesOldMappingAndWaits() throws Exception {
        observe(1); observe(2);
        resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a"));
        String conflict=playlist(80,"a").replace(encrypted(2,"720p_av1","",80),encrypted(2,"720p_av1","",81));
        try { resolver.rewrite(URI.create(root+"123_720p.m3u8"),conflict); fail(); }
        catch(MouflonPlaylistResolver.Pending expected) { }
        assertTrue(logs.stream().anyMatch(s -> s.contains("mapping_invalidated")));
    }
    @Test public void choosesBrowserQualityAndKeepsCurrentPlaylistQuery() throws Exception {
        String selected=root+"123_720p.m3u8?psch=v2&pkey=privateValue&_HLS_msn=30";
        MouflonPlaylistResolver.observe(selected,Map.of());
        String body="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n"+root+"123_720p.m3u8\n"+
            "#EXT-X-STREAM-INF:BANDWIDTH=99\n"+root+"123_1080p.m3u8\n";
        assertEquals(selected,resolver.preferredRendition(master,body));
        assertFalse(String.join("\n",logs).contains("privateValue"));
    }
    @Test public void headersNeverBorrowAnotherOriginCookieOrRange() {
        MouflonPlaylistResolver.observe(root+"init.mp4",Map.of("Cookie","mediaOnly","Range","bytes=0-9","User-Agent","browser"));
        LiveHlsRelay.Access fallback=new LiveHlsRelay.Access("ua","page","pageCookie",Map.of("Authorization","secret"));
        LiveHlsRelay.Access same=resolver.accessFor(URI.create(root+"segment.mp4"),fallback);
        assertEquals("mediaOnly",same.cookie); assertFalse(same.extra.containsKey("range"));
        LiveHlsRelay.Access other=resolver.accessFor(URI.create(master),fallback);
        assertEquals("",other.cookie); assertFalse(other.extra.containsKey("Authorization"));
    }
    @Test public void unknownSchemesAndMissingCustomUrisAreRejected() throws Exception {
        observe(1); observe(2);
        for(String body:List.of(playlist(20,"a").replace(":PSCH:v2:",":PSCH:v3:"),
            playlist(20,"a").replace("#EXT-X-MOUFLON:URI:","#EXT-X-MOUFLON:FILE:"))) {
            try { resolver.rewrite(URI.create(root+"123_720p.m3u8"),body); fail(); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void expiredMappingRequiresFreshEvidence() throws Exception {
        long[] now={0}; resolver=new MouflonPlaylistResolver(master,logs::add,()->now[0]);
        observe(1); observe(2); resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a"));
        MouflonPlaylistResolver.clearObservations(); now[0]=121_000_000_000L;
        try { resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a")); fail(); }
        catch(MouflonPlaylistResolver.Pending expected) { }
    }
    @Test public void rejectsHostSpoofingAndCrossModelDirectory() throws Exception {
        assertFalse(MouflonPlaylistResolver.observe(root.replace("doppiocdn.media","doppiocdn.media.evil.test")+"x.mp4",Map.of()));
        observe(1); observe(2);
        try { resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a").replace("/123/123_","/999/123_")); fail(); }
        catch(IllegalArgumentException expected) { }
    }
    @Test public void successfulMediaKeepsMappingAliveWithoutNewBrowserTraffic() throws Exception {
        long[] now={0}; resolver=new MouflonPlaylistResolver(master,logs::add,()->now[0]);
        observe(1); observe(2); resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a"));
        MouflonPlaylistResolver.clearObservations(); now[0]=119_000_000_000L;
        resolver.mediaResponse(URI.create(actual(3,"720p_av1","_part0")),200,true);
        now[0]=130_000_000_000L;
        assertTrue(resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a")).contains(actual(3,"720p_av1","_part0")));
    }
    @Test public void rejectedSegmentRequiresRelearning() throws Exception {
        observe(1); observe(2); resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a"));
        MouflonPlaylistResolver.clearObservations();
        resolver.mediaResponse(URI.create(actual(3,"720p_av1","_part0")),403,false);
        try { resolver.rewrite(URI.create(root+"123_720p.m3u8"),playlist(20,"a")); fail(); }
        catch(MouflonPlaylistResolver.Pending expected) { }
    }
}
