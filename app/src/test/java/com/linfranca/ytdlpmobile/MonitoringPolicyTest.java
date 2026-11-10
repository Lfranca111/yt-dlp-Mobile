package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;

public class MonitoringPolicyTest {
    private long stamp(String s) { return ZonedDateTime.parse(s).toInstant().toEpochMilli(); }
    @Test public void mobileAliasesPreserveIdentityAndFilters() {
        assertEquals("https://www.youtube.com/@example",MonitoringPolicy.normalize("https://m.youtube.com/@example"));
        assertEquals("https://x.com/example",MonitoringPolicy.normalize("https://mobile.twitter.com/example"));
        assertEquals("https://tailspace.com/browse?tag=5",MonitoringPolicy.normalize("https://tailspace.com/browse?tag=5#top"));
        assertEquals("https://stripchat.com/example@xh",MonitoringPolicy.normalize("https://stripchat.com/example@xh"));
    }
    @Test public void unknownMobileHostsAreNotGuessed() {
        assertEquals("https://m.example.org/user",MonitoringPolicy.normalize("https://m.example.org/user"));
        assertEquals("General fallback",MonitoringPolicy.handler("https://notyoutube.com/user"));
    }
    @Test public void rejectsEmbeddedCredentialsAndNonWebLinks() {
        for(String s: List.of("file:///tmp/test","https://user:password@example.com/user","example.com/user")) {
            try { MonitoringPolicy.normalize(s);fail(s); } catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void youtubeAndTwitchUseSeparateListings() {
        assertEquals("https://www.youtube.com/@example/streams",MonitoringPolicy.target("https://www.youtube.com/@example/videos",true));
        assertEquals("https://www.twitch.tv/example/videos?filter=archives&sort=time",MonitoringPolicy.target("https://www.twitch.tv/example",false));
    }
    @Test public void channelQueryDoesNotCorruptListingPath() {
        assertEquals("https://www.youtube.com/@example/streams",MonitoringPolicy.target("https://www.youtube.com/@example/videos?view=0",true));
        assertEquals("https://www.twitch.tv/example",MonitoringPolicy.target("https://www.twitch.tv/example/videos?filter=archives",true));
        assertEquals("https://example.org/user?filter=video",MonitoringPolicy.target("https://example.org/user?filter=video",false));
    }
    @Test public void schedulesMinimumAndBackoff() {
        long now=stamp("2026-10-12T12:00:00Z");Set<Integer> all=Set.of(1,2,3,4,5,6,7);
        assertEquals(now+300000,MonitoringPolicy.next(now,1,0,"UTC",-1,-1,all));
        assertEquals(now+900000,MonitoringPolicy.next(now,15,0,"UTC",-1,-1,all));
        assertTrue(MonitoringPolicy.next(now,5,4,"UTC",-1,-1,all)>now+300000);
    }
    @Test public void overnightBelongsToStartingDay() {
        assertTrue(MonitoringPolicy.inWindow(stamp("2026-10-13T01:00:00Z"),"UTC",22*60,2*60,Set.of(1)));
        assertFalse(MonitoringPolicy.inWindow(stamp("2026-10-14T01:00:00Z"),"UTC",22*60,2*60,Set.of(1)));
    }
    @Test public void windowAndDaySelectionMoveNextCheck() {
        long now=stamp("2026-10-12T21:59:00Z");
        long next=MonitoringPolicy.next(now,15,0,"UTC",8*60,22*60,Set.of(1,2,3,4,5));
        assertEquals(stamp("2026-10-13T08:00:00Z"),next);
    }
    @Test public void dstGapDoesNotInventNonexistentLocalTime() {
        long now=stamp("2026-03-08T01:59:00-06:00[America/Chicago]");
        long next=MonitoringPolicy.next(now,5,0,"America/Chicago",2*60,4*60,Set.of(7));
        assertTrue(MonitoringPolicy.inWindow(next,"America/Chicago",2*60,4*60,Set.of(7)));
        assertEquals(3,Instant.ofEpochMilli(next).atZone(ZoneId.of("America/Chicago")).getHour());
    }
}
