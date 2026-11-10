package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
class PlaybackChoiceTest {
 @Test fun preferencesStayWithinShowAndDoNotCrossToAnotherShow() {
  assertEquals(PlaybackChoice.key("https://aniwaves.ru/watch/show-77/ep-1"),PlaybackChoice.key("https://aniwaves.ru/watch/show-77/ep-2?audio=dub"))
  assertNotEquals(PlaybackChoice.key("https://aniwaves.ru/watch/show-77/ep-1"),PlaybackChoice.key("https://aniwaves.ru/watch/other-88/ep-1"))
 }
 @Test fun changingSelectionRemovesOnlyAppPlaybackHints() {
  assertEquals("https://aniwaves.ru/watch/show-77/ep-2?keep=a%2Fb#player",PlaybackChoice.clean("https://aniwaves.ru/watch/show-77/ep-2?audio=dub&keep=a%2Fb&server=Vidplay#player"))
  assertEquals("https://h123movies.com/movie/example?keep=1",PlaybackChoice.clean("https://h123movies.com/movie/example?ydlp_server=3&keep=1"))
 }
 @Test fun audioAndServerChoicesHaveDistinctVariantIdentities() {
  assertNotEquals(PlaybackChoice.Choice("sub","Vidplay").variant(),PlaybackChoice.Choice("dub","Vidplay").variant())
  assertNotEquals(PlaybackChoice.Choice("dub","Vidplay").variant(),PlaybackChoice.Choice("dub","BYFMS").variant())
 }
 @Test fun mainAndBrowserChoicesShareStableDuplicateIdentity() {
  val page="https://aniwaves.ru/watch/show-77/ep-2"
  assertEquals(PlaybackChoice.identityUrl("$page?audio=dub&server=Vidplay"),PlaybackChoice.identityUrl(page,"dub:vidplay"))
  assertNotEquals(PlaybackChoice.identityUrl(page,"sub:vidplay"),PlaybackChoice.identityUrl(page,"dub:vidplay"))
  assertEquals(PlaybackChoice.identityUrl("https://h123movies.com/movie/one?ydlp_server=3"),PlaybackChoice.identityUrl("https://h123movies.com/movie/one",":server 3"))
 }
}
