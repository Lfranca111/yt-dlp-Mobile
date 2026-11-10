package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
class ExtractorPreflightFallbackTest {
 private val original="https://provider.example/video-123/original-title?quality=hd&token=PRIVATE"
 private fun response(html: String="<title>Video</title>")=PageRedirectPolicy.Response(200,html=html,contentType="text/html")
 @Test fun pathQueryAndRegionalDomainChangesReturnExactOriginalToNormalExtractor() {
  for(target in listOf("https://provider.example/watch/123/canonical-title","https://provider.example/video-123/original-title?quality=hd","https://region-provider.example/watch/123?locale=en")) {
   val logs=mutableListOf<String>()
   val r=PageRedirectPolicy.resolve(original,{ url,_-> if(url==original) PageRedirectPolicy.Response(302,target) else response() },log=logs::add,extractorFallback=true)
   assertEquals(original,r.target);assertEquals(original,r.original);assertTrue(r.extractorOnly);assertFalse(r.verified);assertEquals("",r.handler)
   assertTrue(logs.any { "normal yt-dlp using original submitted URL" in it });assertFalse(logs.joinToString().contains("PRIVATE"))
   assertNull(WebsiteDomains.site(target))
  }
 }
 @Test fun loopsMissingLocationsAndUnusableDestinationsDoNotVetoNormalExtraction() {
  for(dest in listOf(original,"","https://user:password@other.example/a","javascript:invalid","http://other.example/video")) {
   val r=PageRedirectPolicy.resolve(original,{ _,_->PageRedirectPolicy.Response(302,dest) },extractorFallback=true)
   assertEquals(original,r.target);assertTrue(r.extractorOnly);assertFalse(r.verified)
  }
 }
 @Test fun aVerifiedGenericRedirectStillBelongsToTheNormalExtractor() {
  val raw="https://generic-original.example/creator-name";val target="https://generic-new.example/creator-name"
  val r=PageRedirectPolicy.resolve(raw,{ url,_->if(url==raw) PageRedirectPolicy.Response(302,target) else response("<title>Creator Name videos</title>") },extractorFallback=true)
  assertEquals(raw,r.target);assertTrue(r.extractorOnly);assertFalse(r.verified);assertNull(WebsiteDomains.site(target))
 }
 @Test fun timeoutAndIncompletePageReachNormalExtraction() {
  val r=PageRedirectPolicy.resolve(original,{ _,_->response("<title>Loading title...</title><main><h1>Loading...</h1></main>") },renderProgress={ _,_->throw PageInspectionIncomplete("timeout") },extractorFallback=true)
  assertEquals(original,r.target);assertTrue(r.extractorOnly)
  val error=PageRedirectPolicy.forExtractor(original,{false},{}) { throw java.net.SocketTimeoutException("PRIVATE") }
  assertEquals(original,error.target);assertTrue(error.extractorOnly)
 }
 @Test fun knownDomainWithoutMatchingPageMustNotForceItsDedicatedHandler() {
  val raw="https://aniwaves.ru/other-page"
  val r=PageRedirectPolicy.resolve(raw,{ _,_->response("<title>Other page</title>") },extractorFallback=true)
  assertTrue(r.extractorOnly);assertEquals("",r.handler);assertEquals(raw,r.target)
 }
 @Test fun aVerifiedDedicatedMatchStillUsesItsExistingHandler() {
  val raw="https://aniwaves.ru/watch/example-77548/ep-2?audio=dub&server=Vidplay"
  val target="https://verified-new-146.example/watch/example-77548/ep-2"
  val r=PageRedirectPolicy.resolve(raw,{ url,_->if(url==raw) PageRedirectPolicy.Response(302,target) else response("<title>Example Episode 2</title><div class='layout-page-watchtv' data-id='77548'></div>") },extractorFallback=true)
  assertTrue(r.verified);assertFalse(r.extractorOnly);assertEquals("AniWaves",r.handler)
  assertTrue(r.target.startsWith(target));assertTrue(r.target.contains("audio=dub"));assertTrue(r.target.contains("server=Vidplay"))
 }
 @Test fun conflictingDedicatedRedirectReturnsOriginalWithoutAdoptingTheOtherEpisode() {
  val raw="https://aniwaves.ru/watch/example-77548/ep-2"
  val r=PageRedirectPolicy.resolve(raw,{ url,_->if(url==raw) PageRedirectPolicy.Response(302,"https://other-146.example/watch/example-77548/ep-3") else response("<div class='layout-page-watchtv' data-id='77548'></div>") },extractorFallback=true)
  assertEquals(raw,r.target);assertTrue(r.extractorOnly);assertFalse(r.verified);assertNull(WebsiteDomains.site("https://other-146.example/"))
 }
 @Test fun cancellationAndInvalidUserInputAreNotSwallowed() {
  try { PageRedirectPolicy.resolve(original,{ _,_->throw CancellationException("stop") },extractorFallback=true);fail() } catch(_: CancellationException) {}
  try { PageRedirectPolicy.forExtractor(original,{true},{}) { error("Must not inspect") };fail() } catch(_: CancellationException) {}
  try { PageRedirectPolicy.forExtractor("file:///invalid",{false},{}) { error("Must not inspect") };fail() } catch(_: IllegalArgumentException) {}
 }
 @Test fun directMediaRemainsOriginalAndUsesNormalExtractor() {
  val raw="https://cdn.example/video.mp4?signature=PRIVATE"
  val r=PageRedirectPolicy.resolve(raw,{ _,_->error("Must not fetch") },extractorFallback=true)
  assertEquals(raw,r.target);assertTrue(r.extractorOnly)
 }
 @Test fun strictCustomSourceRedirectValidationRemainsAvailable() {
  try { PageRedirectPolicy.resolve(original,{ _,_->PageRedirectPolicy.Response(302,"/different-page") });fail() } catch(_: PageRedirectPolicy.Blocked) {}
 }
}
