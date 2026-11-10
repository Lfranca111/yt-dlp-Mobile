package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveEndEvidenceTest {
    private String hls(long seq,boolean end) {
        return "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:"+seq+"\n#EXTINF:2,\na.ts\n"+(end ? "#EXT-X-ENDLIST\n" : "");
    }
    @Test public void rejectsFinitePlaylistWithoutPreviousLiveEvidence() {
        assertNull(new LiveEndEvidence().observe(hls(20,true)));
    }
    @Test public void detectsLiveToEndWithSameOrAdvancingSequence() {
        var p=new LiveEndEvidence();assertNull(p.observe(hls(20,false)));assertNotNull(p.observe(hls(20,true)));
        p=new LiveEndEvidence();p.observe(hls(20,false));assertNotNull(p.observe(hls(21,true)));
    }
    @Test public void rejectsTimelineResetAndMissingEndMarker() {
        var p=new LiveEndEvidence();p.observe(hls(20,false));assertNull(p.observe(hls(1,true)));assertNull(p.observe(hls(22,false)));
    }
    @Test public void rejectsMasterErrorsAndEmptyPlaylists() {
        var p=new LiveEndEvidence();p.observe(hls(20,false));
        assertNull(p.observe("Forbidden"));assertNull(p.observe("#EXTM3U\n#EXT-X-ENDLIST"));
        assertNull(p.observe("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nv.m3u8\n#EXT-X-ENDLIST"));
        assertNull(p.observe("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:invalid\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST"));
    }
    @Test public void primaryManifestCompletionCannotBypassPairedTrackDrain() {
        assertFalse(LiveEndEvidence.independentCompletionApplies("HLS ENDLIST on previously live media playlist",true));
        assertFalse(LiveEndEvidence.independentCompletionApplies("DASH dynamic to finite static presentation",true));
        assertTrue(LiveEndEvidence.independentCompletionApplies("Platform explicitly ended the same previously verified broadcast",true));
        assertTrue(LiveEndEvidence.independentCompletionApplies("HLS ENDLIST on previously live media playlist",false));
        assertFalse(LiveEndEvidence.independentCompletionApplies(null,false));
    }
    private String mpd(String type,String id,String period,String duration) {
        return "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' type='"+type+"' id='"+id+"' mediaPresentationDuration='"+duration+"'><Period id='"+period+"'/></MPD>";
    }
    @Test public void dashRequiresPreviouslyDynamicSamePresentationAndFiniteDuration() {
        var p=new LiveEndEvidence();assertNull(p.observe(mpd("static","stream","p","PT20S")));
        assertNull(p.observe(mpd("dynamic","stream","p","")));
        assertNull(p.observe(mpd("static","other","p","PT20S")));
        assertNull(p.observe(mpd("static","stream","other","PT20S")));
        assertNull(p.observe(mpd("static","stream","p","")));
        assertNotNull(p.observe(mpd("static","stream","p","PT20S")));
    }
    @Test public void noDtdOrExternalEntityCanBecomeCompletionEvidence() {
        assertNull(new LiveEndEvidence().observe("<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///never-read'>]><MPD type='static'>&x;</MPD>"));
    }
    @Test public void sameBroadcastMustHaveExplicitEndedStatus() {
        assertTrue(LiveEndEvidence.broadcastEnded("a","a","post_live"));
        assertTrue(LiveEndEvidence.broadcastEnded("a","a","was_live"));
        for(String s:new String[]{"offline","is_live","is_upcoming","not_live","","403"}) assertFalse(LiveEndEvidence.broadcastEnded("a","a",s));
        assertFalse(LiveEndEvidence.broadcastEnded("a","b","was_live"));assertFalse(LiveEndEvidence.broadcastEnded("","","was_live"));
    }
}
