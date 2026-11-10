package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class Version115PolicyTest {
    private fun recipe()=JSONObject().put("url","https://www.youtube.com/@creator/videos").put("pathPrefix","/watch").put("pathPattern","/watch").put("identityKeys",JSONArray(listOf("v")))
    private fun item(id:String)=MonitoringItem(id,"https://www.youtube.com/watch?v=$id",id)
    @Test fun customYoutubeVideosAndCreatorRootCanUseExtractor() {
        assertTrue(CustomBackgroundPolicy.extractorCompatible("https://www.youtube.com/@creator/videos"))
        assertTrue(CustomBackgroundPolicy.extractorCompatible("https://www.youtube.com/@creator"))
    }
    @Test fun customShortsAndExplicitFiltersAreNotReplacedWithDefaultUploads() {
        assertFalse(CustomBackgroundPolicy.extractorCompatible("https://www.youtube.com/@creator/shorts"))
        assertFalse(CustomBackgroundPolicy.extractorCompatible("https://www.youtube.com/@creator/videos?sort=p"))
    }
    @Test fun unmodifiedGenericSourceQueryIsRetained() {
        assertTrue(CustomBackgroundPolicy.extractorCompatible("https://example.com/creator/videos?category=one"))
    }
    @Test fun extractorItemsBecomeSameIdentitiesAsBrowserLinks() {
        val a=CustomBackgroundPolicy.scoped(recipe(),listOf(item("a").copy(url="https://m.youtube.com/watch?v=a&si=tracking")))
        assertEquals("custom:https://www.youtube.com/watch?v=a",a.single().id)
    }
    @Test fun unrelatedDomainsPathsAndQueriesAreExcluded() {
        val a=CustomBackgroundPolicy.scoped(recipe(),listOf(item("ok"),item("x").copy(url="https://evil.example/watch?v=x"),item("y").copy(url="https://www.youtube.com/channel/y"),item("z").copy(url="https://www.youtube.com/watch?list=z")))
        assertEquals(listOf("ok"),a.map { it.title })
    }
    @Test fun credentialsAndUnexpectedPortsAreRejected() {
        assertTrue(CustomBackgroundPolicy.scoped(recipe(),listOf(item("x").copy(url="https://u:p@www.youtube.com/watch?v=x"),item("x").copy(url="https://www.youtube.com:8443/watch?v=x"),item("x").copy(url="https://www.youtube.com/watch?v=x&token=secret"))).isEmpty())
    }
    @Test fun extractorIdentityDoesNotDependOnItsPrefix() {
        val a=CustomBackgroundPolicy.scoped(recipe(),listOf(item("a").copy(id="Youtube:a"),item("a").copy(id="Other:a")))
        assertEquals(1,a.size)
    }
    @Test fun recentComparisonDoesNotRequireScrolledHistory() {
        val browser=(1..100).map { item("$it") };val background=(1..10).map { item("$it") }
        assertTrue(CustomBackgroundPolicy.comparison(browser,background))
    }
    @Test fun noRecentOverlapDoesNotValidateDirectMethod() {
        assertFalse(CustomBackgroundPolicy.comparison(listOf(item("a")),listOf(item("b"))))
        assertFalse(CustomBackgroundPolicy.comparison(emptyList(),listOf(item("a"))))
    }
    @Test fun genericSignInLinkIsNotAnInteractiveBlock() {
        assertNull(CustomBackgroundPolicy.interaction("https://example.com/channel","Creator","<a href='/login'>Sign in</a><a href='/video/1'>Video</a>"))
    }
    @Test fun actualLoginAndChallengePagesRequireInteraction() {
        assertNotNull(CustomBackgroundPolicy.interaction("https://example.com/login","Login",""))
        assertNotNull(CustomBackgroundPolicy.interaction("https://example.com/channel","Verify you are human",""))
        assertNotNull(CustomBackgroundPolicy.interaction("https://example.com/channel","Account","<input type='password'>"))
    }
    @Test fun redirectToHomepageCannotEstablishChannelBaseline() {
        assertFalse(CustomBackgroundPolicy.channelCompatible("https://example.com/creator/videos","https://example.com/"))
        assertFalse(CustomBackgroundPolicy.channelCompatible("https://example.com/creator/videos","https://other.example/creator/videos"))
        assertFalse(CustomBackgroundPolicy.channelCompatible("https://example.com/list?creator=1","https://example.com/list?creator=2"))
    }
    @Test fun harmlessAddedQueryAndTrailingSlashAreAllowed() {
        assertTrue(CustomBackgroundPolicy.channelCompatible("https://example.com/list/?creator=1","https://example.com/list?creator=1&tracking=x"))
    }
    @Test fun rendererRequiresFourConsistentObservations() {
        val p=RenderedCheckProgress()
        repeat(3) { assertFalse(p.accept(listOf("a","b"))) }
        assertTrue(p.accept(listOf("a","b")))
    }
    @Test fun emptyOrChangedSnapshotsResetRenderingStability() {
        val p=RenderedCheckProgress();repeat(3) { p.accept(listOf("a")) }
        assertFalse(p.accept(emptyList()));assertFalse(p.accept(listOf("a")))
        assertFalse(p.accept(listOf("b")));assertFalse(p.accept(listOf("b")));assertFalse(p.accept(listOf("b")));assertTrue(p.accept(listOf("b")))
    }
    @Test fun liveAndOfflineStatesAlsoNeedStableObservations() {
        val p=RenderedCheckProgress();repeat(3) { assertFalse(p.accept(listOf("live"))) };assertTrue(p.accept(listOf("live")))
        assertFalse(p.accept(listOf("offline")))
    }
    @Test fun partialHistoricalCoverageCanStillRecognizeValidatedNewHead() {
        val now=1700000000000L
        val b=CustomUploadPolicy.decide(JSONObject(),emptySet(),listOf(item("a"),item("b")),now,false,true)
        val next=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("new"),item("a"),item("b")),now+1000,false,true,headValidated=true)
        assertEquals("new",next.fresh.single().title);assertFalse(next.state.getBoolean("coverage"))
    }
    @Test fun oldItemsRevealedBelowHeadStayUncertain() {
        val now=1700000000000L
        val b=CustomUploadPolicy.decide(JSONObject(),emptySet(),listOf(item("a"),item("b")),now,false,true)
        val next=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("a"),item("b"),item("older")),now+1000,false,true,headValidated=true)
        assertTrue(next.fresh.isEmpty());assertEquals(1,next.uncertain)
    }
    @Test fun unknownOrderingStillNeedsReviewWithValidatedRenderer() {
        val now=1700000000000L
        val b=CustomUploadPolicy.decide(JSONObject(),emptySet(),listOf(item("a"),item("b")),now,false,false)
        val next=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("new"),item("a"),item("b")),now+1000,false,false,headValidated=true)
        assertTrue(next.fresh.isEmpty());assertEquals(1,next.uncertain)
    }
    @Test fun validPublicationTimeWorksWithoutHistoricalCoverage() {
        val now=1700000000000L
        val b=CustomUploadPolicy.decide(JSONObject(),emptySet(),listOf(item("a")),now,false,false)
        val next=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("new").copy(publishedAt=now+100)),now+1000,false,false)
        assertEquals("new",next.fresh.single().title)
    }
    @Test fun firstAutomaticCheckStillNeverQueuesExistingItems() {
        val b=CustomUploadPolicy.decide(JSONObject(),emptySet(),listOf(item("a")),1700000000000L,false,true,true)
        assertTrue(b.fresh.isEmpty());assertTrue(b.baseline)
    }
}
