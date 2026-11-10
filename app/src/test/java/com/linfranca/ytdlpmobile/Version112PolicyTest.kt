package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class Version112PolicyTest {
    private fun source()=JSONObject().put("schema",1).put("id","test").put("name","Channel")
        .put("url","https://example.com/creator/a").put("mode","uploads")
        .put("linkSelector","main a[href]").put("pathPrefix","/videos/")
        .put("liveSelector","#state").put("liveText","LIVE NOW").put("offlineText","OFFLINE")
    @Test fun emptyExceptionStillHasAUsefulType() { assertTrue(MonitoringStore.error(IllegalStateException("")).contains("IllegalStateException")) }
    @Test fun crashFrameSurvivesSanitization() { assertTrue(MonitoringStore.safe("frame example.Screen.copy(Screen.kt:123)").contains("Screen.kt:123")) }
    @Test fun rollingBufferDoesNotReplayRetainedLines() {
        val j=ProgressJournal()
        assertEquals("old\nretained\nlatest",j.accept("old\nretained\nlatest",1000))
        assertEquals("new",j.accept("retained\nlatest\nnew",2000))
        assertEquals("",j.accept("retained\nlatest\nnew",3000))
        assertEquals("error",j.accept("latest\nnew\nerror",4000))
    }
    @Test fun missingOverlapRetainsNewErrorsWithoutReplayingOldEvents() {
        val j=ProgressJournal();j.accept("old\nretained",1000)
        assertEquals("ERROR: new\nnew",j.accept("ERROR: new\nnew",2000))
    }
    @Test fun overlapMustMatchWholeLines() {
        val j=ProgressJournal();j.accept("old\nnew",1000)
        assertEquals("ERROR: new\nnew2",j.accept("ERROR: new\nnew2",2000))
    }
    @Test fun errorsAreNotProgressThrottled() {
        val j=ProgressJournal()
        val first="[download] 20%\nERROR: first"
        assertEquals(first,j.accept(first,1000))
        assertEquals("ERROR: second",j.accept(first+"\n[download] 21%\nERROR: second",1001))
    }
    @Test fun sourceRestrictsOriginAndContentArea() {
        val j=source();CustomSources.validate(j)
        val r=CustomSources.parse(j,"""<aside><a href="/videos/unrelated">Other</a></aside><main><a href="/videos/1">One</a><a href="https://evil.example/videos/2">Other site</a><a href="/videos/1">Duplicate</a></main>""",false)
        assertEquals(1,r.items.size);assertEquals("https://example.com/videos/1",r.items.single().url)
    }
    @Test fun relativeLinksHaveStableIdentity() {
        val j=source()
        val a=CustomSources.parse(j,"<main><a href='/videos/1#player'>One</a></main>",false)
        val b=CustomSources.parse(j,"<main><a href='https://example.com/videos/1#other'>One</a></main>",false)
        assertEquals(a.items.single().id,b.items.single().id)
    }
    @Test fun importsInvalidateRemoteVerification() {
        val imported=CustomSources.imported(source().put("testedAt",123).put("browserOnly",false).toString())
        assertEquals(0,imported.getLong("testedAt"));assertTrue(imported.getBoolean("browserOnly"));assertNotEquals("test",imported.getString("id"))
    }
    @Test(expected=IllegalArgumentException::class) fun executableImportsAreRejected() { CustomSources.imported(source().put("script","alert(1)").toString()) }
    @Test(expected=IllegalArgumentException::class) fun regexSelectorsAreRejected() { CustomSources.validate(source().put("linkSelector","a:matches((a+)+$)")) }
    @Test(expected=IllegalArgumentException::class) fun credentialsAreRejected() { CustomSources.validate(source().put("url","https://example.com/channel?token=secret")) }
    @Test(expected=IllegalArgumentException::class) fun rootUploadPatternIsRejected() { CustomSources.validate(source().put("pathPrefix","/")) }
    @Test(expected=IllegalStateException::class) fun emptyListingDoesNotBecomeBaseline() { CustomSources.parse(source(),"<main></main>",false) }
    @Test fun explicitLiveStates() {
        val j=source().put("mode","live")
        assertNotNull(CustomSources.parse(j,"<span id=state>LIVE NOW</span>",true).live)
        assertTrue(CustomSources.parse(j,"<span id=state>OFFLINE</span>",true).offline)
    }
    @Test(expected=IllegalStateException::class) fun ambiguousLiveStateIsNotOffline() { CustomSources.parse(source(),"<span id=state>LIVE NOW or OFFLINE</span>",true) }
    @Test(expected=IllegalStateException::class) fun absentLiveStateIsNotOffline() { CustomSources.parse(source(),"<html></html>",true) }
}
