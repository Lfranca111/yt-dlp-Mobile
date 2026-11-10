package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Version111PolicyTest {
    private fun job(id: String,state: String)=JSONObject().put("id",id).put("state",state)
    private fun page(id: String,url: String="https://img.example/$id.jpg",folder: String="")=CollectionItem(id,url,"jpg",folder=folder)
    @Test fun savingRetainsExclusiveLease() {
        val a=job("recording","finalizing");val b=job("live","queued")
        assertNull(TransferRules.next(listOf(a,b),false));assertTrue(TransferRules.acceptsCompletion(listOf(a,b),"recording"))
        a.put("state","saved");assertSame(b,TransferRules.next(listOf(a,b),false))
    }
    @Test fun staleCompletionCannotReleaseNextTask() {
        val jobs=listOf(job("old","saved"),job("new","running"))
        assertFalse(TransferRules.acceptsCompletion(jobs,"old"));assertTrue(TransferRules.acceptsCompletion(jobs,"new"));assertFalse(TransferRules.acceptsCompletion(jobs,""))
    }
    @Test fun lateResultFromPreviousAttemptCannotFinishRetriedJob() {
        val current=job("same-job","running").put("lease","attempt-two")
        assertFalse(TransferRules.acceptsCompletion(listOf(current),"same-job","attempt-one"))
        assertTrue(TransferRules.acceptsCompletion(listOf(current),"same-job","attempt-two"))
    }
    @Test fun staleLiveAlertCannotInterruptForMissingOrFinishedTask() {
        assertFalse(TransferRules.canPrioritize(null))
        assertFalse(TransferRules.canPrioritize(job("old","saved").put("live",true)))
        assertFalse(TransferRules.canPrioritize(job("video","queued").put("live",false)))
        assertTrue(TransferRules.canPrioritize(job("new","queued").put("live",true)))
    }
    @Test fun browserReservationCannotRaceAnAlreadyWaitingJob() {
        assertFalse(TransferRules.canReserve(listOf(job("next","queued")),false))
        assertFalse(TransferRules.canReserve(emptyList(),true))
        assertTrue(TransferRules.canReserve(listOf(job("notify","held")),false))
    }
    @Test fun pausedAndHeldJobsDoNotStart() {
        val q=job("video","queued");assertNull(TransferRules.next(listOf(q),true))
        assertSame(q,TransferRules.next(listOf(job("notify","held"),q),false))
    }
    @Test fun livePriorityStillWaitsForCurrentStopAndSave() {
        val current=job("current","stopping");val live=job("live","queued");val next=job("video","queued")
        assertNull(TransferRules.next(listOf(live,current,next),false))
        current.put("state","saved");assertSame(live,TransferRules.next(listOf(live,current,next),false))
    }
    @Test fun restartMarksOnlyActiveTasksInterrupted() {
        val jobs=TransferRules.activeStates.map { job(it,it) }+listOf(job("waiting","queued"),job("done","saved"))
        TransferRules.recover(jobs)
        assertEquals(4,jobs.count { it.optString("state")=="interrupted" });assertEquals("queued",jobs[4].getString("state"));assertEquals("saved",jobs[5].getString("state"))
    }
    @Test fun browserCredentialsExpireBeforeDeferredStart() { assertFalse(TransferRules.expiredBrowser(1000,31000));assertTrue(TransferRules.expiredBrowser(1000,31001));assertTrue(TransferRules.expiredBrowser(0,5)) }
    @Test fun allOptionsOffPreservesExistingDownloadArguments() { assertTrue(ExtraDownloadOptions.arguments(JSONObject(),false).isEmpty());assertNull(ExtraDownloadOptions.template(JSONObject(),false)) }
    @Test fun sectionAndRateAreValidatedAndDoNotAddSubtitleEmbedding() {
        val a=ExtraDownloadOptions.arguments(JSONObject().put("section_on",true).put("start","01:02").put("end","02:05").put("rate_on",true).put("rate","2.5").put("unit","M"),false)
        assertEquals(listOf("--download-sections","*01:02-02:05","--limit-rate","2.5M"),a)
    }
    @Test(expected=IllegalArgumentException::class) fun reversedSectionRejected() { ExtraDownloadOptions.arguments(JSONObject().put("section_on",true).put("start","02:00").put("end","01:00"),false) }
    @Test(expected=IllegalArgumentException::class) fun playlistSelectionRequiresPlaylistMode() { ExtraDownloadOptions.arguments(JSONObject().put("items_on",true).put("items","1-5,8"),false) }
    @Test(expected=IllegalArgumentException::class) fun outputPathCannotEscapeSession() { ExtraDownloadOptions.arguments(JSONObject().put("filename_on",true).put("filename","../../bad"),false) }
    @Test fun playlistFilenameKeepsIndexAndVideoIdentity() {
        val name=ExtraDownloadOptions.template(JSONObject().put("filename_on",true).put("filename","100% comic"),true)!!
        assertTrue(name.startsWith("%(playlist_index)05d"));assertTrue(name.contains("100%% comic"));assertTrue(name.contains("%(id)s"))
    }
    @Test fun emptyComicResponseDoesNotBecomeNewBaseline() { assertTrue(ComicDiff.compare(listOf(page("a")),emptyList()).review.isNotBlank()) }
    @Test fun signedUrlRotationIsNotANewComicPage() { assertTrue(ComicDiff.compare(listOf(page("a","https://old.example/a.jpg?token=old")),listOf(page("a","https://new.example/a.jpg?token=new"))).review.isBlank()) }
    @Test fun onlyAppendIsAutomatic() {
        val a=page("a");val b=page("b");val c=page("c")
        assertEquals(listOf(c),ComicDiff.compare(listOf(a,b),listOf(a,b,c)).additions)
        assertTrue(ComicDiff.compare(listOf(a,b),listOf(a,c,b)).review.isNotBlank())
        assertTrue(ComicDiff.compare(listOf(a,b),listOf(b,a)).review.isNotBlank())
    }
    @Test fun missingOrReplacedPagesRequireReview() {
        val a=page("a");val b=page("b")
        assertTrue(ComicDiff.compare(listOf(a,b),listOf(a)).review.isNotBlank())
        assertTrue(ComicDiff.compare(listOf(a),listOf(page("a","https://img.example/replaced.jpg"))).review.isNotBlank())
    }
    @Test fun identicalIdsInSeparateChaptersRemainSeparate() {
        val a=page("1",folder="chapter1");val b=page("1",folder="chapter2")
        assertEquals(listOf(b),ComicDiff.compare(listOf(a),listOf(a,b)).additions)
    }
    @Test fun repeatedIdentityIsNotAppendedTwice() { val a=page("a");assertTrue(ComicDiff.compare(listOf(a),listOf(a,a)).review.isNotBlank()) }
}
