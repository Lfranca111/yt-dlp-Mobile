package com.linfranca.ytdlpmobile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataOutputStream

class RecordingMediaTest {
    @Test fun interruptedTransportStreamIsKeptDespiteTemporaryFilename() {
        val folder = Files.createTempDirectory("recording-test").toFile()
        try {
            val bytes = ByteArray(188 * 400)
            for (packet in bytes.indices step 188) bytes[packet] = 0x47
            folder.resolve("recording.mp4.part").writeBytes(bytes)
            assertEquals("ts", RecordingMedia.choose(folder, interrupted = true)?.extension)
        } finally { folder.deleteRecursively() }
    }

    @Test fun largeFileWithoutMediaPacketsIsRejected() {
        val folder = Files.createTempDirectory("recording-test").toFile()
        try {
            folder.resolve("recording.ts").writeBytes(ByteArray(188 * 400))
            assertNull(RecordingMedia.choose(folder, interrupted = true))
        } finally { folder.deleteRecursively() }
    }
    @Test fun interruptedFragmentedMp4IsKeptOnlyForOptedInCapture() {
        val folder = Files.createTempDirectory("recording-test").toFile()
        try {
            DataOutputStream(folder.resolve("recording.mp4").outputStream()).use { out ->
                listOf("ftyp", "moov", "moof", "mdat").forEach { type ->
                    out.writeInt(20_008); out.writeBytes(type); out.write(ByteArray(20_000))
                }
            }
            assertNull(RecordingMedia.choose(folder, interrupted = true))
            assertEquals("mp4", RecordingMedia.choose(folder, interrupted = true, allowFragmentedMp4 = true)?.extension)
        } finally { folder.deleteRecursively() }
    }
}
