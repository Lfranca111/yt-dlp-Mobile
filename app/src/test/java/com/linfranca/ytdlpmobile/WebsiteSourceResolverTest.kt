package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CancellationException

class WebsiteSourceResolverTest {
    private fun result(html: String)=JSONObject().put("status",200).put("result",html).toString()
    @Test fun aniResolvesFreshIdentityForDifferentShows() {
        val seen=mutableListOf<String>()
        fun resolve(show: String,ep: String): WebsiteSourceResolver.Media {
            val handler=WebsiteSourceResolver({ url,_,_->
                seen+=url
                when {
                    "/ajax/server/list?" in url -> result("<div class='type' data-type='sub'><li data-link-id='fresh-$show-$ep'>Player</li></div>")
                    "/ajax/sources?" in url -> JSONObject().put("result",JSONObject().put("url","https://play.echovideo.ru/embed-1/$show-$ep")).toString()
                    "getSources?" in url -> JSONObject().put("sources","https://media.echovideo.to/$show-$ep.m3u8").toString()
                    "play.echovideo.ru" in url -> "<div id='screen' data-id='$show-$ep'></div>"
                    else -> "<title>Show $show episode $ep</title><div class='layout-page-watchtv' data-id='$show' data-ep-name='$ep'></div>"
                }
            })
            return handler.resolve("https://aniwaves.ru/watch/example-$show/ep-$ep")
        }
        val a=resolve("77","5");val b=resolve("88","9")
        assertEquals("aniwaves:77:5:sub",a.id);assertEquals("aniwaves:88:9:sub",b.id)
        assertNotEquals(a.url,b.url)
        assertTrue(seen.any { "servers=88&eps=9" in it })
    }
    @Test fun aniMonitoringStaysInsideSeriesAndIgnoresUnavailableEpisodes() {
        val html="""<div class='layout-page-watchtv' data-id='77'></div><div class='episodes'>
            <a href='/watch/77/ep-2' data-num='2' data-ids='77&amp;eps=2' data-sub='1'>Two</a>
            <a href='/watch/88/ep-3' data-num='3' data-ids='88&amp;eps=3' data-sub='1'>Other show</a>
            <a href='/watch/77/ep-4' data-num='4' data-ids='77&amp;eps=4' enabled='0'>Unavailable</a></div>"""
        val items=WebsiteSourceResolver.listing("https://aniwaves.ru/watch/one-77/ep-1",html)
        assertEquals(1,items.size);assertEquals("aniwaves:77:2:sub",items.single().id)
    }
    @Test fun aniDubMonitoringUsesSeparateIdentityAndDoesNotSubstituteSub() {
        val html="""<div class='episodes'><a href='/watch/77/ep-2' data-num='2' data-ids='77' data-sub='1' data-dub='1'>Dub</a>
            <a href='/watch/77/ep-3' data-num='3' data-ids='77' data-sub='1' data-dub='0'>Sub only</a></div>"""
        val items=WebsiteSourceResolver.listing("https://aniwaves.ru/watch/one-77?audio=dub",html)
        assertEquals("aniwaves:77:2:dub",items.single().id)
        assertTrue(items.single().url.contains("audio=dub"));assertTrue(items.single().url.contains("server=auto"))
    }
    @Test fun wcoDecodesPlayerWithoutExecutingJavascriptAndChoosesAvailableQuality() {
        val iframe="<iframe src='https://embed.wcostream.com/inc/embed/video-js.php?file=fixture'></iframe>"
        val offset=2468
        val encoded=JSONArray(iframe.map { Base64.getEncoder().encodeToString("abc${it.code+offset}xyz".toByteArray()) }).toString()
        val html="<title>Episode</title><script>var text=''; var a=$encoded; a.forEach(function(value){text += String.fromCharCode(parseInt(atob(value).replace(/\\D/g,'')) - $offset);});document.write(text);</script>"
        assertEquals(iframe,WebsiteSourceResolver.decodeWco(html))
        val media=WebsiteSourceResolver({ url,_,_-> when {
            "getvidlink.php" in url -> """{"server":"https://neptun.wcostream.com","fhd":"high","hd":"medium","enc":"low"}"""
            "/getvid?" in url -> JSONObject.quote("https://u33.wcostream.com/getvid?evid="+if("high" in url) "high" else "medium")
            "embed.wcostream.com" in url -> """<script>$.getJSON('/inc/embed/getvidlink.php?v=fixture',function(response){});</script>"""
            else -> html
        } },playable={ url,_->"high" !in url }).resolve("https://m.wcostream.tv/show-season-1-episode-2")
        assertTrue(media.url.endsWith("medium"));assertEquals("wcostream:show-season-1-episode-2",media.id)
    }
    @Test fun wcoMonitoringFollowsItsSeriesLinkAndExcludesRelatedContent() {
        val items=WebsiteSourceResolver({ url,_,_-> if("/anime/" in url) """<a href='/show-episode-2'>Episode two</a><aside class='sidebar'><a href='/other-episode-5'>Related</a></aside>"""
            else "<a href='/anime/show'>Show series</a>" }).discover("https://m.wcostream.tv/show-episode-1")
        assertEquals("wcostream:show-episode-2",items.single().id)
    }
    private val mapping="Object.freeze({0:\"m\",1:\"i\",2:\"2\",3:\"g\",4:\"7\",5:\"l\",6:\"b\",7:\"u\",8:\"4\",9:\"3\"})"
    @Test fun h123DecodesCurrentPageConfigurationInsteadOfSavingAnExampleId() {
        assertEquals("262504",WebsiteSourceResolver.decodeH123("2b2lm7",mapping))
        assertEquals("12345",WebsiteSourceResolver.decodeH123("i2g7l",mapping))
        assertEquals("",WebsiteSourceResolver.decodeH123("bad-token","changed mapping"))
    }
    @Test fun h123UsesAlternateExposedServerAndFreshPlayerConfiguration() {
        var sentKey=false
        val media=WebsiteSourceResolver({ url,_,headers-> when {
            url.endsWith("/js/script.js") -> "var harmless=true;"
            url.endsWith("/js/detail-bundle.js") -> "$mapping; var movie='https://moviesapi.to/movie/'"
            url.endsWith("moviesapi.to/assets/main.js") -> "var player='https://cdn.vidspark.to'"
            url.endsWith("vidspark.to/assets/main.js") -> "var testKey=`fixture-key`; var options={headers:{\"x-player-key\":testKey}};var servers=[{key:`alpha`,label:`One`},{key:`beta`,label:`Two`}]"
            "/api/vidora/v1/" in url -> { sentKey=headers["x-player-key"]=="fixture-key";JSONObject().put("result",true).put("title","Fixture movie").put("sources",JSONArray().put(JSONObject().put("url","https://media.example/"+if("alpha" in url) "unavailable.m3u8" else "playable.m3u8"))).toString() }
            "moviesapi.to/movie/" in url || "vidspark.to/movie/" in url -> "<script src='/assets/main.js'></script>"
            else -> "<title>Loading title...</title><script src='/js/script.js'></script><script src='/js/detail-bundle.js'></script>"
        } },playable={ url,_->!url.contains("unavailable") }).resolve("https://h123movies.com/movie/example-2b2lm7")
        assertTrue(sentKey);assertEquals("h123:movie:262504",media.id);assertTrue(media.url.endsWith("playable.m3u8"))
    }
    @Test fun h123MoviePageIsNotTreatedAsAChannelOfRecommendations() {
        val html="<article class='movie-card'><a class='movie-card-link' href='/movie/other-i'>Other</a></article>"
        assertTrue(WebsiteSourceResolver.listing("https://h123movies.com/movie/example-i",html).isEmpty())
        assertEquals(1,WebsiteSourceResolver.listing("https://h123movies.com/movies",html).size)
    }
    @Test fun h123SeriesEpisodeLinksAreScopedToTheSelectedShow() {
        val html="""<a data-episode='2' href='/series/one-i/season-1/episode-2'>Two</a>
            <a data-episode='3' href='/series/two-2/season-1/episode-3'>Wrong show</a>"""
        assertEquals(1,WebsiteSourceResolver.listing("https://h123movies.com/series/one-i",html).size)
    }
    @Test fun cancellationNeverBecomesAnEmptySuccessfulListing() {
        var calls=0
        try { WebsiteSourceResolver({ _,_,_->calls++;"" },stopped={true}).discover("https://aniwaves.ru/watch/one-77");fail() }
        catch(_: CancellationException) { assertEquals(0,calls) }
    }
    @Test fun cookieScopesDoNotLeakAcrossHostsPathsOrExpiry() {
        val lines=listOf(".example.com\tTRUE\t/account\tTRUE\t1000\tvalid\tyes", "example.com\tFALSE\t/\tTRUE\t0\thost\tonly", ".example.com\tTRUE\t/\tTRUE\t5\texpired\tno")
        assertEquals("valid=yes",WebsiteHandlerBridge.cookieHeader(lines,"https://sub.example.com/account/item",10))
        assertEquals("",WebsiteHandlerBridge.cookieHeader(lines,"https://other.test/account",10))
        assertEquals("",WebsiteHandlerBridge.cookieHeader(lines,"http://sub.example.com/account",10))
        assertEquals("",WebsiteHandlerBridge.cookieHeader(lines,"https://sub.example.com/accounting",10))
    }
    @Test fun aniChecksNewestTwoHundredEvenWhenEpisodeListIsOldestFirst() {
        val html="<div class='episodes'>"+(1..250).joinToString("") { "<a href='/watch/77/ep-$it' data-num='$it' data-ids='77' data-sub='1'>Episode $it</a>" }+"</div>"
        val items=WebsiteSourceResolver.listing("https://aniwaves.ru/watch/show-77",html)
        assertEquals(200,items.size);assertEquals("aniwaves:77:250:sub",items.first().id)
        assertEquals("aniwaves:77:51:sub",items.last().id)
    }
    @Test fun selectedIndividualItemsCanUseHandlerWithPlaylistCheckboxEnabled() {
        assertTrue(WebsiteSourceResolver.individual("https://h123movies.com/movie/test-i"))
        assertTrue(WebsiteSourceResolver.individual("https://aniwaves.ru/watch/test-77/ep-3"))
        assertFalse(WebsiteSourceResolver.individual("https://aniwaves.ru/watch/test-77"))
        assertFalse(WebsiteSourceResolver.individual("https://h123movies.com/series/test-i"))
    }
    @Test fun hostRecognitionUsesExactHostsAndMobileAliases() {
        assertEquals("WCOStream",WebsiteSourceResolver.site("m.wcostream.tv/show-episode-1"))
        assertEquals("AniWaves",MonitoringPolicy.handler("https://aniwaves.ru/watch/one-77"))
        assertNull(WebsiteSourceResolver.site("https://aniwaves.ru.attacker.example/"))
    }
    @Test fun explicitAudioAndServerSelectDifferentSourcesWithoutSubstitution() {
        val calls=mutableListOf<String>()
        val resolver=WebsiteSourceResolver({ url,_,_->
            calls+=url
            when {
                "/ajax/server/list" in url -> result("<div class='type' data-type='sub'><li data-link-id='sub-v'>Vidplay</li><li data-link-id='sub-b'>BYFMS</li></div><div class='type' data-type='dub'><li data-link-id='dub-v'>Vidplay</li></div>")
                "/ajax/sources" in url -> JSONObject().put("result",JSONObject().put("url","https://play.echovideo.ru/"+url.substringAfter("id=").substringBefore('&'))).toString()
                "/getSources" in url -> JSONObject().put("sources","https://cdn.example/"+url.substringAfter("id=")+".m3u8").toString()
                "play.echovideo.ru" in url -> "<div id='screen' data-id='"+url.substringAfterLast('/')+"'></div>"
                else -> "<div class='layout-page-watchtv' data-id='77'></div>"
            }
        })
        val sub=resolver.resolve("https://aniwaves.ru/watch/show-77/ep-2?audio=sub&server=BYFMS")
        val dub=resolver.resolve("https://aniwaves.ru/watch/show-77/ep-2?audio=dub&server=Vidplay")
        assertTrue(sub.url.contains("sub-b"));assertTrue(dub.url.contains("dub-v"));assertNotEquals(sub.id,dub.id)
        val before=calls.count { "/ajax/sources" in it }
        try { resolver.resolve("https://aniwaves.ru/watch/show-77/ep-2?audio=dub&server=BYFMS");fail() } catch(_: IllegalStateException) {}
        assertEquals(before,calls.count { "/ajax/sources" in it })
    }
    @Test fun monitoringEntriesCarryTheirOwnPlaybackChoice() {
        val html="<div class='episodes'><a href='/watch/show-77/ep-2' data-num='2' data-ids='x' data-sub='1' data-dub='1'>Episode</a></div>"
        val item=WebsiteSourceResolver.listing("https://aniwaves.ru/watch/show-77?audio=dub&server=Vidplay",html).single()
        assertTrue(item.url.contains("audio=dub"));assertTrue(item.url.contains("server=Vidplay"));assertTrue(item.id.endsWith(":dub:Vidplay"))
    }
    private fun movieFailureFixture(response: String, logs: MutableList<String>, calls: MutableList<String>, cancel: Boolean=false): WebsiteSourceResolver = WebsiteSourceResolver({ url,_,_ -> when {
        url.endsWith("/js/main.js") -> "$mapping; var movie='https://moviesapi.to/movie/'"
        url.endsWith("moviesapi.to/assets/main.js") -> "var player='https://cdn.vidspark.to'"
        url.endsWith("vidspark.to/assets/main.js") -> "var testKey=`fixture-key`; var options={headers:{\"x-player-key\":testKey}};var servers=[{key:`alpha`,label:`One`},{key:`beta`,label:`Two`}]"
        "/api/vidora/v1/" in url -> { calls+=url;if(cancel) throw CancellationException("stop");response }
        "moviesapi.to/movie/" in url || "vidspark.to/movie/" in url -> "<script src='/assets/main.js'></script>"
        else -> "<title>Movie</title><script src='/js/main.js'></script>"
    } },log=logs::add)
    @Test fun failedExplicitMovieServerReportsItsStageWithoutSubstitution() {
        val logs=mutableListOf<String>();val calls=mutableListOf<String>()
        try {
            movieFailureFixture("""{"result":false,"private":"secret-value"}""",logs,calls).resolve("https://h123movies.com/movie/x-2b2lm7?ydlp_server=1")
            fail("Unavailable server must fail")
        } catch(e: IllegalStateException) { assertEquals("unavailable",WebsiteDownloadFailure.describe(e).kind) }
        assertEquals(1,calls.size);assertTrue(calls.single().contains("alpha"))
        assertTrue(logs.any { it.contains("server 1 failed") && it.contains("stage=player-response") })
        assertTrue(logs.any { it.contains("no other server substituted") })
        assertFalse(logs.any { it.contains("trying next exposed server") || it.contains("secret-value") })
    }
    @Test fun movieSourcesCanUseTheStandardFileFieldWithoutChangingServer() {
        val calls=mutableListOf<String>()
        val media=movieFailureFixture("""{"result":true,"sources":[{"file":"https://media.example/fixture.m3u8"}]}""",mutableListOf(),calls)
            .resolve("https://h123movies.com/movie/x-2b2lm7?ydlp_server=1")
        assertEquals("https://media.example/fixture.m3u8",media.url);assertEquals(1,calls.size)
    }
    @Test fun movieCancellationIsNotReportedAsAServerFailure() {
        val logs=mutableListOf<String>();val calls=mutableListOf<String>()
        try { movieFailureFixture("{}",logs,calls,true).resolve("https://h123movies.com/movie/x-2b2lm7?ydlp_server=1");fail() }
        catch(_: CancellationException) { }
        assertFalse(logs.any { it.contains("failed; stage=") })
    }

}
