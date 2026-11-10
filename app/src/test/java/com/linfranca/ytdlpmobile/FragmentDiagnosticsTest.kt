package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class FragmentDiagnosticsTest {
    private fun temp(block: (File)->Unit) { val root=Files.createTempDirectory("fragment-diagnostics").toFile();try { block(root) } finally { root.deleteRecursively() } }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun inventoryDoesNotModifyFilesAndDoesNotExportSecrets()=temp { root ->
        val bytes=ByteArray(1000) { 3 };bytes[0]=71;bytes[188]=71;bytes[376]=71
        val key="a".repeat(64)
        val fragment=File(root,".download-cache/objects/$key.bin").apply { parentFile!!.mkdirs();writeBytes(bytes) }
        File(fragment.parentFile,"$key.json").writeText(JSONObject().put("bytes",bytes.size).put("sha256",sha(bytes)).put("cookie","TOP_SECRET_COOKIE").toString())
        File(root,"browser-selection.json").writeText("{\"url\":\"https://secret.invalid/?token=HIDDEN_TOKEN\"}")
        val assembled=File(root,"PRIVATE_TITLE.mp4").apply { writeBytes(bytes) }
        val snapshot=root.walkTopDown().filter { it.isFile }.associate { it to it.readBytes().toList() }
        val (items,complete)=FragmentDiagnosticCore.inventory(root,{false},Long.MAX_VALUE)
        assertTrue(complete)
        val row=items.first { it.file==fragment }.row
        assertTrue(row.getBoolean("receiptHashMatches"));assertEquals("mpegts",row.getString("kind"))
        val exported=JSONArray(items.map { it.row }).toString()
        for(secret in listOf("TOP_SECRET_COOKIE","HIDDEN_TOKEN","PRIVATE_TITLE","https://secret.invalid")) assertFalse(secret,exported.contains(secret))
        for((file,before) in snapshot) assertEquals(before,file.readBytes().toList())
        assertEquals(snapshot.keys,root.walkTopDown().filter { it.isFile }.toSet())
        assertTrue(assembled.exists())
    }
    @Test fun corruptReceiptIsReported()=temp { root ->
        val key="b".repeat(64);val file=File(root,".download-cache/objects/$key.bin").apply { parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3)) }
        File(file.parentFile,"$key.json").writeText(JSONObject().put("bytes",999).put("sha256","c".repeat(64)).toString())
        val row=FragmentDiagnosticCore.inventory(root,{false},Long.MAX_VALUE).first.first { it.file==file }.row
        assertFalse(row.getBoolean("receiptSizeMatches"));assertFalse(row.getBoolean("receiptHashMatches"))
    }
    @Test fun timeLimitLeavesInventoryAndMarksHashesIncomplete()=temp { root ->
        File(root,"video.mp4").writeBytes(byteArrayOf(1,2,3))
        val (items,complete)=FragmentDiagnosticCore.inventory(root,{false},1L) { 2L }
        assertFalse(complete);assertEquals(1,items.size);assertEquals("time_limit",items[0].row.getString("hashStatus"))
    }
    @Test fun canceledInspectionStopsWithoutChangingSources()=temp { root ->
        val f=File(root,"video.mp4").apply { writeBytes(byteArrayOf(1,2)) }
        try { FragmentDiagnosticCore.inventory(root,{true},Long.MAX_VALUE);fail("Expected cancellation") } catch(expected: InterruptedException) {}
        assertEquals(listOf<Byte>(1,2),f.readBytes().toList())
    }
    @Test fun packetSummaryCountsTimestampResetsAndOmitsUnexpectedFields() {
        val probe=FragmentDiagnosticCore.ProbeSummary()
        for(time in listOf("0.0","10.0","53.0","0.0","54.0")) probe.line("packet|stream_index=0|pts_time=$time|size=100|secret=TOP_SECRET")
        probe.line("stream|index=0|codec_type=video|codec_name=h264|duration=54.0|filename=/secret/PRIVATE_TITLE.mp4|tags=TOP_SECRET")
        val j=probe.json();val t=j.getJSONArray("packetTiming").getJSONObject(0)
        assertEquals(5,t.getInt("packets"));assertEquals(1,t.getInt("backwardJumpsOverOneSecond"));assertEquals(54.0,t.getDouble("maxTimestamp"),0.001)
        assertFalse(j.toString().contains("TOP_SECRET"));assertFalse(j.toString().contains("PRIVATE_TITLE"))
    }
    @Test fun snapshotRestoresOriginalTaskAndKeepsNewReportLink() {
        val job=JSONObject().put("id","job1").put("state","attention").put("payload","original-download").put("log","original failure log").put("detail","original failure").put("retry_count",3).put("files",JSONArray().put("content://original")).put("events","before")
        FragmentDiagnosticJob.begin(job,"diagnostic-intent",99)
        job.put("state","running").put("log","diagnostic progress").put("events","before\ninspection")
        job.getJSONArray("files").put("content://diagnostics")
        FragmentDiagnosticJob.restore(job,"Diagnostics saved")
        assertEquals("attention",job.getString("state"));assertEquals("original-download",job.getString("payload"));assertEquals("original failure log",job.getString("log"));assertEquals(3,job.getInt("retry_count"));assertEquals(2,job.getJSONArray("files").length());assertFalse(FragmentDiagnosticJob.pending(job))
    }
    @Test fun activeAndQueuedJobsCannotBeBorrowed() {
        for(state in listOf("running","queued","held","finalizing")) {
            val job=JSONObject().put("state",state).put("payload","original")
            try { FragmentDiagnosticJob.begin(job,"new",99);fail("Expected busy rejection") } catch(expected: IllegalStateException) {}
            assertEquals("original",job.getString("payload"));assertFalse(FragmentDiagnosticJob.pending(job))
        }
    }
    @Test fun repeatedExportDoesNotNestSnapshots() {
        val job=JSONObject().put("state","interrupted").put("payload","original").put("files",JSONArray())
        repeat(2) { FragmentDiagnosticJob.begin(job,"new",99);FragmentDiagnosticJob.restore(job,"done") }
        assertEquals("original",job.getString("payload"));assertEquals("interrupted",job.getString("state"));assertFalse(job.toString().contains("fragment_diagnostic_snapshot"))
    }
    @Test fun realLocalProbeFindsBothTracksAndDoesNotExposeFilename()=temp { root ->
        val ffprobe=System.getenv("FRAGMENT_DIAGNOSTICS_FFPROBE").orEmpty()
        val ffmpeg=System.getenv("FRAGMENT_DIAGNOSTICS_FFMPEG").orEmpty()
        assumeTrue(ffprobe.isNotBlank() && ffmpeg.isNotBlank())
        val source=File(root,"PRIVATE_TITLE-token-SECRET.ts")
        val generator=ProcessBuilder(ffmpeg,"-v","error","-f","lavfi","-i","testsrc=size=160x90:rate=10","-f","lavfi","-i","sine=frequency=440","-t","4","-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac","-f","mpegts",source.absolutePath).redirectErrorStream(true).start()
        generator.inputStream.use { it.readBytes() };assertEquals(0,generator.waitFor())
        val before=sha(source.readBytes())
        val report=FragmentDiagnosticCore.probe(File(ffprobe),emptyMap(),source,{false},20000)
        assertEquals(0,report.getInt("exitCode"))
        val metadata=report.getJSONObject("summary").getJSONArray("metadata")
        val types=(0 until metadata.length()).map { metadata.getJSONObject(it).optString("codec_type") }.toSet()
        assertTrue(types.containsAll(setOf("video","audio")))
        assertFalse(report.toString().contains("PRIVATE_TITLE"));assertFalse(report.toString().contains("SECRET"))
        assertEquals(before,sha(source.readBytes()))
        assertTrue(report.getJSONObject("summary").getJSONArray("packetTiming").length()>=2)
    }

}
