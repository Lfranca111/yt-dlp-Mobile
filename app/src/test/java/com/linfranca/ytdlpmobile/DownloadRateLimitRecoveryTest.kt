package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class DownloadRateLimitRecoveryTest {
    private val limited="[download] Got error: HTTP Error 429: Too Many Requests. Retrying fragment 1 (1/10)..."
    @Test fun stopsOnlyOncePerAttemptAndKeepsJobBudget() {
        val r=DownloadRateLimitRecovery()
        repeat(3) { attempt ->
            r.beginAttempt()
            assertTrue(r.observe(limited))
            assertFalse(r.observe(limited))
            assertTrue(r.wasLimited("CanceledException"))
            assertEquals(listOf(30_000L,60_000L,null)[attempt],r.nextDelay("CanceledException"))
        }
        assertEquals(2,r.retries)
        assertTrue(r.encountered)
    }
    @Test fun ignoresVideoIdsAndOrdinaryErrors() {
        val r=DownloadRateLimitRecovery()
        for(line in listOf("video429.mp4", "HTTP Error 403: Forbidden", "ERROR: Unsupported URL: /429")) {
            assertFalse(r.observe(line));assertNull(r.nextDelay(line))
        }
        assertEquals(0,r.retries)
        assertFalse(r.encountered)
    }
    @Test fun recognizesReportedHttpStatusForms() {
        for(line in listOf("HTTP/2 429", "website HTTP; status=429; values omitted", limited))
            assertTrue(DownloadFailurePolicy.rateLimited(line))
    }
    @Test fun stopsLimitedAttemptsFromBeingExportedEvenIfExecuteReturns() {
        val r=DownloadRateLimitRecovery();r.observe(limited)
        try { r.requireNotLimited();fail("Limited attempt must fail") } catch(expected: IllegalStateException) { }
        r.beginAttempt();r.requireNotLimited()
    }
    @Test fun boundedWaitUsesFullDelayWithoutBusyLoop() {
        var elapsed=0L;var calls=0
        DownloadRateLimitRecovery.await(30_000,{true}) { elapsed+=it;calls++ }
        assertEquals(30_000L,elapsed);assertEquals(120,calls)
    }
    @Test fun userStopCancelsCooldownPromptly() {
        var elapsed=0L
        try {
            DownloadRateLimitRecovery.await(60_000,{elapsed<750}) { elapsed+=it }
            fail("Stop must interrupt recovery")
        } catch(expected: IllegalStateException) { assertEquals("Download stopped",expected.message) }
        assertEquals(750L,elapsed)
    }
    @Test fun interruptionIsPropagated() {
        try {
            DownloadRateLimitRecovery.await(30_000,{true}) { throw InterruptedException() }
            fail("Interruption must propagate")
        } catch(expected: InterruptedException) { }
    }
    @Test fun non429FailureAfterRecoveryIsNotMisclassified() {
        val r=DownloadRateLimitRecovery();r.observe(limited);r.nextDelay(limited);r.beginAttempt()
        assertFalse(r.wasLimited("HTTP Error 403: Forbidden"))
        assertNull(r.nextDelay("ERROR: Unsupported URL: example"))
        assertEquals(1,r.retries)
    }
}
