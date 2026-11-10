package com.linfranca.ytdlpmobile
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
class Presentation143Test {
 @Test fun progressBurstIsBoundedButTerminalEventsAreImmediate() {
  val gate=ProgressPresentation(300)
  assertEquals(4,(0L..999L).count { gate.allow(it) })
  assertTrue(gate.allow(1000,true));assertFalse(gate.allow(1001))
 }
 @Test fun logsRetainErrorsAndCompletion() {
  val gate=ProgressLogPresentation()
  assertTrue(gate.allow("[download] 1% ETA 3s",0))
  assertFalse(gate.allow("[download] 2% ETA 3s",1))
  assertTrue(gate.allow("ERROR: fragment 8 not found",2))
  assertTrue(gate.allow("[download] 100% of 1MiB in 00:01",3))
 }
 @Test fun queueProgressDoesNotRebuildHistoryButStateDoes() {
  val j=JSONObject().put("id","a").put("state","running").put("detail","Downloading 0%")
  val before=QueuePresentation.signature(listOf(j))
  j.put("detail","Downloading 30%").put("progress",30).put("log","more")
  assertEquals(before,QueuePresentation.signature(listOf(j)))
  j.put("state","attention");assertNotEquals(before,QueuePresentation.signature(listOf(j)))
 }
 @Test fun probeSettingsAndVersionHaveNoPrivateValues() {
  assertEquals(listOf("-probesize","50000000","-analyzeduration","60000000"),MediaProbePolicy.inputArguments)
  assertEquals("6.1.1",MediaProbePolicy.version("ffmpeg version 6.1.1 Copyright /private/path"))
  assertEquals("unavailable",MediaProbePolicy.version("private startup failure"))
  assertTrue(PostprocessRecoveryPolicy.markers("Could not write header: sample rate not set, 0 channels").containsAll(listOf("mux_header_failed","audio_sample_rate_missing","audio_channels_missing")))
 }
}
