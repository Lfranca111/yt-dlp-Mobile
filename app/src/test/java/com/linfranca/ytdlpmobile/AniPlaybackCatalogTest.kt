package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
class AniPlaybackCatalogTest {
 private val page="https://aniwaves.ru/watch/show-77/ep-1"
 private fun result(html: String)=JSONObject().put("result",html).toString()
 private fun options()="<div class='type' data-type='sub'><label>SUB</label>"+(1..4).joinToString("") { "<li data-link-id='s$it'>New $it</li>" }+"</div><div class='type' data-type='ssub'><label>S-Sub</label><li data-link-id='soft'>MyCloud</li></div><div class='type' data-type='dub'><label>DUB</label><li data-link-id='dub'>Dub New</li></div>"
 @Test fun discoversEpisodeGroupsAndNewServerNamesWithoutAnEnumeration() {
  val groups=AniPlaybackCatalog.parse(options())
  assertEquals(listOf("sub","ssub","dub"),groups.map { it.key })
  assertEquals(listOf("New 1","New 2","New 3","New 4"),groups[0].servers.map { it.name })
  assertEquals("MyCloud",groups[1].servers.single().name)
 }
 @Test fun explicitSubOverridesDefaultDubAndSoftSubIsPreserved() {
  assertEquals("sub",AniPlaybackCatalog.audio("sub","dub"))
  assertEquals("ssub",AniPlaybackCatalog.audio("ssub","dub"))
  assertEquals("dub",AniPlaybackCatalog.audio("","dub"))
 }
 @Test fun newDomainUsesPlayerStructureAndNotDomainSpelling() {
  val screen=AniPlaybackCatalog.player("https://brand-new.example/embed/episode","<div id='screen' data-id='fresh'></div>")
  assertEquals("screen-getSources",screen.kind);assertEquals("https://brand-new.example/embed/getSources?id=fresh",screen.endpoint)
  val mg=AniPlaybackCatalog.player("https://different.example/embed/episode","<div id='mg-player' data-id='fresh'></div><script>fetch('getSources?id='+x);jwplayer('mg-player')</script>")
  assertEquals("jw-mg-getSources",mg.kind)
  for(html in listOf("<title>Vidplay</title>","<video src='ad.mp4'></video>","<div id='mg-player' data-id='x'></div>")) {
   try { AniPlaybackCatalog.player("https://different.example/a",html);fail() } catch(_: IllegalStateException) {}
  }
 }
 @Test fun qualityResponseIsScopedAndNotArbitraryRecursiveUrlScraping() {
  val response=JSONObject().put("1171",JSONObject().put("HD",JSONArray().put("https://cdn.example/hd.mp4")).put("SD",JSONArray().put("https://cdn.example/sd.mp4"))).put("ads",JSONObject().put("url","https://ads.example/ad.mp4"))
  assertEquals(listOf("https://cdn.example/hd.mp4","https://cdn.example/sd.mp4"),AniPlaybackCatalog.sources(response,"jw-mg-getSources"))
  assertTrue(AniPlaybackCatalog.sources(response,"screen-getSources").isEmpty())
  response.put("999",JSONObject())
  try { AniPlaybackCatalog.sources(response,"jw-mg-getSources");fail() } catch(_: IllegalStateException) {}
 }
 @Test fun currentMgQualityMapIsSupported() {
  val response=JSONObject().put("sources",JSONObject().put("HD",JSONArray().put("https://cdn.example/hd.mp4")).put("SD",JSONArray().put("https://cdn.example/sd.mp4")))
  assertEquals(listOf("https://cdn.example/hd.mp4","https://cdn.example/sd.mp4"),AniPlaybackCatalog.sources(response,"jw-mg-getSources"))
 }
 @Test fun automaticCanReachFourthServerAndFailureLogsDescribeStage() {
  val logs=mutableListOf<String>();val attempted=mutableListOf<String>()
  val resolver=WebsiteSourceResolver({ url,_,_-> when {
   "/ajax/server/list" in url -> result(options())
   "/ajax/sources" in url -> { val token=url.substringAfter("id=").substringBefore('&');attempted+=token;JSONObject().put("result",JSONObject().put("url","https://new-player.example/$token")).toString() }
   "/getSources" in url -> JSONObject().put("sources","https://cdn.example/video.m3u8").toString()
   "new-player.example/s4" in url -> "<div id='screen' data-id='fresh'></div>"
   "new-player.example" in url -> "<html>Unknown player</html>"
   else -> "<div class='layout-page-watchtv' data-id='77'></div>"
  } },log=logs::add)
  assertEquals("https://cdn.example/video.m3u8",resolver.resolve(page).url)
  assertEquals(listOf("s1","s2","s3","s4"),attempted)
  assertTrue(logs.any { "stage=player-inspection" in it && "unsupported-player-structure" in it })
  assertFalse(logs.joinToString().contains("id=fresh"))
 }
 @Test fun explicitFailureDoesNotSubstituteAnotherServerOrAudio() {
  val attempts=mutableListOf<String>()
  val resolver=WebsiteSourceResolver({ url,_,_-> when {
   "/ajax/server/list" in url -> result(options())
   "/ajax/sources" in url -> { attempts+=url.substringAfter("id=").substringBefore('&');JSONObject().put("result",JSONObject().put("url","https://new.example/player")).toString() }
   "new.example" in url -> "unsupported"
   else -> "<div class='layout-page-watchtv' data-id='77' data-ep-type='dub'></div>"
  } })
  try { resolver.resolve("$page?audio=sub&server=New+2");fail() } catch(_: IllegalStateException) {}
  assertEquals(listOf("s2"),attempts)
 }
 @Test fun chooserReadsFreshEpisodeResponseAndMonitoringUsesRepresentativeEpisode() {
  val calls=mutableListOf<String>()
  val catalog=AniPlaybackCatalog.load("https://aniwaves.ru/watch/show-77") { url,_-> calls+=url;when {
   "/ajax/episode/list" in url -> result("<a data-num='12'>Episode</a>")
   "/ajax/server/list" in url -> result(options())
   else -> "<div class='layout-page-watchtv' data-id='77'></div>"
  } }
  assertEquals("12",catalog.episode);assertTrue(catalog.representative)
  assertTrue(calls.last().endsWith("eps=12"))
 }
 @Test fun softSubMonitoringDoesNotTurnIntoHardSubOrDub() {
  val items=WebsiteSourceResolver.listing("https://aniwaves.ru/watch/show-77?audio=ssub&server=MyCloud","<div class='episodes'><a href='/watch/show-77/ep-1' data-num='1' data-sub='0' data-dub='0' data-ssub='1'>Episode</a></div>")
  assertEquals(1,items.size);assertTrue(items[0].url.contains("audio=ssub"));assertTrue(items[0].id.contains(":ssub"))
 }
}
