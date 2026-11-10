package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class Version118LinksTest {
    @Test public void eachExactAliasRetainsEncodedCreatorAndQuery() {
        for(var pair:MonitoringLinkPolicy.ALIASES.entrySet()) {
            String expected="https://"+pair.getValue()+"/creator%20name/videos?sort=new&tag=%2B1";
            assertEquals(expected,MonitoringPolicy.normalize("https://"+pair.getKey()+"/creator%20name/videos?sort=new&tag=%2B1#top"));
        }
    }
    @Test public void everyBuiltInHandlerKeepsUnverifiedHostAndPath() {
        for(String h:List.of("kick.com","instagram.com","tiktok.com","vimeo.com","dailymotion.com","rumble.com","odysee.com","bitchute.com","rutube.ru","picarto.tv","space.bilibili.com","chzzk.naver.com","sooplive.com","newgrounds.com","reddit.com","chaturbate.com","chaturbate.eu","chaturbate.global","stripchat.com","xhamsterlive.com")) {
            String url="https://"+h+"/creator?sort=new&filter=video";
            assertEquals(url,MonitoringPolicy.normalize(url));assertNotEquals("General fallback",MonitoringPolicy.handler(url));
        }
    }
    @Test public void unknownMobileAndLookalikeSitesAreNotRewritten() {
        for(String h:List.of("m.example.org","m.notyoutube.com","youtube.com.evil.example","mobile.unknown.example")) {
            String url="https://"+h+"/creator?filter=a";assertEquals(url,MonitoringPolicy.normalize(url));
            assertEquals("General fallback",MonitoringPolicy.handler(url));
        }
    }
    @Test public void exactCustomSourceIsPreservedIncludingItsQueryAndHost() {
        String url="https://m.youtube.com/@example/shorts?view=0&sort=dd";
        assertEquals(url,MonitoringLinkPolicy.input(url,false));
    }
    @Test public void shortsAndStreamsTabsAreNotBroadenedForUploadChecks() {
        assertEquals("https://www.youtube.com/@creator/shorts?sort=dd",MonitoringPolicy.target("https://m.youtube.com/@creator/shorts?sort=dd&si=tracking",false));
        assertEquals("https://www.youtube.com/@creator/streams",MonitoringPolicy.target("https://m.youtube.com/@creator/streams",false));
    }
    @Test public void liveModeUsesSameCreatorAndRetainsFilters() {
        assertEquals("https://www.youtube.com/@creator/streams?sort=dd",MonitoringPolicy.target("https://m.youtube.com/@creator/videos?sort=dd&view=0",true));
    }
    @Test public void twitchSelectionAndQueryArePreserved() {
        assertEquals("https://www.twitch.tv/creator/videos?filter=clips&sort=views",MonitoringPolicy.target("https://m.twitch.tv/creator/videos?filter=clips&sort=views",false));
        assertEquals("https://www.twitch.tv/creator/clips?filter=clips",MonitoringPolicy.target("https://go.twitch.tv/creator/clips?filter=clips",false));
    }
    @Test public void creatorRootKeepsLanguageAndDefaultArchiveSelection() {
        assertEquals("https://www.twitch.tv/creator/videos?lang=en&filter=archives&sort=time",MonitoringPolicy.target("https://m.twitch.tv/creator?lang=en",false));
    }
    @Test public void singleItemsAreNotInventedIntoChannels() {
        for(String url:List.of("https://youtu.be/abcdef12345","https://m.youtube.com/watch?v=abcdef12345","https://youtube.com/shorts/abcdef12345","https://x.com/name/status/123","https://tiktok.com/@creator/video/123","https://instagram.com/p/abc/","https://twitch.tv/videos/123")) {
            try { MonitoringLinkPolicy.validateChannel(MonitoringPolicy.normalize(url));fail(url); } catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("channel/profile")); }
        }
    }
    @Test public void knownShortLinksRequireResolutionWithoutInventingIdentity() {
        for(String url:List.of("https://vm.tiktok.com/code","https://vt.tiktok.com/code","https://t.co/code","https://b23.tv/code","https://fb.watch/code","https://dai.ly/code","https://nico.ms/code","https://www.tiktok.com/t/code")) {
            assertTrue(MonitoringLinkPolicy.shortLink(url));assertEquals(url,MonitoringPolicy.normalize(url));
        }
        assertFalse(MonitoringLinkPolicy.shortLink("https://m.example.org/code"));
    }
    @Test public void recoveryUsesOnlyAnEquivalentPathAndQuery() {
        assertEquals("https://youtube.com/@creator/shorts?sort=dd",MonitoringLinkFallback.alternative("https://www.youtube.com/@creator/shorts?sort=dd"));
        assertNull(MonitoringLinkFallback.alternative("https://m.example.org/creator"));
        assertNull(MonitoringLinkFallback.alternative("https://www.youtube.com:8443/@creator/videos"));
    }
    @Test public void scopeMigrationRequiresBaselineOnlyWhenEnumerationChanged() {
        assertTrue(MonitoringLinkPolicy.changedUploadScope("https://www.youtube.com/@creator/shorts"));
        assertTrue(MonitoringLinkPolicy.changedUploadScope("https://www.youtube.com/@creator/streams"));
        assertTrue(MonitoringLinkPolicy.changedUploadScope("https://www.twitch.tv/creator/videos?filter=clips"));
        assertFalse(MonitoringLinkPolicy.changedUploadScope("https://www.youtube.com/@creator/videos"));
        assertFalse(MonitoringLinkPolicy.changedUploadScope("https://www.twitch.tv/creator"));
        assertFalse(MonitoringLinkPolicy.changedUploadScope("https://example.org/creator?filter=video"));
    }
    @Test public void credentialsAndNonWebInputsAreRejected() {
        for(String url:List.of("https://u:p@example.org/creator","javascript:alert(1)","file:///tmp/a","example.com/creator")) {
            try { MonitoringPolicy.normalize(url);fail(url); } catch(IllegalArgumentException expected) { }
        }
    }
}
