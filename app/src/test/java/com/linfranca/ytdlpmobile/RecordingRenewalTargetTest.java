package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingRenewalTargetTest {
    @Test public void masterCannotReplaceSelectedMediaRendition() {
        assertFalse(RecordingRenewalTarget.sameRoute("https://cdn.test/720p.m3u8", "https://cdn.test/master.m3u8"));
        assertTrue(RecordingRenewalTarget.sameRoute("https://cdn.test/720p.m3u8?session=old", "https://cdn.test/720p.m3u8?session=new"));
    }
    @Test public void schemeChangeAndMalformedUriAreRejectedOnBothSides() {
        assertFalse(RecordingRenewalTarget.sameRoute("https://cdn.test/v.m3u8", "http://cdn.test/v.m3u8"));
        assertFalse(RecordingRenewalTarget.sameRoute("bad uri", "https://cdn.test/v.m3u8"));
        assertFalse(RecordingRenewalTarget.sameRoute("file:///v.m3u8", "file:///v.m3u8"));
    }
    @Test public void hostRotationKeepsTheExistingPathContract() {
        assertTrue(RecordingRenewalTarget.sameRoute("https://edge1.cdn.test/v.m3u8", "https://edge2.cdn.test/v.m3u8"));
        assertFalse(RecordingRenewalTarget.sameRoute("https://cdn.test/720p.m3u8", "https://cdn.test/1080p.m3u8"));
    }
}
