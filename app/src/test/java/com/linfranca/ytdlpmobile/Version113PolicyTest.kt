package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class Version113PolicyTest {
    private fun item(n:String,date:Long=0)=MonitoringItem("custom:https://example.com/videos/$n","https://example.com/videos/$n",n,date)
    private val now=1700000000000L
    private fun base(complete:Boolean=true)=CustomUploadPolicy.decide(JSONObject(),emptySet(),listOf(item("a"),item("b")),now,complete,false)
    @Test fun firstCheckNeverQueuesEvenRecentDatedItems() {
        val r=CustomUploadPolicy.decide(JSONObject(),emptySet(),listOf(item("a",now+1)),now,true,true)
        assertTrue(r.fresh.isEmpty());assertTrue(r.baseline)
    }
    @Test fun trackingAndMobileLinksShareIdentity() {
        assertEquals(CustomUploadPolicy.canonical("https://m.youtube.com/watch?v=abc&pp=old#player"),CustomUploadPolicy.canonical("https://www.youtube.com/watch?si=new&v=abc"))
    }
    @Test fun queryIdentityInferredFromTwoExamples() {
        val r=CustomUploadPolicy.suggest(listOf("https://example.com/watch?v=a&si=one","https://example.com/watch?v=b&si=two"))
        assertEquals(listOf("v"),r.keys);assertEquals("/watch",r.pattern)
    }
    @Test fun segmentPatternDoesNotAcceptExtraDirectories() {
        assertTrue(CustomUploadPolicy.matchesPath("/videos/123","/videos/*"));assertFalse(CustomUploadPolicy.matchesPath("/videos/123/related","/videos/*"))
    }
    @Test fun scrollingOldUndatedItemsRequiresReview() {
        val b=base();val r=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("a"),item("b"),item("older")),now+10000,false,false)
        assertTrue(r.fresh.isEmpty());assertEquals(1,r.uncertain)
    }
    @Test fun verifiedNewPrefixCanQueueWithExplicitNewestFirst() {
        val b=base();val r=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("new"),item("a"),item("b")),now+10000,false,true)
        assertEquals("new",r.fresh.single().title)
    }
    @Test fun reversedHeadIsNotTrusted() {
        val b=base();val r=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("new"),item("b"),item("a")),now+10000,false,true)
        assertTrue(r.fresh.isEmpty());assertEquals(1,r.uncertain)
    }
    @Test fun incompleteBaselineCannotUseUndatedOrdering() {
        val b=base(false);val r=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("new"),item("a"),item("b")),now+10000,false,true)
        assertTrue(r.fresh.isEmpty());assertEquals(1,r.uncertain)
    }
    @Test fun reviewedBaselineExtensionDoesNotQueueOldItems() {
        val b=base(false);val r=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("a"),item("b"),item("old")),now+10000,true,true)
        assertTrue(r.fresh.isEmpty());assertTrue(r.baseline);assertTrue(r.state.getBoolean("coverage"))
    }
    @Test fun publicationTimesSeparateNewAndOldItems() {
        val b=base();val r=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("new",now+1000),item("old",now-1),item("future",now+900000)),now+10000,false,false)
        assertEquals("new",r.fresh.single().title);assertEquals(1,r.uncertain)
    }
    @Test fun pendingSurvivesSerializationAndRepeatedChecks() {
        val b=base();val r=CustomUploadPolicy.decide(b.state,b.seen,listOf(item("unknown")),now+1000,false,false)
        val again=CustomUploadPolicy.decide(JSONObject(r.state.toString()),r.seen,listOf(item("unknown")),now+2000,false,false)
        assertTrue(again.fresh.isEmpty());assertEquals(1,again.state.getJSONObject("pending").length())
    }
    @Test fun migrationDoesNotReplayExistingUploads() {
        val r=CustomUploadPolicy.decide(JSONObject().put("version",1),emptySet(),listOf(item("old")),now,false,true)
        assertTrue(r.fresh.isEmpty());assertTrue(r.baseline)
    }
    @Test(expected=IllegalArgumentException::class) fun emptyDoesNotEstablishBaseline() { CustomUploadPolicy.decide(JSONObject(),emptySet(),emptyList(),now,true,true) }
    @Test fun inferredSelectorsAndIdentityRejectUnrelatedLinks() {
        val j=JSONObject().put("schema",2).put("id","s").put("name","Test").put("mode","uploads").put("url","https://example.com/channel")
            .put("linkSelector","main:nth-of-type(1) a[href]").put("pathPrefix","/watch").put("pathPattern","/watch").put("identityKeys",org.json.JSONArray(listOf("v")))
        CustomSources.validate(j)
        val r=CustomSources.parse(j,"<main><a href='/watch?v=1&si=tracking'>One</a><a href='/watch?v=1&si=other'>Duplicate</a><a href='/watch?list=2'>List</a><a href='/watch/recommendations?v=2'>Other</a></main>",false)
        assertEquals(1,r.items.size);assertEquals("https://example.com/watch?v=1",r.items.single().url)
    }
}
