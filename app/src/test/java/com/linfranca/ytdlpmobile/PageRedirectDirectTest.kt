package com.linfranca.ytdlpmobile

import org.junit.Test
import org.junit.Assert.*
import java.util.Base64
import org.json.JSONArray

class PageRedirectDirectTest {
    @Test fun directlyPastedAniDomainIsIdentifiedWithoutAnyRedirect() {
        val raw="https://fresh-ani.example/watch/example-show-77548/ep-2?audio=dub&server=Vidplay"
        val html="<title>Example Show - AniWaves</title><div class='layout-page-watchtv' data-id='77548'></div>"
        val calls=mutableListOf<Boolean>()
        val result=PageRedirectPolicy.resolve(raw,{ _,body -> calls+=body;PageRedirectPolicy.Response(200,html=if(body) html else "",contentType="text/html") })
        assertTrue(result.verified);assertEquals("AniWaves",result.handler);assertEquals(raw,result.target);assertEquals(listOf(false,true),calls)
    }
    @Test fun directlyPastedH123UsesBoundedSameOriginScriptEvidence() {
        val raw="https://fresh-movies.example/movie/allegiant-2b2lm7?ydlp_server=3"
        val html="<title>Allegiant - 123Movies</title><script src='https://ads.example/player.js'></script><script src='/assets/detail.js'></script>"
        val calls=mutableListOf<String>()
        val result=PageRedirectPolicy.resolve(raw,{ url,body -> calls+=url
            if(url.endsWith("detail.js")) PageRedirectPolicy.Response(200,html="const player='https://moviesapi.to/movie/';const ids=Object.freeze({0:'2',1:'b',2:'l',3:'m',4:'7',5:'q',6:'x',7:'r',8:'s',9:'t'});",contentType="application/javascript")
            else PageRedirectPolicy.Response(200,html=if(body) html else "",contentType="text/html")
        })
        assertEquals("h123movies",result.handler);assertTrue(result.target.endsWith("ydlp_server=3"));assertFalse(calls.any { "ads.example" in it })
    }
    @Test fun wcoRecognizesCurrentEmbeddedAndEncodedPlayerStructures() {
        val raw="https://fresh-wco.example/show-episode-2"
        val iframe="<iframe src='https://embed.wcostream.com/inc/embed/video-js.php?file=fixture'></iframe>"
        assertEquals("WCOStream",WebsitePageFingerprint.detect(raw,"<title>Show Episode 2</title>$iframe"))
        val a=JSONArray(iframe.map { Base64.getEncoder().encodeToString("abc${it.code+2468}xyz".toByteArray()) }).toString()
        val html="<title>Show Episode 2</title><script>var text='';var a=$a;a.forEach(function(v){text+=String.fromCharCode(parseInt(atob(v).replace(/\\D/g,'')) - 2468);});document.write(text);</script>"
        assertEquals("WCOStream",WebsitePageFingerprint.detect(raw,html))
    }
    @Test fun brandingAndGenericVideoOrSimilarDomainSpellingAreInsufficient() {
        for(html in listOf("<title>AniWaves</title><video src='x.mp4'></video>","<title>123Movies</title><script src='/x.js'></script>","<title>WCOStream</title><iframe src='https://other.example/player'></iframe>"))
            assertNull(WebsitePageFingerprint.detect("https://lookalike.example/watch/example-77548/ep-2",html))
        assertNull(WebsitePageFingerprint.detect("https://new.example/watch/example-77548/ep-2","<title>AniWaves</title><div class='layout-page-watchtv' data-id='999'></div>"))
    }
    @Test fun anUnrecognizedPageKeepsExistingExtractorFallback() {
        val raw="https://ordinary.example/movie"
        val result=PageRedirectPolicy.resolve(raw,{ _,_-> PageRedirectPolicy.Response(200,html="<title>Ordinary Video</title><video src='x.mp4'></video>",contentType="text/html") })
        assertFalse(result.verified);assertEquals("",result.handler);assertEquals(raw,result.target)
    }
    @Test fun inspectedAliasMaintainsDedicatedRoutingWithoutEditingOriginalLinks() {
        val raw="https://directly-inspected.example/watch/example-77548/ep-2"
        WebsiteDomains.recognize(raw,"AniWaves")
        assertEquals("AniWaves",WebsiteSourceResolver.site(raw));assertTrue(WebsiteDomains.equivalent("aniwaves.ru","directly-inspected.example"))
        try { WebsiteDomains.recognize(raw,"h123movies");fail("Must not silently replace handler") } catch(expected: IllegalArgumentException) {}
    }
}
