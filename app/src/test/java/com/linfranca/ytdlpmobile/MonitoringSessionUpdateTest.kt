package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class MonitoringSessionUpdateTest {
    @Test fun browserRecoveryKeepsExactChannelAndVideoIdentity() {
        assertTrue(MonitoringBuiltinBrowser.samePage("https://www.youtube.com/watch?v=abc","https://m.youtube.com/watch?v=abc&feature=share"))
        assertTrue(MonitoringBuiltinBrowser.samePage("https://chaturbate.com/test_room/","https://www.chaturbate.com/test_room/"))
        for(other in listOf("https://m.youtube.com/watch?v=other","https://evil.example/watch?v=abc","http://m.youtube.com/watch?v=abc","https://m.youtube.com:444/watch?v=abc","https://user:secret@m.youtube.com/watch?v=abc","https://m.youtube.com/other?v=abc")) {
            assertFalse(other,MonitoringBuiltinBrowser.samePage("https://www.youtube.com/watch?v=abc",other))
        }
    }
    @Test fun importedCookiesRemainTargetScopedBeforeMergingBrowserCookies() {
        val lines=listOf(
            "example.com\tFALSE\t/private\tTRUE\t0\thost\tsecret",
            ".example.com\tTRUE\t/\tFALSE\t0\tshared\tone",
            ".example.com\tTRUE\t/private\tTRUE\t0\tsecure\ttwo",
            ".example.com\tTRUE\t/private\tFALSE\t1\texpired\told",
            ".other.example\tTRUE\t/\tFALSE\t0\tforeign\tno"
        )
        val scoped=WebsiteHandlerBridge.cookieHeader(lines,"https://sub.example.com/private/child",100)
        assertEquals("shared=one; secure=two",scoped)
        assertEquals("shared=browser; secure=two",MonitoringCookieHeader.merge(scoped,"shared=browser"))
        assertEquals("shared=one",WebsiteHandlerBridge.cookieHeader(lines,"http://sub.example.com/private/child",100))
        assertEquals("shared=one",WebsiteHandlerBridge.cookieHeader(lines,"https://sub.example.com/private-other",100))
        assertEquals("",WebsiteHandlerBridge.cookieHeader(lines,"https://unrelated.test/",100))
    }
    @Test fun interactionAndRateLimitAreNotMistakenForOffline() {
        assertEquals("interaction",MonitoringFailure.kind("Age confirmation required"))
        assertEquals("interaction",MonitoringFailure.kind("Website agreement or consent required"))
        assertEquals("verification",MonitoringFailure.kind("Human verification required"))
        assertEquals("rate",MonitoringFailure.kind("Website returned HTTP 429"))
        for(error in listOf("Human verification required","HTTP 403","Age confirmation required","Website returned HTTP 429")) {
            assertFalse(MonitoringOfflinePolicy.confirmed(error))
        }
    }
    @Test fun extractorFailureDetailsKeepReasonButRemoveSessionValues() {
        val message="ERROR: Requested format is not available https://example.com/private token=supersecret"
        val clean=RecorderErrorDiagnostic.describe(IllegalStateException(message)).joinToString("\n")
        assertTrue(clean,clean.contains("Requested format is not available"))
        assertFalse(clean.contains("supersecret"));assertFalse(clean.contains("example.com"))
    }
}
