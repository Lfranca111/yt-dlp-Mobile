package com.linfranca.ytdlpmobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class H123AudioLanguageTest {
 private fun f(id: String,language: String,video: Boolean=false,group: String="audio")=JSONObject().put("format_id",id).put("vcodec",if(video) "h264" else "none").put("acodec",if(video) "none" else "aac").put("language",language).put("_app_audio_group",group)
 @Test fun englishTagsAreRecognizedWithoutGuessingOtherLanguages() {
  listOf("en","eng","English","EN-us","en_GB").forEach { assertTrue(H123AudioLanguage.english(it)) }
  listOf("hi","fr","ta","enm","","English commentary").forEach { assertFalse(H123AudioLanguage.english(it)) }
 }
 @Test fun filtersFrenchAndHindiButKeepsEnglishAndMatchingVideo() {
  val info=JSONObject().put("formats",JSONArray(listOf(f("fr","fr"),f("en","en"),f("hi","hi"),f("v","",true),f("wrong-group","",true,"other"))))
  H123AudioLanguage.plan(info)
  val a=info.getJSONArray("formats")
  assertEquals(listOf("en","v"),(0 until a.length()).map { a.getJSONObject(it).getString("format_id") })
  assertTrue(info.getBoolean("_app_english_rendition_verified"))
 }
 @Test fun missingEnglishFailsWithoutSubstitution() {
  val info=JSONObject().put("formats",JSONArray(listOf(f("fr","fr"),f("v","",true))))
  try { H123AudioLanguage.plan(info);fail() } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("not available")) }
 }
 @Test fun embeddedUnknownStreamsWaitForRealStreamInspection() {
  val info=JSONObject().put("formats",JSONArray(listOf(JSONObject().put("vcodec","unknown").put("acodec","unknown"))))
  H123AudioLanguage.plan(info);assertFalse(info.optBoolean("_app_english_rendition_verified"))
  assertEquals(4,H123AudioLanguage.chosen(listOf(H123AudioLanguage.Track(1,"tam"),H123AudioLanguage.Track(4,"eng")),false))
  try { H123AudioLanguage.chosen(listOf(H123AudioLanguage.Track(1,"")),false);fail() } catch(_: IllegalStateException) {}
  assertEquals(1,H123AudioLanguage.chosen(listOf(H123AudioLanguage.Track(1,"")),true))
  try { H123AudioLanguage.chosen(listOf(H123AudioLanguage.Track(1,"fra")),true);fail() } catch(_: IllegalStateException) {}
 }
 @Test fun languageSelectionSurvivesRedirectAndHasItsOwnDuplicateIdentity() {
  assertTrue(PageRedirectPolicy.preserve("https://h123movies.com/movie/a?ydlp_audio=en&ydlp_server=3","https://h123movies.com/movie/a").contains("ydlp_audio=en"))
  assertEquals("https://h123movies.com/movie/a?keep=1",PlaybackChoice.clean("https://h123movies.com/movie/a?ydlp_audio=en&ydlp_server=3&keep=1"))
  assertEquals(PlaybackChoice.identityUrl("https://h123movies.com/movie/a?ydlp_audio=en&ydlp_server=3"),PlaybackChoice.identityUrl("https://h123movies.com/movie/a","en:server 3"))
  assertNotEquals(PlaybackChoice.identityUrl("https://h123movies.com/movie/a?ydlp_audio=en&ydlp_server=3"),PlaybackChoice.identityUrl("https://h123movies.com/movie/a?ydlp_server=3"))
 }
 @Test fun suppliedPlayerStyleMasterSelectsEnglishInsteadOfFrench() {
  val master="""#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="english"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Français",LANGUAGE="fr",DEFAULT=NO,URI="french"
#EXT-X-STREAM-INF:BANDWIDTH=5500000,RESOLUTION=1920x1080,AUDIO="audio"
video
"""
  val child="#EXTM3U\n#EXTINF:5,\nsegment.ts\n#EXT-X-ENDLIST"
  val plan=HlsDownloadPlan.build("https://media.invalid/master",master,"https://h123movies.com/movie/a","Example",{ emptyMap() }) { child }
  H123AudioLanguage.plan(plan)
  val formats=plan.getJSONArray("formats")
  assertEquals(2,formats.length())
  val audio=(0 until formats.length()).map { formats.getJSONObject(it) }.single { it.optString("vcodec")=="none" }
  assertEquals("en",audio.getString("language"));assertEquals("audio",audio.getString("_app_audio_group"))
 }
 @Test fun missingEnglishHasUsefulPrivateValueFreeFailure() {
  val reason=WebsiteDownloadFailure.describe(IllegalStateException("English audio is not available https://private.invalid/token"))
  assertEquals("audio-language",reason.kind)
  assertFalse(reason.detail.contains("private"))
 }
 @Test fun probeParsesOnlyAllowlistedLanguageFields() {
  val probe=FragmentDiagnosticCore.ProbeSummary()
  probe.line("stream|index=4|codec_type=audio|codec_name=aac")
  probe.line("stream|index=4|codec_type=audio|codec_name=aac|tag:language=eng|tag:title=Private value")
  val report=JSONObject().put("summary",probe.json())
  assertEquals(listOf(H123AudioLanguage.Track(4,"eng")),H123AudioLanguage.tracks(report))
  assertFalse(report.toString().contains("Private"))
 }
}
