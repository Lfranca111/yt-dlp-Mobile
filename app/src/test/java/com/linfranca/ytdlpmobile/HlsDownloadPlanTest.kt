package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test

class HlsDownloadPlanTest {
 private fun vod(count: Int=150,duration: Double=10.0)=buildString { append("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");repeat(count) { append("#EXTINF:$duration,\nsegment$it.jpg\n") };append("#EXT-X-ENDLIST\n") }
 @Test fun extensionlessAndImageNamedResponsesAreFullHls() {
  val info=HlsDownloadPlan.build("https://cdn.example/opaque","#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\nlevel.jpg\n","https://site.example/episode","Episode",{ emptyMap() }) { vod() }
  assertEquals(150,info.getInt("_app_expected_segments"));assertEquals(1500.0,info.getDouble("_app_expected_duration"),0.001)
  val f=info.getJSONArray("formats").getJSONObject(0)
  assertEquals("m3u8_native",f.getString("protocol"));assertEquals(1080,f.getInt("height"));assertEquals("https://cdn.example/level.jpg",f.getString("url"))
 }
 @Test fun directMediaPlaylistPreservesEverySegment() {
  val p=HlsDownloadPlan.parse("https://cdn.example/index.jpg",vod(1446,5.0));assertEquals(1446,p.segments);assertEquals(7230.0,p.duration,0.01);assertTrue(p.ended)
 }
 @Test fun incompletePlaylistIsNotReportedAsCompleteVod() {
  try { HlsDownloadPlan.build("https://cdn.example/stream",vod().replace("#EXT-X-ENDLIST", ""),"https://site.example/episode","Episode",{ emptyMap() }) { error("No child expected") };fail() } catch(_: IllegalStateException) {}
 }
 @Test fun signedQueriesAndRelativeChildrenArePreserved() {
  assertEquals("https://cdn.example/a/level.jpg?token=new",HlsDownloadPlan.resolve("https://cdn.example/a/master?token=old","level.jpg?token=new"))
  assertEquals("avc1.4d401f,mp4a.40.2",HlsDownloadPlan.attributes("#EXT-X-STREAM-INF:CODECS=\"avc1.4d401f,mp4a.40.2\",BANDWIDTH=20")["CODECS"])
 }
 @Test fun protectedEncryptionIsRejected() {
  try { HlsDownloadPlan.build("https://cdn.example/stream",vod().replace("#EXTM3U","#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\""),"https://site.example/episode","Episode",{ emptyMap() }) { "" };fail() } catch(_: IllegalStateException) {}
 }
 @Test fun declaredSeparateAudioHasExplicitTrackTypes() {
  val master="#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",LANGUAGE=\"en\",URI=\"audio.jpg\"\n#EXT-X-STREAM-INF:BANDWIDTH=10,AUDIO=\"a\",RESOLUTION=1280x720\nvideo.jpg\n"
  val formats=HlsDownloadPlan.build("https://cdn.example/master",master,"https://site.example/episode","Episode",{ emptyMap() }) { vod(3) }.getJSONArray("formats")
  assertEquals("none",formats.getJSONObject(0).getString("vcodec"));assertEquals("none",formats.getJSONObject(1).getString("acodec"))
 }
 @Test fun rejectsInvalidDurationsAndUnsafeResourceSchemes() {
  try { HlsDownloadPlan.parse("https://cdn.example/a",vod().replace("#EXTINF:10.0", "#EXTINF:NaN"));fail() } catch(_: IllegalArgumentException) {}
  try { HlsDownloadPlan.resolve("https://cdn.example/a","file:///etc/passwd");fail() } catch(_: IllegalArgumentException) {}
 }
 @Test fun unavailableAndShortPreviewVariantsDoNotBecomeTheProgram() {
  val master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=40\nmissing.jpg\n#EXT-X-STREAM-INF:BANDWIDTH=30\npreview.jpg\n#EXT-X-STREAM-INF:BANDWIDTH=20\nfull.jpg\n"
  val info=HlsDownloadPlan.build("https://cdn.example/master",master,"https://site.example/episode","Episode",{ emptyMap() }) {
   when { it.endsWith("missing.jpg") -> error("HTTP 403");it.endsWith("preview.jpg")->vod(36);else->vod(150) }
  }
  val f=info.getJSONArray("formats");assertEquals(1,f.length());assertTrue(f.getJSONObject(0).getString("url").endsWith("full.jpg"));assertEquals(1500.0,info.getDouble("_app_expected_duration"),0.01)
 }
}
