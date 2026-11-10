package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Version124StartupTest {
    @Test fun recoverableLiveStartupUsesShortBurstBeforeLongerDelays() {
        for(kind in listOf("background","network","dns","access")) {
            assertEquals(15_000L,MonitoringStartPolicy.delay(true,kind,1))
            assertEquals(30_000L,MonitoringStartPolicy.delay(true,kind,2))
            assertEquals(60_000L,MonitoringStartPolicy.delay(true,kind,3))
            assertEquals(300_000L,MonitoringStartPolicy.delay(true,kind,4))
            assertEquals(900_000L,MonitoringStartPolicy.delay(true,kind,5))
            assertEquals(0L,MonitoringStartPolicy.delay(true,kind,6))
        }
    }
    @Test fun confirmedQuotaUsesItsOwnBoundedRecovery() {
        assertEquals(900_000L,MonitoringStartPolicy.delay(true,"quota",1))
        assertEquals(1_800_000L,MonitoringStartPolicy.delay(true,"quota",2))
        assertEquals(3_600_000L,MonitoringStartPolicy.delay(true,"quota",3))
        assertEquals(0L,MonitoringStartPolicy.delay(true,"quota",4))
    }
    @Test fun uploadStartupAndOrdinaryCheckBackoffStayUnchanged() {
        assertEquals(60_000L,MonitoringStartPolicy.delay(false,"background",1))
        assertEquals(120_000L,MonitoringStartPolicy.delay(false,"network",2))
        assertEquals(300_000L,MonitoringStartPolicy.delay(false,"access",3))
        assertEquals(0L,MonitoringStartPolicy.delay(false,"background",4))
        assertEquals(60_000L,MonitoringRecoveryPolicy.delay(1))
    }
    @Test fun onlyShortWaitsCanRetainTheForegroundOwner() {
        assertEquals(15_000L,MonitoringStartPolicy.shortWait(115_000,100_000))
        assertEquals(60_000L,MonitoringStartPolicy.shortWait(160_000,100_000))
        assertEquals(0L,MonitoringStartPolicy.shortWait(160_001,100_000))
        assertEquals(0L,MonitoringStartPolicy.shortWait(100_000,100_000))
        assertEquals(0L,MonitoringStartPolicy.shortWait(99_999,100_000))
    }
    private fun legacy()=JSONObject().put("live",true).put("monitor","monitor-a").put("state","held")
        .put("engine_started",false).put("detail","Android foreground-service time budget unavailable; foreground retry held until cooldown")
    @Test fun priorAppSkippedAttemptCanBeMigratedToARealAttempt() {
        assertTrue(MonitoringStartPolicy.legacySkippedAttempt(legacy()))
        assertFalse(MonitoringStartPolicy.legacySkippedAttempt(legacy().put("startup_recovered_124",true)))
    }
    @Test fun migrationNeverRestartsAnActiveOrSavedRecording() {
        for(state in listOf("running","finalizing","stopping","saved","canceled","dismissed"))
            assertFalse(state,MonitoringStartPolicy.legacySkippedAttempt(legacy().put("state",state)))
        assertFalse(MonitoringStartPolicy.legacySkippedAttempt(legacy().put("engine_started",true)))
    }
    @Test fun migrationDoesNotRewriteRealAndroidRejectionsOrUploads() {
        assertFalse(MonitoringStartPolicy.legacySkippedAttempt(legacy().put("detail","Time limit already exhausted for foreground service type dataSync")))
        assertFalse(MonitoringStartPolicy.legacySkippedAttempt(legacy().put("live",false)))
        assertFalse(MonitoringStartPolicy.legacySkippedAttempt(legacy().put("monitor","")))
    }
    @Test fun failedAttemptCannotReleaseAnotherTasksLeaseOrStartTwoRecorders() {
        val active=JSONObject().put("id","one").put("lease","fresh").put("state","running")
        val waiting=JSONObject().put("id","two").put("state","queued")
        assertFalse(TransferRules.acceptsCompletion(listOf(active,waiting),"one","stale"))
        assertTrue(TransferRules.acceptsCompletion(listOf(active,waiting),"one","fresh"))
        assertNull(TransferRules.next(listOf(active,waiting),false))
        active.put("state","attention")
        assertSame(waiting,TransferRules.next(listOf(active,waiting),false))
    }
}
