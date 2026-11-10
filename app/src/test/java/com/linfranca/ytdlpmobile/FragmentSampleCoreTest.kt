package com.linfranca.ytdlpmobile

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.nio.file.Files
import java.security.MessageDigest

class FragmentSampleCoreTest {
    private fun root()=Files.createTempDirectory("sample-test").toFile()
    private fun fragment(root: File,n: Int,data: ByteArray): File=File(root,".download-cache/objects/"+n.toString().padStart(64,'0')+".bin").apply { parentFile!!.mkdirs();writeBytes(data);setLastModified(1000L+n) }
    private fun entries(bytes: ByteArray): Map<String,ByteArray> {
        val out=linkedMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while(true) { val e=zip.nextEntry ?: break;out[e.name]=zip.readBytes() } }
        return out
    }
    @Test fun samplesCopyExactBytesWithSafeAliasesAndNoPrivateFiles() {
        val root=root()
        try {
            val bytes=ByteArray(800) { it.toByte() };val file=fragment(root,1,bytes)
            File(root,"cookies.txt").writeText("cookie-secret")
            File(file.parentFile,file.nameWithoutExtension+".json").writeText(JSONObject().put("url","https://private.example/secret").put("cookie","private-cookie").put("fragment_index",4).toString())
            val before=file.readBytes();val output=ByteArrayOutputStream();FragmentSampleCore.write(root,output)
            val map=entries(output.toByteArray());val report=JSONObject(String(map.getValue("report.json")));val row=report.getJSONArray("samples").getJSONObject(0)
            assertArrayEquals(bytes,map.getValue(row.getString("file")))
            assertEquals(4,row.getInt("receiptFragmentIndex"))
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },row.getString("sha256"))
            assertFalse(report.toString().contains("private"));assertFalse(map.keys.any { it.contains(file.name) })
            assertArrayEquals(before,file.readBytes());assertEquals("cookie-secret",File(root,"cookies.txt").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun includesTinyAndUnusualFragmentsAndBoundsLargeExcerpts() {
        val root=root()
        try {
            for(i in 1..30) fragment(root,i,ByteArray(3*1024*1024))
            val tiny=fragment(root,31,ByteArray(80));val odd=fragment(root,32,ByteArray(188*5+90))
            val plan=FragmentSampleCore.plan(root)
            assertTrue(plan.sumOf { it.length }<=FragmentSampleCore.MAX_BYTES)
            assertTrue(plan.any { it.file==tiny && it.length==80L })
            assertTrue(plan.any { it.file==odd })
            assertTrue(plan.filter { it.file.length()>2*1024*1024 }.all { it.length<=262144 && it.reason.endsWith("excerpt") })
        } finally { root.deleteRecursively() }
    }
    @Test fun failureWindowIsCopiedAtExactOffsetAndStaleMatchIsNotUsed() {
        val root=root()
        try {
            val media=File(root,"retained.mp4").apply { writeBytes(ByteArray(300000) { (it%251).toByte() }) }
            val failure=File(root,".download-cache/recovery-failure.json").apply { parentFile!!.mkdirs() }
            fun record(modified: Long)=failure.writeText(JSONObject().put("assemblyBytes",media.length()).put("assemblyModified",modified).put("inputByteOffset",150000).put("reason","Unrecognized bytes at a transport-packet boundary").put("privateUrl","secret").toString())
            record(media.lastModified());val plan=FragmentSampleCore.plan(root)
            val sample=plan.single { it.reason=="around_normalization_failure" }
            assertEquals(117232L,sample.offset);assertEquals(65536L,sample.length)
            val output=ByteArrayOutputStream();FragmentSampleCore.write(root,output);val map=entries(output.toByteArray());val report=JSONObject(String(map.getValue("report.json")))
            assertFalse(report.toString().contains("privateUrl"))
            val rows=report.getJSONArray("samples");val row=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("reason").startsWith(sample.reason) }
            assertArrayEquals(media.readBytes().copyOfRange(117232,182768),map.getValue(row.getString("file")))
            record(media.lastModified()+1);assertFalse(FragmentSampleCore.plan(root).any { it.reason=="around_normalization_failure" })
        } finally { root.deleteRecursively() }
    }
    @Test fun includesPlainAssemblyBeginningAndEarliestReceiptOrdinalsRegardlessOfFileTime() {
        val root=root()
        try {
            val media=File(root,"plain.ts").apply { writeBytes(ByteArray(5*1024*1024) { (it%188).toByte() }) }
            val track="a".repeat(64)
            for(i in 0..8) {
                val file=fragment(root,i+1,ByteArray(188*5))
                file.setLastModified(9000L-i)
                File(file.parentFile,file.nameWithoutExtension+".json").writeText(JSONObject().put("fragment_index",i).put("track_key",track).toString())
            }
            val plan=FragmentSampleCore.plan(root)
            assertTrue(plan.any { it.file==media && it.offset==0L && it.length==4L*1024*1024 && it.reason=="assembly_beginning" })
            val first=plan.filter { it.reason=="earliest_receipt_ordinal" }
            assertEquals(listOf(1,2,3),first.map { it.file.nameWithoutExtension.toInt() })
            assertTrue(plan.sumOf { it.length }<=FragmentSampleCore.MAX_BYTES)
            assertEquals(5L*1024*1024,media.length())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationDoesNotChangeOriginals() {
        val root=root()
        try { val file=fragment(root,1,ByteArray(1000) { 42 });val before=file.readBytes()
            try { FragmentSampleCore.write(root,ByteArrayOutputStream(),{true});fail("Expected cancellation") } catch(expected: InterruptedException) {}
            assertArrayEquals(before,file.readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun emptyCacheHasClearFailure() {
        val root=root()
        try { try { FragmentSampleCore.write(root,ByteArrayOutputStream());fail("Expected failure") } catch(expected: IllegalStateException) { assertTrue(expected.message!!.contains("No retained")) } }
        finally { root.deleteRecursively() }
    }
    @Test fun normalizationReportsExactFailureOffsetAndCounters() {
        val packets=ByteArray(188*5);for(i in 0..4) { packets[i*188]=71;packets[i*188+3]=16 }
        try { PngTsNormalizer.copy(ByteArrayInputStream(packets+byteArrayOf(1,2,3)),ByteArrayOutputStream());fail("Expected failure") }
        catch(e: PngTsNormalizer.Failure) { assertEquals(940L,e.offset);assertEquals(5L,e.packetsSeen);assertEquals(3,e.bufferedBytes);assertTrue(e.message!!.contains("inputByteOffset=940")) }
    }
    @Test fun sampleJobRestoresOriginalSettingsLogAndState() {
        val job=JSONObject().put("state","attention").put("payload","original").put("log","original log")
        FragmentDiagnosticJob.begin(job,"sample payload",123,"samples");assertTrue(FragmentDiagnosticJob.pending(job))
        FragmentDiagnosticJob.restore(job,"samples saved")
        assertEquals("attention",job.getString("state"));assertEquals("original",job.getString("payload"));assertEquals("original log",job.getString("log"));assertEquals("samples saved",job.getString("fragment_sample_status"))
    }
}
