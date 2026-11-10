package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.json.JSONObject

class PngTsNormalizerTest {
    private val image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5d0AAAAASUVORK5CYII=").let { it+ByteArray(80-it.size) }
    private fun packets(count: Int)=ByteArray(188*count) { 3 }.apply { repeat(count) { val p=188*it;this[p]=71;this[p+1]=0;this[p+2]=0;this[p+3]=16 } }
    @Test fun removesMixedSizeImageHeadersAndCountsEmptyPlaceholders() {
        val first=packets(400);val second=packets(600)
        val input=image+first+image+image+ByteArray(10)+second+image
        val out=ByteArrayOutputStream();val result=PngTsNormalizer.copy(ByteArrayInputStream(input),out)
        assertArrayEquals(first+second,out.toByteArray());assertEquals(1000L,result.packets)
        assertEquals(4,result.wrappers);assertEquals(2,result.emptyWrappers)
        assertEquals(input.size.toLong(),result.inputBytes);assertEquals(330L,result.removedBytes)
    }
    @Test fun standardTransportRemainsByteIdentical() {
        val data=packets(10);val out=ByteArrayOutputStream();val result=PngTsNormalizer.copy(ByteArrayInputStream(data),out)
        assertArrayEquals(data,out.toByteArray());assertEquals(0,result.wrappers);assertEquals(0L,result.removedBytes)
    }
    @Test fun pngSignatureInsidePacketPayloadIsKept() {
        val ts=packets(30);image.copyInto(ts,30)
        val out=ByteArrayOutputStream();PngTsNormalizer.copy(ByteArrayInputStream(image+ts),out)
        assertArrayEquals(ts,out.toByteArray())
    }
    @Test fun smallReadsDoNotLosePacketBoundaries() {
        val ts=packets(2000)
        val input=object: ByteArrayInputStream(image+ts) { override fun read(b: ByteArray,off: Int,len: Int)=super.read(b,off,minOf(17,len)) }
        val out=ByteArrayOutputStream();PngTsNormalizer.copy(input,out);assertArrayEquals(ts,out.toByteArray())
    }
    @Test fun aPictureWithoutTransportIsNotCalledRecoveredVideo() {
        try { PngTsNormalizer.copy(ByteArrayInputStream(image),ByteArrayOutputStream());fail("Expected no-video rejection") } catch(expected: IllegalStateException) {}
    }
    @Test fun unrecognizedMidstreamGarbageIsNotSilentlyDiscarded() {
        try { PngTsNormalizer.copy(ByteArrayInputStream(image+packets(10)+byteArrayOf(1,2,3)+packets(10)),ByteArrayOutputStream());fail("Expected rejection") } catch(expected: IllegalStateException) {}
    }
    @Test fun cancellationDoesNotAlterSource() {
        val data=image+packets(20);val original=data.clone()
        try { PngTsNormalizer.copy(ByteArrayInputStream(data),ByteArrayOutputStream(),{true});fail("Expected cancellation") } catch(expected: InterruptedException) {}
        assertArrayEquals(original,data)
    }
    @Test fun detectorRequiresBothImageHeaderAndTransportEvidence() {
        val root=Files.createTempDirectory("wrapped-detector").toFile()
        try {
            val file=File(root,"file.bin")
            file.writeBytes(image);assertFalse(PngTsNormalizer.looksWrapped(file))
            file.writeBytes(packets(20));assertFalse(PngTsNormalizer.looksWrapped(file))
            file.writeBytes(image+packets(20));assertTrue(PngTsNormalizer.looksWrapped(file))
        } finally { root.deleteRecursively() }
    }
    @Test fun repairJobRestoresOriginalHistoryAndKeepsNewCopyLink() {
        val j=JSONObject().put("state","attention").put("payload","original").put("log","original failure").put("files",org.json.JSONArray())
        FragmentDiagnosticJob.begin(j,"local-repair",1,"repair")
        assertEquals("repair",FragmentDiagnosticJob.operation(j))
        j.getJSONArray("files").put("content://recovered")
        FragmentDiagnosticJob.restore(j,"Recovered video saved")
        assertEquals("attention",j.getString("state"));assertEquals("original",j.getString("payload"));assertEquals("original failure",j.getString("log"))
        assertEquals("Recovered video saved",j.getString("retained_recovery_status"));assertFalse(FragmentDiagnosticJob.pending(j))
    }
    @Test fun realWrappedMediaBecomesPlayableVideoWithAudioAndCorrectDuration() {
        val ffmpeg=System.getenv("FRAGMENT_DIAGNOSTICS_FFMPEG").orEmpty();val ffprobe=System.getenv("FRAGMENT_DIAGNOSTICS_FFPROBE").orEmpty()
        assumeTrue(ffmpeg.isNotBlank() && ffprobe.isNotBlank())
        val root=Files.createTempDirectory("wrapped-media-recovery").toFile()
        try {
            fun run(args: List<String>): String { val p=ProcessBuilder(args).redirectErrorStream(true).start();val text=p.inputStream.bufferedReader().readText();assertEquals(text,0,p.waitFor());return text }
            run(listOf(ffmpeg,"-v","error","-f","lavfi","-i","testsrc=size=160x90:rate=10","-f","lavfi","-i","sine=frequency=440","-t","12","-c:v","libx264","-pix_fmt","yuv420p","-g","40","-sc_threshold","0","-c:a","aac","-f","hls","-hls_time","4","-hls_list_size","0",File(root,"clip.m3u8").absolutePath))
            val fragments=root.listFiles()!!.filter { it.extension=="ts" }.sortedBy { it.name }
            val original=File(root,"assembled.mp4").apply { outputStream().use { out -> fragments.forEachIndexed { i,f -> out.write(image);if(i==1) out.write(image);out.write(f.readBytes()) } } }
            val before=original.readBytes()
            val output=File(root,"recovered.mp4")
            val process=ProcessBuilder(ffmpeg,"-v","error","-y","-protocol_whitelist","pipe,file","-fflags","+genpts+discardcorrupt","-f","mpegts","-i","pipe:0","-map","0:v:0","-map","0:a?","-c","copy","-avoid_negative_ts","make_zero","-movflags","+faststart",output.absolutePath).redirectErrorStream(true).start()
            val drain=Thread { process.inputStream.use { it.readBytes() } }.apply { start() }
            val stats=original.inputStream().use { input -> process.outputStream.buffered().use { out -> PngTsNormalizer.copy(input,out) } }
            assertEquals(0,process.waitFor());drain.join();assertEquals(1,stats.emptyWrappers)
            val metadata=JSONObject(run(listOf(ffprobe,"-v","error","-show_streams","-show_format","-of","json",output.absolutePath)))
            val streams=metadata.getJSONArray("streams");val types=(0 until streams.length()).map { streams.getJSONObject(it).getString("codec_type") }.toSet()
            assertTrue(types.containsAll(setOf("video","audio")))
            assertTrue(metadata.getJSONObject("format").getDouble("duration") in 11.5..13.0)
            assertArrayEquals(before,original.readBytes())
            assertTrue(streams.getJSONObject(0).optString("codec_name")!="png")
        } finally { root.deleteRecursively() }
    }
}
