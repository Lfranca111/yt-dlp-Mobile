package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Version126ConcurrencyTest {
    private fun job(id: String,state: String="queued",live: Boolean=false,retries: Int=0)=
        JSONObject().put("id",id).put("state",state).put("live",live).put("lease","lease-$id").put("start_failures",retries)
    @Test fun defaultStillAllowsOnlyOneActiveTask() {
        assertNull(TransferRules.next(listOf(job("a","running"),job("b")),false))
    }
    @Test fun configurableLimitCanExceedTwo() {
        val jobs=(1..3).map { job("$it","running") }+job("four")
        assertEquals("four",TransferRules.next(jobs,false,4)?.getString("id"))
        assertNull(TransferRules.next(jobs,false,3))
    }
    @Test fun liveThenRetryThenDownloadPriority() {
        val download=job("download");val retry=job("retry",retries=1);val live=job("live",live=true)
        assertEquals("live",TransferRules.next(listOf(download,retry,live),false,3)?.getString("id"))
        assertEquals("retry",TransferRules.next(listOf(download,retry),false,3)?.getString("id"))
    }
    @Test fun eachActiveLeaseAcceptsOnlyItsOwnCompletion() {
        val jobs=listOf(job("one","running"),job("two","finalizing"))
        assertTrue(TransferRules.acceptsCompletion(jobs,"two","lease-two"))
        assertTrue(TransferRules.acceptsCompletion(jobs,"one","lease-one"))
        assertFalse(TransferRules.acceptsCompletion(jobs,"one","lease-two"))
        assertFalse(TransferRules.acceptsCompletion(jobs,"waiting","lease-one"))
    }
    @Test fun retryWaitsDoNotOccupySlots() {
        assertEquals("download",TransferRules.next(listOf(job("retry","held",true),job("download")),false)?.getString("id"))
    }
    @Test fun loweringLimitRetainsRunningTasksButPreventsNewStarts() {
        val jobs=listOf(job("one","running"),job("two","stopping"),job("next"))
        assertNull(TransferRules.next(jobs,false,1))
        assertEquals("running",jobs[0].getString("state"))
        assertEquals("stopping",jobs[1].getString("state"))
    }
    @Test fun pausePreventsStartingAdditionalWork() {
        assertNull(TransferRules.next(listOf(job("one")),true,10))
    }
    @Test fun taskProgressAndRecoveryStateAreIndependent() {
        val first=MediaTaskState();val second=MediaTaskState()
        first.running=true;first.progress=67;first.log="first";first.recording403RecoveryAvailable=true
        second.running=true;second.progress=12;second.log="second"
        second.running=false
        assertTrue(first.running);assertEquals(67,first.progress);assertEquals("first",first.log)
        assertFalse(second.recording403RecoveryAvailable);assertEquals("second",second.log)
    }
}
