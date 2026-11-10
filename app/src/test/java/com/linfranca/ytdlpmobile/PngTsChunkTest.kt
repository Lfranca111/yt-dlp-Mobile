package com.linfranca.ytdlpmobile

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.*
import java.nio.file.Files
import java.util.zip.CRC32
import org.json.JSONObject

class PngTsChunkTest {
    private val signature=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    private fun chunk(type: String,data: ByteArray): ByteArray=ByteArrayOutputStream().apply {
        DataOutputStream(this).apply { writeInt(data.size);writeBytes(type);write(data);val crc=CRC32().apply { update(type.toByteArray());update(data) };writeInt(crc.value.toInt()) }
    }.toByteArray()
    private fun wrap(data: ByteArray)=signature+chunk("IHDR",byteArrayOf(0,0,0,1,0,0,0,1,8,6,0,0,0))+chunk("IDAT",byteArrayOf(1,2,3))+chunk("tsDa",data)+chunk("IEND",byteArrayOf())
    private fun ts(n: Int)=ByteArray(n*188) { 255.toByte() }.apply { repeat(n) { this[it*188]=71;this[it*188+3]=16 } }
    @Test fun extractsDeclaredPayloadAndRemovesCrcAndIendBetweenImages() {
        val first=ts(6466);val second=ts(30);val data=wrap(first)+wrap(byteArrayOf())+wrap(second)
        val output=ByteArrayOutputStream();val s=PngTsNormalizer.copy(ByteArrayInputStream(data),output)
        assertArrayEquals(first+second,output.toByteArray());assertEquals(3,s.wrappers);assertEquals(1,s.emptyWrappers);assertEquals(2,s.chunkPayloads)
        assertEquals((data.size-first.size-second.size).toLong(),s.removedBytes)
    }
    @Test fun preservesNonAlignedBytesInsideValidatedTransportChunkForNativeDemuxer() {
        val payload=ts(10)+byteArrayOf(1,2,3,4,5,6,7,8,9,10)+ts(10)
        val out=ByteArrayOutputStream();val s=PngTsNormalizer.copy(ByteArrayInputStream(wrap(payload)),out)
        assertArrayEquals(payload,out.toByteArray());assertEquals(10L,s.payloadAlignmentBytes)
    }
    @Test fun checksumFailureRejectsOutputWithoutChangingSource() {
        val data=wrap(ts(10));data[data.size-17]=(data[data.size-17].toInt() xor 1).toByte();val before=data.clone()
        try { PngTsNormalizer.copy(ByteArrayInputStream(data),ByteArrayOutputStream());fail("Expected CRC rejection") }
        catch(e: PngTsNormalizer.Failure) { assertEquals("PNG chunk checksum mismatch",e.reason);assertTrue(e.offset>0) }
        assertArrayEquals(before,data)
    }
    @Test fun truncatedChunkHasSpecificErrorAndOffset() {
        val data=wrap(ts(100)).dropLast(100).toByteArray()
        try { PngTsNormalizer.copy(ByteArrayInputStream(data),ByteArrayOutputStream());fail("Expected truncation") }
        catch(e: PngTsNormalizer.Failure) { assertEquals("Truncated PNG chunk or transport payload",e.reason);assertEquals(data.size.toLong(),e.offset) }
    }
    @Test fun chunkPayloadMustContainTransportSync() {
        try { PngTsNormalizer.copy(ByteArrayInputStream(wrap(ByteArray(600))),ByteArrayOutputStream());fail("Expected rejection") }
        catch(e: PngTsNormalizer.Failure) { assertTrue(e.reason.contains("lacks verified transport sync")) }
    }
    @Test fun streamedChunksCrossSmallReadBoundariesAndCancellationKeepsInput() {
        val data=wrap(ts(2000))+wrap(ts(100));val before=data.clone();val out=ByteArrayOutputStream()
        val input=object: ByteArrayInputStream(data) { override fun read(b: ByteArray,off: Int,len: Int)=super.read(b,off,minOf(17,len)) }
        PngTsNormalizer.copy(input,out);assertArrayEquals(ts(2000)+ts(100),out.toByteArray())
        var calls=0
        try { PngTsNormalizer.copy(ByteArrayInputStream(data),ByteArrayOutputStream(),{ ++calls>8 });fail("Expected cancellation") } catch(expected: InterruptedException) {}
        assertArrayEquals(before,data)
    }
    @Test fun actualUploadedSamplesExtractExactlyAndDecodeAsVideoWithAudio() {
        val path=System.getenv("FRAGMENT_SAMPLE_FIXTURES").orEmpty();val ffmpeg=System.getenv("FRAGMENT_DIAGNOSTICS_FFMPEG").orEmpty();val ffprobe=System.getenv("FRAGMENT_DIAGNOSTICS_FFPROBE").orEmpty()
        assumeTrue(path.isNotBlank() && ffmpeg.isNotBlank() && ffprobe.isNotBlank())
        val root=Files.createTempDirectory("actual-chunk-recovery").toFile()
        try {
            val fixture=File(path);val pieces=listOf(1,4,6,5,2,7,3).map { File(fixture,"sample-${it.toString().padStart(3,'0')}.bin").readBytes() }
            val joined=pieces.fold(byteArrayOf()) { acc,b -> acc+b };val out=ByteArrayOutputStream()
            val stats=PngTsNormalizer.copy(ByteArrayInputStream(joined),out)
            assertArrayEquals(pieces.filter { it.size>80 }.fold(byteArrayOf()) { acc,b -> acc+b.copyOfRange(64,b.size-16) },out.toByteArray())
            assertEquals(2,stats.emptyWrappers);assertEquals(5,stats.chunkPayloads);assertEquals(20L,stats.payloadAlignmentBytes)
            for(i in listOf(1,2,3,6,7)) {
                val source=File(fixture,"sample-${i.toString().padStart(3,'0')}.bin");val before=source.readBytes()
                val mp4=File(root,"$i.mp4")
                val process=ProcessBuilder(ffmpeg,"-nostdin","-v","error","-y","-f","mpegts","-i","pipe:0","-map","0:v:0","-map","0:a:0","-c","copy","-movflags","+faststart",mp4.absolutePath).redirectErrorStream(true).start()
                val drain=Thread { process.inputStream.use { it.readBytes() } }.apply { start() }
                source.inputStream().use { input -> process.outputStream.use { output -> PngTsNormalizer.copy(input,output) } }
                assertEquals(0,process.waitFor());drain.join()
                val probe=ProcessBuilder(ffprobe,"-v","error","-show_streams","-show_format","-of","json",mp4.absolutePath).start()
                val metadata=JSONObject(probe.inputStream.bufferedReader().readText());assertEquals(0,probe.waitFor())
                val streams=metadata.getJSONArray("streams");assertEquals(setOf("h264","aac"),(0 until streams.length()).map { streams.getJSONObject(it).getString("codec_name") }.toSet())
                assertTrue(metadata.getJSONObject("format").getDouble("duration") in 4.8..5.3)
                val decode=ProcessBuilder(ffmpeg,"-nostdin","-v","error","-i",mp4.absolutePath,"-map","0:v:0","-map","0:a:0","-f","null","-").redirectErrorStream(true).start()
                val errors=decode.inputStream.bufferedReader().readText();assertEquals(errors,0,decode.waitFor());assertEquals("",errors)
                assertArrayEquals(before,source.readBytes())
            }
        } finally { root.deleteRecursively() }
    }
}
