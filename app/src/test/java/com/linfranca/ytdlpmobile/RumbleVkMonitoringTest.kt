package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RumbleVkMonitoringTest {
    private val creator="https://rumble.com/user/ItsJinx"
    private val post="https://rumble.com/v7hzr8k-3-its-dangerous-to-go-alone-lets-play-oot-remake.html"
    private fun listing(vararg rows: JSONObject)=JSONObject().put("extractor_key","RumbleChannel")
        .put("id","ItsJinx").put("webpage_url",creator).put("entries",JSONArray(rows.toList()))
    private fun row(path: String,status: String="",owner: String="")=JSONObject().put("url",path)
        .put("live_status",status).put("channel_url",owner).put("title","Example")

    @Test fun metadata403IsSpecificToRumbleAndPreservesBrowserGuidance() {
        val failure="ERROR: [RumbleEmbed] v7fteb2: Unable to download JSON metadata: HTTP Error 403: Forbidden"
        assertTrue(requireNotNull(RumbleMonitoring.accessHelp(post,failure,false)).contains("signed-in Rumble session"))
        assertTrue(requireNotNull(RumbleMonitoring.accessHelp(post,failure,true)).contains("Retry this item"))
        assertNull(RumbleMonitoring.accessHelp(post,"[download] HTTP Error 403: Forbidden",false))
        assertNull(RumbleMonitoring.accessHelp("https://example.org",failure,false))
    }

    @Test fun listingRejectsOtherCreatorsActiveStreamsAndWrongExtractor() {
        val j=listing(row(post+"?share=1","was_live",creator),row("/v123-live.html","is_live",creator),
            row("/v234-foreign.html","not_live","/user/SomeoneElse"))
        val items=RumbleMonitoring.fromExtractor(creator,j)
        assertEquals(listOf("Capture:RUMBLE:v7hzr8k"),items.map { it.id })
        assertEquals(post,items.single().url)
        j.put("extractor_key","Generic")
        assertTrue(RumbleMonitoring.fromExtractor(creator,j).isEmpty())
    }

    @Test fun creatorCardsCanConfirmFlatEntriesWithoutTrustingRecommendations() {
        val html="""
            <link rel="canonical" href="$creator">
            <main><ul>
            <li><a href="/v111-old.html" class="videostream__link">Finished</a><span class="videostream__duration">9:29:36</span></li>
            <li><a href="/v222-now.html" class="video-item--a">Live</a><a href="/user/ItsJinx">ItsJinx</a><span class="video-item--status">LIVE</span></li>
            <li><a href="/v333-off.html" class="video-item--a">Other creator</a><a href="/user/Other">Other</a><span class="duration">02:30</span></li>
            <li><a href="/v444-owned.html" class="video-item--a">Owned</a><a href="/user/ItsJinx">ItsJinx</a><span class="duration">08:09</span></li>
            </ul><aside><article><a href="/v555-suggested.html" class="videostream__link">Suggested</a><span class="duration">02:30</span></article></aside></main>
        """.trimIndent()
        val entries=listing(row("/v111-old.html"),row("/v555-suggested.html"))
        assertEquals(listOf("Capture:RUMBLE:v111","Capture:RUMBLE:v444"),
            RumbleMonitoring.fromPage(creator,html,entries).map { it.id })
        assertTrue(RumbleMonitoring.fromPage(creator,html.replace("canonical","wrong"),entries).isEmpty())
    }

    @Test fun vkApiRequiresLivePublicAccessAndAnExactDashVideoId() {
        val primary="https://a.okcdn.ru/cmaf/12345/get/dash_12345.a.mpd"
        val backup="https://b.okcdn.ru/cmaf/12345/get/dash_12345.b.mpd"
        fun manifest(url: String)=JSONObject().put("type","live_cmaf").put("url",url)
        val state=JSONObject().put("isOnline",true).put("isPublic",true).put("hasAccess",true).put("data",JSONArray().put(
            JSONObject().put("vid","12345").put("playerUrls",JSONArray().put(manifest(primary))
                .put(manifest("https://a.okcdn.ru/get/dash_99999.a.mpd")))
                .put("sharedPlayerUrls",JSONArray().put(manifest(backup)))))
        val permitted: (String)->Boolean={ java.net.URI(it).host?.endsWith(".okcdn.ru")==true }
        assertEquals(listOf(primary,backup),VkLivePlayback.fromStream(state,true,permitted)?.urls)
        assertNull(VkLivePlayback.fromStream(state.put("hasAccess",false),true,permitted))
        assertNull(VkLivePlayback.fromStream(state.put("hasAccess",true).put("isOnline",false),false,permitted))
    }

    @Test fun vkRequestContextAndFailureDiagnosticsDoNotExposeSignedAddresses() {
        val page="https://live.vkvideo.ru/brm?unneeded=secret"
        val media="https://a.okcdn.ru/dash_12345.mpd"
        assertEquals(mapOf("Referer" to "https://live.vkvideo.ru/","User-Agent" to "agent","Cache-Control" to "no-cache"),
            VkLivePlayback.requestHeaders(page,media,"agent"))
        val trace=VkDashStartupTrace()
        trace.observe("DASH request for url 'https://a.okcdn.ru/init-1.m4s?token=secret'")
        trace.observe("HTTP error 403 Forbidden")
        assertTrue(trace.summary().contains("initialization segment: HTTP 403"))
        assertFalse(trace.summary().contains("secret"))
        assertFalse(trace.summary().contains("okcdn"))
        val nativeTrace=VkDashStartupTrace()
        nativeTrace.observe("DASH request for url 'https://a.okcdn.ru/get/opaque'")
        nativeTrace.observe("Failed to open an initialization section")
        nativeTrace.observe("Error opening input: Connection timed out")
        assertTrue(nativeTrace.summary().contains("initialization segment: request timed out"))
    }

    @Test fun cookieSnapshotsUseFreshImportAndDownloaderWritesCannotChangeIt() {
        val folder=kotlin.io.path.createTempDirectory().toFile()
        try {
            val source=java.io.File(folder,"import.txt").apply { writeText("# Netscape HTTP Cookie File\nfirst\n") }
            val jobs=java.io.File(folder,"jobs")
            val first=MonitoringDownloadCookies.copy(source,jobs)
            assertArrayEquals(source.readBytes(),first.readBytes())
            first.appendText("downloader mutation\n")
            assertFalse(source.readText().contains("mutation"))
            source.writeText("# Netscape HTTP Cookie File\nnew\n")
            val retry=MonitoringDownloadCookies.copy(source,jobs)
            assertNotEquals(first,retry)
            assertArrayEquals(source.readBytes(),retry.readBytes())
        } finally { folder.deleteRecursively() }
    }
}
