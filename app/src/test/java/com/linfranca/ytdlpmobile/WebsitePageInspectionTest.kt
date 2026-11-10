package com.linfranca.ytdlpmobile

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import java.io.File

class WebsitePageInspectionTest {
    private val raw="https://fresh-movies.example/movie/kung-fury-2lilib"
    private val map="const ids=Object.freeze({0:'2',1:'b',2:'l',3:'i',4:'m',5:'7',6:'q',7:'x',8:'r',9:'t'});const player='https://moviesapi.to/movie/';"
    private val shell="<title>Loading title...</title><meta property='og:site_name' content='123Movies'><main class='detail-hero'><script src='/assets/config.js'></script></main>"
    private val ready=shell.replace("Loading title...","Kung Fury").replace("<main class='detail-hero'>","<main class='detail-hero'><h1>Kung Fury</h1>")
    private fun fetch(html: String)= { url: String,body: Boolean -> if(url.endsWith("config.js")) PageRedirectPolicy.Response(200,html=map,contentType="application/javascript") else PageRedirectPolicy.Response(200,html=if(body) html else "",contentType="text/html") }
    @Test fun placeholderTitleDoesNotHideMetadataConfigurationOrContentId() {
        val a=WebsitePageFingerprint.assess(raw,shell,map)
        assertEquals("h123movies",a.handler);assertTrue(a.loading);assertTrue(a.evidence["h123_compatible_content_id"]==true)
        assertTrue(a.evidence.keys.any { it.startsWith("ani_") });assertTrue(a.evidence.keys.any { it.startsWith("wco_") })
    }
    @Test fun confidentStaticConfigurationHandsOffWithoutWaitingForLoadingShell() {
        var renders=0;val log=mutableListOf<String>()
        val result=PageRedirectPolicy.resolve(raw,fetch(shell),log=log::add,render={ renders++;PageRedirectPolicy.Response(200,html=ready,contentType="text/html",rendered=true) })
        assertEquals(0,renders);assertEquals("h123movies",result.handler)
        assertTrue(log.any { "h123_player_config=true" in it });assertTrue(log.any { "page inspection handoff" in it })
    }
    @Test fun timeoutIsIncompleteButNormalFallbackRemainsAvailable() {
        val log=mutableListOf<String>()
        val result=PageRedirectPolicy.resolve(raw,{ _,_ -> PageRedirectPolicy.Response(200,html="<title>Loading...</title>",contentType="text/html") },log=log::add,render={ throw PageInspectionIncomplete("timeout") })
        assertFalse(result.verified);assertEquals(raw,result.target);assertTrue(log.any { "action=continue existing fallback" in it })
    }
    @Test fun noRendererForPendingContentContinuesNormalFallback() {
        val result=PageRedirectPolicy.resolve(raw,{ _,_ -> PageRedirectPolicy.Response(200,html="<title>Loading...</title>",contentType="text/html") })
        assertFalse(result.verified);assertEquals(raw,result.target)
    }
    @Test fun completedRenderedUnknownPageCanUseExistingExtractorFallback() {
        val unknown="<title>Ready page</title><main><h1>Ready content</h1><script src='/app.js'></script></main>"
        val result=PageRedirectPolicy.resolve("https://unknown.example/page",{ url,_ -> PageRedirectPolicy.Response(200,html=if(url.endsWith("app.js")) "var app=true" else unknown,contentType=if(url.endsWith("app.js")) "application/javascript" else "text/html") },render={ PageRedirectPolicy.Response(200,html=unknown,contentType="text/html",rendered=true) })
        assertFalse(result.verified);assertEquals("",result.handler)
    }
    @Test fun titleIsNotRequiredForAniOrWcoRecognition() {
        assertEquals("AniWaves",WebsitePageFingerprint.detect("https://unknown.example/watch/show-123/ep-2","<div class='layout-page-watchtv' data-id='123'></div>"))
        assertEquals("WCOStream",WebsitePageFingerprint.detect("https://unknown.example/show-episode-2","<iframe src='https://embed.wcostream.com/inc/embed/video-js.php'></iframe>"))
    }
    @Test fun titleAloneCannotRecognizeAHandlerAndExplicitChallengesNeedCompletion() {
        assertNull(WebsitePageFingerprint.detect(raw,"<title>123Movies</title><video src='ad.mp4'></video>",map))
        assertTrue(WebsitePageFingerprint.challenge("<form id='challenge-form'></form>"))
        assertFalse(WebsitePageFingerprint.challenge("<div hidden><form id='challenge-form'></form></div>"))
    }
    @Test fun readyHeadingOverridesStaleLoadingTitle() {
        assertFalse(WebsitePageFingerprint.loading("<title>Loading title...</title><main><h1>Actual movie</h1></main>"))
    }
    @Test fun scriptsAreInspectedWithoutTitleGateAndAreSameOriginOnly() {
        val scripts=WebsitePageFingerprint.scripts(raw,"<title>Loading title...</title><script src='/a.js'></script><link rel='modulepreload' href='/b.mjs'><script src='https://ads.example/track.js'></script>")
        assertEquals(2,scripts.size);assertTrue(scripts.all { it.startsWith("https://fresh-movies.example/") })
    }
    @Test fun renderReadinessIgnoresTitleAndRequiresStablePrimaryContent() {
        val r=WebsiteRenderReadiness()
        assertFalse(r.accept("loading",false,"movie"));assertFalse(r.accept("complete",true,"movie"))
        assertFalse(r.accept("interactive",false,"movie"));assertFalse(r.accept("complete",false,"movie"));assertTrue(r.accept("complete",false,"movie"))
        assertFalse(r.accept("complete",false,"new movie"));assertFalse(r.accept("complete",false,""))
    }
    @Test fun redirectVerificationUsesRenderedPageAndStrongBackendIdentity() {
        val old="https://h123movies.com/movie/kung-fury-2lilib"
        val result=PageRedirectPolicy.resolve(old,{ url,body -> if(url==old) PageRedirectPolicy.Response(301,raw) else fetch(shell)(url,body) },render={ PageRedirectPolicy.Response(200,html=ready,contentType="text/html",rendered=true) })
        assertTrue(result.verified);assertEquals(raw,result.target)
    }
    @Test fun liveNet123HtmlAndScriptsReproduceTitleBugAndRecognizeCompatibleHandler() {
        val folder=System.getenv("WEBSITE_INSPECTION_FIXTURES").orEmpty();assumeTrue(folder.isNotBlank())
        val responses=JSONObject(File(folder,"responses.json").readText());val url="https://net123movies.com/movie/kung-fury-2lilib"
        val html=responses.getString(url);val assets=responses.keys().asSequence().filter { it!=url }.joinToString("\n") { responses.getString(it) }
        assertEquals("h123movies",WebsitePageFingerprint.detect(url,html,assets))
        assertTrue(WebsitePageFingerprint.scripts(url,html).isNotEmpty())
        var rendered=0
        val complete=html.replace("Loading title...","Kung Fury").replace("<main id=\"main-content\">","<main id=\"main-content\"><h1>Kung Fury</h1>")
        val result=PageRedirectPolicy.resolve(url,{ target,body -> PageRedirectPolicy.Response(200,html=if(body) responses.optString(target) else "",contentType=if(target.endsWith(".js")) "text/javascript" else "text/html") },render={ rendered++;PageRedirectPolicy.Response(200,html=complete,contentType="text/html",rendered=true) })
        assertEquals("h123movies",result.handler);assertEquals(0,rendered)
    }
}
