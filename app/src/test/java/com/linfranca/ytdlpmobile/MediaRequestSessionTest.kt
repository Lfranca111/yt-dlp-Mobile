package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files

class MediaRequestSessionTest {
    @Test fun generatedReferrerMatchesCrossOriginBrowserPolicy() {
        assertEquals("https://play.example/",MediaRequestPolicy.referrer("https://play.example/embed/id?token=private#frame","https://cdn.example/media"))
        assertEquals("https://play.example/embed/id?token=private",MediaRequestPolicy.referrer("https://play.example/embed/id?token=private#frame","https://play.example/media"))
        assertEquals("",MediaRequestPolicy.referrer("https://play.example/embed/id","http://play.example/media"))
        assertEquals("https://play.example:444/",MediaRequestPolicy.referrer("https://play.example:444/a","https://play.example/a"))
    }
    @Test fun sameOriginReferrersPreserveEncodedTokens() {
        val page="https://play.example/embed%2Fname?token=a%2Bb%26c#frame"
        assertEquals(page.substringBefore('#'),MediaRequestPolicy.referrer(page,"https://play.example/source"))
    }
    @Test fun importedCookieScopeIsNotBroadenedDuringObservation() {
        val jar=MediaCookieJar()
        jar.load("media.example\tFALSE\t/assets\tTRUE\t0\tsession\tknown\n","https://media.example/assets/a",overwrite=false)
        jar.observe("https://media.example/assets/a","session=known")
        assertEquals("",jar.header("https://media.example/outside"))
        assertEquals("session=known",jar.header("https://media.example/assets/a"))
    }
    @Test fun invalidReferrersAreNotForwarded() {
        assertEquals("",MediaRequestPolicy.referrer("blob:opaque","https://cdn.example/x"))
        assertEquals("https://play.example/path?q=x",MediaRequestPolicy.referrer("https://user:password@play.example/path?q=x#frag","https://play.example/video"))
    }
    @Test fun freshCookiesRetainDomainPathSecureAndHttpOnly() {
        val jar=MediaCookieJar { 100L }
        jar.accept("https://media.example/master",mapOf("Set-Cookie" to listOf("session=fresh; Path=/assets; Secure; HttpOnly; Max-Age=60")))
        assertEquals("",jar.header("https://media.example/playlist"))
        assertEquals("session=fresh",jar.header("https://media.example/assets/segment"))
        assertEquals("",jar.header("https://other.example/assets/segment"))
        assertEquals("",jar.header("http://media.example/assets/segment"))
        val exported=jar.netscape();assertTrue(exported.contains("#HttpOnly_media.example\tFALSE\t/assets\tTRUE\t160\tsession\tfresh"))
        val restored=MediaCookieJar { 100L };restored.load(exported)
        assertEquals("session=fresh",restored.header("https://media.example/assets/segment"))
    }
    @Test fun cookiePathsKeepTheirEscapedMeaning() {
        val jar=MediaCookieJar()
        jar.accept("https://media.example/",mapOf("Set-Cookie" to listOf("scoped=yes; Path=/assets%2Fprivate")))
        assertEquals("scoped=yes",jar.header("https://media.example/assets%2Fprivate/segment"))
        assertEquals("",jar.header("https://media.example/assets/private/segment"))
    }
    @Test fun foreignCookieDomainsAreRejected() {
        val jar=MediaCookieJar()
        jar.accept("https://media.example/master",mapOf("Set-Cookie" to listOf("session=bad; Domain=other.example; Path=/")))
        assertEquals("",jar.header("https://other.example/"))
    }
    @Test fun longerPathsAndDeletionTakePrecedenceOverStaleBrowserHeaders() {
        val jar=MediaCookieJar { 100L }
        jar.observe("https://media.example/","session=old")
        jar.accept("https://media.example/",mapOf("Set-Cookie" to listOf("session=deleted; Max-Age=0; Path=/")))
        jar.observe("https://media.example/","session=old")
        assertEquals("",jar.header("https://media.example/"))
        jar.accept("https://media.example/",mapOf("Set-Cookie" to listOf("session=fresh; Path=/assets")))
        assertEquals("session=fresh",jar.header("https://media.example/assets/x"))
        assertEquals("",jar.header("https://media.example/assets2/x"))
    }
    @Test fun domainCookiesWorkOnSubdomainsButHostCookiesDoNot() {
        val jar=MediaCookieJar()
        jar.accept("https://cdn.example/",mapOf("Set-Cookie" to listOf("shared=yes; Domain=cdn.example; Path=/", "host=yes; Path=/")))
        assertEquals("shared=yes",jar.header("https://child.cdn.example/"))
        assertFalse(jar.header("https://notcdn.example/").contains("shared"))
    }
    @Test fun retryAfterSupportsSecondsDateInvalidAndOverflow() {
        assertEquals(121_000L,MediaRequestPolicy.retryAfter("120",1000))
        assertEquals(0L,MediaRequestPolicy.retryAfter("invalid",1000))
        assertEquals(0L,MediaRequestPolicy.retryAfter("-5",1000))
        assertEquals(Long.MAX_VALUE,MediaRequestPolicy.retryAfter(Long.MAX_VALUE.toString(),1000))
        assertEquals(Long.MAX_VALUE,MediaRequestPolicy.retryAfter(Long.MAX_VALUE.toString()+"0",1000))
        assertEquals(1445412480000L,MediaRequestPolicy.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT",1000))
    }
    @Test fun taskJarRetainsDownloaderUpdatesAndNewSnapshotsSupersedeThem() {
        val dir=Files.createTempDirectory("session-test").toFile()
        try {
            fun snapshot(value: String)=JSONObject().put("_app_cookie_jar","# Netscape HTTP Cookie File\nmedia.example\tFALSE\t/assets\tTRUE\t0\tsession\t$value\n").toString()
            val first=snapshot("prepared")
            val path=DownloadSessionFiles.cookieFile(first,"",dir)
            val f=java.io.File(path);assertTrue(DownloadSessionFiles.privateFile(f));assertTrue(f.readLines().first().startsWith("# Netscape HTTP Cookie File"));assertTrue(DownloadSessionFiles.privateFile(java.io.File(path+".snapshot")))
            f.writeText(f.readText().replace("\tprepared","\tupdated"))
            DownloadSessionFiles.cookieFile(first,"",dir)
            assertTrue(f.readText().contains("\tupdated"))
            DownloadSessionFiles.cookieFile(snapshot("new"),"",dir)
            assertTrue(f.readText().contains("\tnew"));assertFalse(f.readText().contains("\tupdated"))
            assertFalse(DownloadSessionFiles.downloaderInfo(first).contains("_app_cookie_jar"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun serverWaitIsRespectedWithoutAnEarlyRetry() {
        val r=DownloadRateLimitRecovery();r.respectRetryAfter(121_000)
        assertEquals(120_000L,r.nextDelay("HTTP Error 429",1000))
        val long=DownloadRateLimitRecovery();long.respectRetryAfter(1_000_000)
        assertNull(long.nextDelay("HTTP Error 429",1000));assertTrue(long.serverWaitTooLong);assertTrue(long.message().contains("Retry-After"))
        assertEquals(0,long.retries)
    }
}
