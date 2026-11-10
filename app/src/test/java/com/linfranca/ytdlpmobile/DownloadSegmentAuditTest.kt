package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
class DownloadSegmentAuditTest {
 @Test fun recordsFragmentProgressAndRetries() {
  val a=DownloadSegmentAudit();a.observe("[download] 50% (frag 75/150)");a.observe("Retrying fragment 76 (1/10)")
  assertTrue(a.summary().contains("75/150"));assertTrue(a.summary().contains("retryEvents=1"));a.requireNoSkipped()
 }
 @Test fun skippedFragmentsCannotBecomeAVerifiedCompleteDownload() {
  val a=DownloadSegmentAudit();a.observe("[download] fragment not found; Skipping fragment 76 ...")
  try { a.requireNoSkipped();fail() } catch(_: IllegalStateException) {}
 }
}
