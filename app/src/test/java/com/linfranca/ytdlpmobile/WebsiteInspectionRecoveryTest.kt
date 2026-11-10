package com.linfranca.ytdlpmobile

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CancellationException

class WebsiteInspectionRecoveryTest {
    private val raw="https://progress-movies.example/movie/show-2lilib?audio=dub&ydlp_server=3"
    private val shell="<title>Loading title...</title><main><h1>Loading...</h1></main>"
    private val config="const ids=Object.freeze({0:'2',1:'b',2:'l',3:'i',4:'m',5:'7',6:'q',7:'x',8:'r',9:'t'});const player='https://moviesapi.to/movie/';"
    private val matched="<title>Loading title...</title><meta property='og:site_name' content='123Movies'><main class='detail-hero' aria-busy='true'><h1>Loading...</h1><script>$config</script><aside aria-busy='true'>Trending</aside></main>"
    private fun response(html: String,rendered: Boolean=false)=PageRedirectPolicy.Response(200,html=html,contentType="text/html",rendered=rendered)
    private val fetch={ _: String,_: Boolean -> response(shell) }
    @Test fun recognizerInterruptsLoadingAsSoonAsPlayerConfigurationMatches() {
        var samples=0;val log=mutableListOf<String>()
        val r=PageRedirectPolicy.resolve(raw,fetch,log=log::add,renderProgress={ _,observe ->
            samples++;assertFalse(observe(response(shell,true)))
            samples++;assertTrue(observe(response(matched,true)))
            response(matched,true)
        })
        assertEquals(2,samples);assertEquals("h123movies",r.handler);assertEquals(raw,r.target)
        assertTrue(log.any { "early handoff" in it });assertTrue(WebsitePageFingerprint.loading(matched))
    }
    @Test fun staticEvidenceAvoidsRendererEntirelyEvenWithBusySidebar() {
        var calls=0
        val r=PageRedirectPolicy.resolve(raw,{ _,_ -> response(matched) },renderProgress={ _,_ -> calls++;error("Must not render") })
        assertEquals(0,calls);assertEquals("h123movies",r.handler)
    }
    @Test fun timedOutRendererRetainsObservedMatchingEvidence() {
        val r=PageRedirectPolicy.resolve(raw,fetch,renderProgress={ _,observe ->
            assertTrue(observe(response(matched,true)));throw PageInspectionIncomplete("timeout after snapshot")
        })
        assertEquals("h123movies",r.handler)
    }
    @Test fun timedOutRendererWithInsufficientEvidenceReturnsOriginalForFallback() {
        val log=mutableListOf<String>()
        val r=PageRedirectPolicy.resolve(raw,fetch,log=log::add,renderProgress={ _,observe ->
            observe(response("<title>Unknown player</title><main><h1>Show</h1><video src='x.mp4'></video></main>",true));throw PageInspectionIncomplete("35 second time limit")
        })
        assertEquals(raw,r.target);assertFalse(r.verified);assertEquals("",r.handler)
        assertTrue(log.any { "35 second time limit" in it });assertTrue(log.any { "page_heading_present=true" in it })
        assertTrue(log.any { "action=continue existing fallback" in it })
    }
    @Test fun rendererFailureAlsoContinuesNormalFallback() {
        val r=PageRedirectPolicy.resolve(raw,fetch,renderProgress={ _,_ -> throw IllegalStateException("renderer unavailable") })
        assertFalse(r.verified);assertEquals(raw,r.target)
    }
    @Test fun failedScriptFetchAndMissingRendererDoNotStopTask() {
        val r=PageRedirectPolicy.resolve(raw,{ _,_ -> response(shell+"<script src='/config.js'></script>") })
        assertFalse(r.verified);assertEquals(raw,r.target)
    }
    @Test fun peripheralPendingFlagsDoNotMakePrimaryPageUnready() {
        assertFalse(WebsitePageFingerprint.loading("<title>Show</title><main><h1>Show</h1><aside data-page-loading='true' aria-busy='true'>Trending</aside></main>"))
        assertTrue(WebsitePageFingerprint.loading("<title>Show</title><main data-page-loading='true'><h1>Show</h1></main>"))
        assertFalse(WebsitePageFingerprint.loading("<title>Show</title><main><h1>Show</h1><div hidden data-page-loading='true'></div></main>"))
    }
    @Test fun meaningfulFindingsIncludeEveryCandidateAndDoNotRepeatIdenticalSnapshots() {
        val log=mutableListOf<String>()
        PageRedirectPolicy.resolve(raw,fetch,log=log::add,renderProgress={ _,observe ->
            repeat(4) { observe(response(shell,true)) };throw PageInspectionIncomplete("timeout")
        })
        for(name in listOf("AniWaves","WCOStream","h123movies")) {
            assertEquals(1,log.count { "candidate=$name;" in it })
            assertTrue(log.any { "candidate=$name;" in it && "missing=" in it && "conflicts=" in it })
        }
    }
    @Test fun contentIdConflictIsExplainedAndNeverRecognized() {
        val a=WebsitePageFingerprint.assess("https://new-ani.example/watch/show-123/ep-2","<div class='layout-page-watchtv' data-id='999'></div>")
        assertNull(a.handler);assertTrue(a.candidates().any { "route/DOM content ID mismatch" in it })
    }
    @Test fun diagnosticFindingsDoNotExposeUrlSecretsOrMarkup() {
        val log=mutableListOf<String>()
        PageRedirectPolicy.resolve(raw+"&token=SECRET",{ _,_ -> response(matched+"<p>PRIVATE BODY</p>") },log=log::add)
        assertFalse(log.any { "SECRET" in it || "PRIVATE BODY" in it || "https://" in it })
    }
    @Test fun uncertainRedirectAfterTimeoutFallsBackWithoutRegisteringAlias() {
        val old="https://h123movies.com/movie/show-2lilib"
        val target="https://uncertain-new.example/movie/show-2lilib"
        val r=PageRedirectPolicy.resolve(old,{ u,_ -> if(u==old) PageRedirectPolicy.Response(302,target) else response(shell) },renderProgress={ _,_ -> throw PageInspectionIncomplete("timeout") })
        assertFalse(r.verified);assertEquals(old,r.target);assertNull(WebsiteDomains.site(target))
    }
    @Test fun cancellationStillStopsInspectionAndDoesNotLaunchFallback() {
        try { PageRedirectPolicy.resolve(raw,fetch,renderProgress={ _,_ -> throw CancellationException("user stop") });fail("Expected cancellation") }
        catch(expected: CancellationException) {}
    }
    @Test fun actualNavigationConflictStillStopsInspection() {
        try { PageRedirectPolicy.resolve(raw,fetch,renderProgress={ _,_ -> throw PageRedirectPolicy.Blocked("changed page") });fail("Expected rejection") }
        catch(expected: PageRedirectPolicy.Blocked) {}
    }
    @Test fun redirectResponseTimeoutContinuesOriginalFallback() {
        val old="https://h123movies.com/movie/show-2lilib"
        val target="https://timeout-new.example/movie/show-2lilib"
        val r=PageRedirectPolicy.resolve(old,{ u,_ -> if(u==old) PageRedirectPolicy.Response(302,target) else throw java.net.SocketTimeoutException("timeout") })
        assertFalse(r.verified);assertEquals(old,r.target);assertNull(WebsiteDomains.site(target))
    }
    @Test fun unavailableRedirectResponseContinuesOriginalFallback() {
        val old="https://h123movies.com/movie/show-2lilib"
        val target="https://unavailable-new.example/movie/show-2lilib"
        val r=PageRedirectPolicy.resolve(old,{ u,_ -> if(u==old) PageRedirectPolicy.Response(302,target) else PageRedirectPolicy.Response(503) })
        assertFalse(r.verified);assertEquals(old,r.target);assertNull(WebsiteDomains.site(target))
    }
}
