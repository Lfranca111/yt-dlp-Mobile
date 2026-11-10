package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Version123LiveRecoveryTest {
    private val source="https://example.com/creator"
    private val page="https://example.com/live/123"
    private fun job(state: String)=JSONObject().put("live",true).put("state",state)
        .put("rule","rule-a").put("source",source).put("page",page)
    private fun blocks(j: JSONObject)=LiveRecordingPolicy.blocks(j,"rule-a",source,page)

    @Test fun savedHistoryAllowsAnotherCaptureOfTheSameOngoingBroadcast() {
        assertFalse(blocks(job("saved")))
    }
    @Test fun stoppedAndFailedCapturesDoNotBecomePermanentLiveDuplicates() {
        for(state in listOf("canceled","interrupted","attention","dismissed","duplicate")) assertFalse(state,blocks(job(state)))
    }
    @Test fun everyOutstandingQueueStagePreventsAnOverlappingCapture() {
        for(state in listOf("queued","held","reserved","running","stopping","finalizing")) assertTrue(state,blocks(job(state)))
    }
    @Test fun uploadsAreNotSubjectToTheLiveAdmissionPolicy() {
        assertFalse(blocks(job("running").put("live",false)))
    }
    @Test fun twoRulesForTheSameSourceDoNotQueueTwoRecordings() {
        assertTrue(blocks(job("queued").put("rule","rule-b")))
    }
    @Test fun manualRecordingOfTheLiveDestinationAlsoBlocksAutomaticCapture() {
        assertTrue(blocks(job("running").put("rule","").put("source","")))
    }
    @Test fun anotherCreatorsRecordingDoesNotSuppressThisSource() {
        assertFalse(blocks(job("running").put("rule","rule-b").put("source","https://example.com/other").put("page","https://example.com/live/456")))
    }
    @Test fun sameRuleKeepsItsOutstandingTaskWhenTheDestinationChanges() {
        assertTrue(blocks(job("finalizing").put("source","https://example.com/old").put("page","https://example.com/live/old")))
    }
    @Test fun blankAndMalformedPagesNeverMatchEachOther() {
        assertFalse(LiveRecordingPolicy.samePage("",""))
        assertFalse(LiveRecordingPolicy.samePage("not a URL","not a URL"))
    }
    @Test fun unknownSiteQueryAndFragmentIdentitiesArePreserved() {
        assertFalse(LiveRecordingPolicy.samePage("$page?room=one","$page?room=two"))
        assertFalse(LiveRecordingPolicy.samePage("$page#one","$page#two"))
    }
    @Test fun verifiedYouTubeAliasesIdentifyTheSameActiveBroadcast() {
        assertTrue(LiveRecordingPolicy.samePage("https://youtu.be/abcdefghijk","https://www.youtube.com/live/abcdefghijk"))
    }
    @Test fun statusDistinguishesWaitingActiveAndSaving() {
        assertTrue(LiveRecordingPolicy.status("queued").contains("waiting"))
        assertTrue(LiveRecordingPolicy.status("running").contains("active"))
        assertTrue(LiveRecordingPolicy.status("finalizing").contains("saving"))
    }
    @Test fun plain403DoesNotAssertCaptchaOrMissingCookies() {
        val detail="Live manifest returned HTTP 403; access failure cause unconfirmed"
        assertEquals("access",MonitoringFailure.kind(detail))
        val message=MonitoringFailure.message(detail)
        assertTrue(message.contains("cause unconfirmed"))
        assertFalse(message.contains("CAPTCHA"))
    }
    @Test fun explicitVerificationIsDistinctFromAGenericAccessFailure() {
        assertEquals("verification",MonitoringFailure.kind("HTTP 403: Are you human?"))
        assertFalse(MonitoringRecoveryPolicy.transientFailure("verification"))
    }
    @Test fun accessRetriesAreBoundedAndDoNotReuseAnExpiredManifest() {
        assertTrue(MonitoringRecoveryPolicy.transientFailure("access"))
        assertEquals(60_000L,MonitoringRecoveryPolicy.delay(1))
        assertEquals(120_000L,MonitoringRecoveryPolicy.delay(2))
        assertEquals(300_000L,MonitoringRecoveryPolicy.delay(3))
        assertEquals(0L,MonitoringRecoveryPolicy.delay(4))
    }
    @Test fun quotaIsDistinctFromBatteryOrBrowserAuthentication() {
        assertEquals("quota",MonitoringFailure.kind("ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync"))
        assertEquals("quota",MonitoringFailure.kind("Android foreground-service time budget exhausted"))
        assertFalse(MonitoringRecoveryPolicy.transientFailure("quota"))
        assertTrue(MonitoringFailure.message("Android foreground-service time budget exhausted").contains("Battery exemption does not remove"))
    }
    @Test fun quotaRetriesWaitLongerThanNetworkRetriesAndRemainBounded() {
        assertEquals(900_000L,MonitoringRecoveryPolicy.quotaDelay(1))
        assertEquals(1_800_000L,MonitoringRecoveryPolicy.quotaDelay(2))
        assertEquals(3_600_000L,MonitoringRecoveryPolicy.quotaDelay(3))
        assertEquals(0L,MonitoringRecoveryPolicy.quotaDelay(4))
    }
    @Test fun queueLifecycleAllowsFreshContentOnlyAfterPreviousCaptureFinishes() {
        val current=job("queued")
        for(state in listOf("queued","running","stopping","finalizing")) {
            current.put("state",state)
            assertTrue(blocks(current))
        }
        current.put("state","saved")
        assertFalse(blocks(current))
        val fresh=job("queued")
        assertTrue(blocks(fresh))
        assertSame(fresh,TransferRules.next(listOf(current,fresh),false))
        fresh.put("state","running")
        assertNull(TransferRules.next(listOf(current,fresh,job("queued")),false))
    }
    @Test fun ordinaryBackgroundAndNetworkRecoveryRemainAvailable() {
        assertEquals("background",MonitoringFailure.kind("startForegroundService() not allowed"))
        assertTrue(MonitoringRecoveryPolicy.transientFailure("background"))
        assertEquals("dns",MonitoringFailure.kind("No address associated with hostname"))
        assertTrue(MonitoringRecoveryPolicy.transientFailure("dns"))
    }
}
