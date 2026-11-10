package com.linfranca.ytdlpmobile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserWebNavigationTest {
    private static final String POST = "https://video.example/post/42";
    private static String intent(String data, String fallback) {
        return "intent:" + data + "#Intent;scheme=https;"
            + (fallback == null ? "" : "S.browser_fallback_url="
                + URLEncoder.encode(fallback, StandardCharsets.UTF_8).replace("+", "%20") + ";")
            + "end";
    }

    @Test public void prefersMatchingWebFallbackAndOnlyTriesOnce() {
        var browser = new BrowserWebNavigation.Session();
        browser.open(POST);
        String target = POST + "?web=1&sig=a+b%2F#play";
        String link = intent("//video.example/post/42", target);
        assertEquals(target, browser.recover(link, false));
        browser.pageVisible(target);
        assertNull(browser.recover(link, false));
        assertNull(browser.recover("sample://post/42", true));
    }

    @Test public void recoversCustomIntentFallbackAndUpgradesHttpPage() {
        var browser = new BrowserWebNavigation.Session();
        browser.open(POST.replace("https:", "http:"));
        browser.pageVisible(POST.replace("https:", "http:"));
        String fallback = URLEncoder.encode(POST, StandardCharsets.UTF_8);
        assertEquals(POST, browser.recover("intent://item/42#Intent;scheme=examplevideo;"
            + "S.browser_fallback_url=" + fallback + ";end", false));
    }

    @Test public void ignoresUnrelatedFallbackAndUsesExplicitSamePost() {
        var browser = new BrowserWebNavigation.Session();
        browser.open(POST);
        assertEquals(POST, browser.recover(intent("//video.example/post/42",
            "https://video.example.evil.test/post/42"), false));
        browser.open(POST);
        assertEquals(POST, browser.recover(intent("//video.example/post/42",
            "https://video.example/post/43"), false));
        browser.open(POST);
        assertEquals(POST, browser.recover(intent("//video.example/post/42",
            "http://video.example/post/42"), false));
    }

    @Test public void blocksAutomaticPostSubstitutionButAllowsUserChoiceOnSameSite() {
        var browser = new BrowserWebNavigation.Session();
        browser.open(POST);
        browser.pageVisible(POST);
        String other = intent("//video.example/post/43", "https://video.example/post/43?web=1");
        assertNull(browser.recover(other, false));
        assertEquals("https://video.example/post/43?web=1", browser.recover(other, true));
        browser.open(POST);
        browser.pageVisible(POST);
        assertNull(browser.recover(intent("//another.example/post/42", null), true));
    }

    @Test public void retriesKnownOriginalOnlyWhenNoPageIsVisible() {
        var browser = new BrowserWebNavigation.Session();
        browser.open(POST);
        assertEquals(POST, browser.recover("examplevideo://open/42", false));
        assertNull(browser.recover("examplevideo://open/42", false));
        browser.open(POST);
        browser.pageVisible(POST);
        assertNull(browser.recover("examplevideo://open/42", false));
        browser.open(POST);
        assertEquals(POST, browser.recover("intent://bad#Intent;end", false));
    }

    @Test public void leavesWebNavigationAndUnrelatedQueryParametersAlone() {
        var browser = new BrowserWebNavigation.Session();
        String threads = "https://www.threads.com/@user/post/ABC";
        assertEquals(threads + "?view=web", browser.open(threads + "?xmt=share&view=web"));
        assertEquals(POST + "?xmt=other", browser.open(POST + "?xmt=other"));
        for (String scheme : new String[] {"https","http","about","blob","data"}) {
            assertFalse(BrowserWebNavigation.isAppScheme(scheme));
        }
        assertTrue(BrowserWebNavigation.isAppScheme("intent"));
        assertTrue(BrowserWebNavigation.isAppScheme("instagram"));
    }
}
