package com.linfranca.ytdlpmobile

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CancellationException

class PageRedirectPolicyTest {
    private val old="https://aniwaves.ru/watch/example-show-77548/ep-2?audio=dub&server=Vidplay"
    private val target="https://aniwaves-new.example/watch/example-show-77548/ep-2"
    private val html="<html><title>Example Show Episode 2 - AniWaves</title><div class='layout-page-watchtv' data-id='77548'></div></html>"
    private fun run(first: String=old,last: String=target,body: String=html): PageRedirectPolicy.Result = PageRedirectPolicy.resolve(first,{ url,read ->
        if(url==first) PageRedirectPolicy.Response(301,last) else if(read) PageRedirectPolicy.Response(200,html=body,contentType="text/html") else PageRedirectPolicy.Response(200)
    },render={ PageRedirectPolicy.Response(200,html=body,contentType="text/html",rendered=true) })
    private fun blocked(action: ()->Unit) { try { action();fail("Expected redirect rejection") } catch(e: PageRedirectPolicy.Blocked) { assertTrue(e.message!!.contains("Browser Assist")) } }
    @Test fun verifiedDomainChangePreservesEpisodeSubDubAndServer() {
        val r=run();assertTrue(r.verified);assertEquals(old,r.original);assertEquals("$target?audio=dub&server=Vidplay",r.target);assertEquals(2,r.chain.size)
    }
    @Test fun homepageAndDifferentEpisodeAreRejected() {
        blocked { run(last="https://aniwaves-new.example/") }
        blocked { run(last=target.replace("ep-2","ep-3")) }
        blocked { run(body=html.replace("Episode 2","Episode 3")) }
    }
    @Test fun challengeAndLoginAndAdsAreNotPageIdentity() {
        for(title in listOf("Just a moment","Sign in","Congratulations! Advertisement","Example Show Episode 2 - Advertisement")) { val r=run(body="<title>$title</title>");assertFalse(r.verified);assertEquals(old,r.target) }
    }
    @Test fun changedUserChoiceIsNeverSilentlyAccepted() { blocked { run(last="$target?audio=sub") };blocked { run(last="$target?server=Other") } }
    @Test fun invalidSchemeAndDowngradeAndCredentialsRejected() {
        for(dest in listOf(target.replace("https:","http:"),"javascript:alert(1)","https://name:password@new.example/watch/example-show-77548/ep-2")) blocked { run(last=dest) }
    }
    @Test fun loopsAndMissingLocationAndHopLimitStop() {
        blocked { PageRedirectPolicy.resolve(old,{ _,_->PageRedirectPolicy.Response(302,old) }) }
        blocked { PageRedirectPolicy.resolve(old,{ _,_->PageRedirectPolicy.Response(302) }) }
        var i=0;blocked { PageRedirectPolicy.resolve(old,{ _,_->PageRedirectPolicy.Response(302,"https://next${++i}.example/watch/example-show-77548/ep-2") }) };assertEquals(6,i)
    }
    @Test fun relativeRedirectsAndHttpsUpgradeKeepIdentity() {
        val original="http://website.example/watch/example-show"
        val r=PageRedirectPolicy.resolve(original,{ u,_-> if(u==original) PageRedirectPolicy.Response(308,"https://website.example/watch/example-show") else PageRedirectPolicy.Response(200) })
        assertTrue(r.verified);assertEquals("https://website.example/watch/example-show",r.target)
        blocked { run(last="/login") }
    }
    @Test fun tokenValuesAreNotInventedOrCopiedAndMediaIsNotPrefetched() {
        assertEquals("https://new.example/movie/example?audio=dub",PageRedirectPolicy.preserve("https://old.example/movie/example?token=secret&audio=dub","https://new.example/movie/example"))
        var calls=0;val r=PageRedirectPolicy.resolve("https://cdn.example/video.m3u8?token=secret",{ _,_->calls++;error("No request expected") });assertEquals(0,calls);assertFalse(r.verified)
    }
    @Test fun unreachableInitialSiteKeepsOriginalButUnreachableRedirectDoesNotGuess() {
        val r=PageRedirectPolicy.resolve(old,{ _,_->throw java.io.IOException("network") });assertEquals(old,r.target);assertFalse(r.verified)
        val deferred=PageRedirectPolicy.resolve(old,{ u,_-> if(u==old) PageRedirectPolicy.Response(302,target) else throw java.io.IOException("network") })
        assertFalse(deferred.verified);assertEquals(old,deferred.target)
    }
    @Test fun cancellationIsNotSwallowed() {
        try { PageRedirectPolicy.resolve(old,{ _,_->throw CancellationException("canceled") });fail("Expected cancellation") } catch(expected: CancellationException) {}
    }
    @Test fun genericChannelNeedsMatchingIdentityAndRetainsPath() {
        val r=run("https://old.example/creator-name","https://new.example/creator-name","<title>Creator Name videos</title>")
        assertTrue(r.verified)
        assertFalse(run("https://old.example/creator-name","https://new.example/creator-name","<title>Unrelated page</title>").verified)
    }
    @Test fun h123AndWcoHandlerEvidenceWorksWithoutChangingSelectedServer() {
        val m=run("https://h123movies.com/movie/allegiant-2b2lm7?ydlp_server=3","https://movies-new.example/movie/allegiant-2b2lm7","<title>Allegiant - 123Movies</title><script src='/player.js'></script>")
        assertTrue(m.verified);assertTrue(m.target.endsWith("ydlp_server=3"))
        assertTrue(run("https://m.wcostream.tv/adventure-time-episode-20","https://wco-new.example/adventure-time-episode-20","<title>Adventure Time Episode 20</title><iframe src='https://embed.wcostream.com/inc/embed/x'></iframe>").verified)
    }
    @Test fun verifiedAliasesRetainHandlerButSimilarUnverifiedNamesDoNot() {
        assertNull(WebsiteDomains.site("https://aniwaves.ru.attacker.example/watch/x"))
        WebsiteDomains.remember("https://aniwaves.ru/watch/x","https://verified-ani.example/watch/x")
        assertEquals("AniWaves",WebsiteDomains.site("https://verified-ani.example/watch/x"));assertTrue(WebsiteDomains.equivalent("aniwaves.ru","verified-ani.example"))
        assertFalse(WebsiteDomains.equivalent("aniwaves.ru","unknown.example"))
    }
    @Test fun logicalSourceCannotSilentlySwitchToAnotherKnownService() { blocked { run(last=target.replace("aniwaves-new.example","h123movies.com")) } }
    @Test fun monitoringIdentityQueryCannotBeDropped() {
        assertFalse(PageRedirectPolicy.samePage("https://old.example/profile.php?id=123","https://new.example/profile.php?id=456","<title>Creator Name</title>"))
    }
}
