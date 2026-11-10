package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class H123WebsiteServersTest {
    private val map="""v=Object.freeze({1:{movie:"https://moviesapi.to/movie/",tv:"https://moviesapi.to/tv/"},2:{movie:"https://vidcore.io/movie/",tv:"https://vidcore.io/tv/"},3:{movie:"https://yapgrid.com/embed/movie/",tv:"https://yapgrid.com/embed/tv/"}}),g=Object.freeze({1:{},2:{autoPlay:"true",sub:"en"},3:{autoplay:"1",lang:"en"}});"""
    @Test fun websiteNumbersAreNotInternalBackendIndices() {
        val servers=H123ServerConfig.parse(map,"movie/251516")
        assertEquals(3,servers.size)
        assertEquals("https://yapgrid.com/embed/movie/251516?autoplay=1&lang=en",servers[2].url)
        assertEquals("https://vidcore.io/tv/251516/2/4?autoPlay=true&sub=en",H123ServerConfig.parse(map,"tv/251516/2/4")[1].url)
    }
    @Test fun rejectForeignHostsCredentialsQueriesAndMalformedContent() {
        assertTrue(H123ServerConfig.parse(map.replace("moviesapi.to","moviesapi.to.evil.invalid").replace("vidcore.io","u:p@vidcore.io").replace("yapgrid.com/embed/movie/","yapgrid.com/embed/movie/?bad="),"movie/4").isEmpty())
        try { H123ServerConfig.parse(map,"movie/4/../../5");fail() } catch(_: IllegalArgumentException) {}
    }
    private fun resolver(log: MutableList<String>,nativeCalls: MutableList<String>,capture: (WebsiteSourceResolver.Media)->WebsiteSourceResolver.Media): WebsiteSourceResolver = WebsiteSourceResolver({ url,_,_->when {
        url.endsWith("/config.js") -> map
        url.contains("moviesapi.to/") -> { nativeCalls.add(url);throw IllegalStateException("Website HTTP 502") }
        else -> "<title>Movie</title><script src='/config.js'></script>"
    } },log=log::add,browserPlayback=capture)
    @Test fun explicitServerThreeUsesWebsiteProviderNotMoviesApiBackendThree() {
        val calls=mutableListOf<String>();val logs=mutableListOf<String>()
        val media=resolver(logs,calls) { seed -> assertEquals(3,seed.playerServer);assertEquals("yapgrid.com",java.net.URI(seed.playerPage).host);seed.copy(url="https://media.example/full.m3u8",browserResolved=true) }
            .resolve("https://h123movies.com/movie/test-251516?ydlp_server=3")
        assertTrue(calls.isEmpty());assertTrue(media.browserResolved);assertTrue(logs.any { "mode=explicit" in it })
    }
    @Test fun nativeFailureStillReachesBackgroundForSameWebsiteServer() {
        val calls=mutableListOf<String>();val captured=mutableListOf<Int>()
        val media=resolver(mutableListOf(),calls) { seed -> captured.add(seed.playerServer);seed.copy(url="https://media.example/full.m3u8") }
            .resolve("https://h123movies.com/movie/test-251516?ydlp_server=1")
        assertEquals(listOf(1),captured);assertEquals(1,media.playerServer);assertTrue(calls.isNotEmpty())
    }
    @Test fun explicitFailureDoesNotSilentlyChooseAnotherWebsiteProvider() {
        val captured=mutableListOf<Int>()
        try { resolver(mutableListOf(),mutableListOf()) { captured.add(it.playerServer);error("Website HTTP 502") }.resolve("https://h123movies.com/movie/test-251516?ydlp_server=2");fail() } catch(_: IllegalStateException) {}
        assertEquals(listOf(2),captured)
    }
    @Test fun automaticMayAdvanceButCancellationNeverDoes() {
        val captured=mutableListOf<Int>()
        val m=resolver(mutableListOf(),mutableListOf()) { captured.add(it.playerServer);if(it.playerServer==1) error("Website HTTP 502");it.copy(url="https://media.example/full.m3u8") }.resolve("https://h123movies.com/movie/test-251516?ydlp_server=auto")
        assertEquals(listOf(1,2),captured);assertEquals(2,m.playerServer)
        captured.clear()
        try { resolver(mutableListOf(),mutableListOf()) { captured.add(it.playerServer);throw CancellationException("stop") }.resolve("https://h123movies.com/movie/test-251516");fail() } catch(_: CancellationException) {}
        assertEquals(listOf(1),captured)
    }
}
