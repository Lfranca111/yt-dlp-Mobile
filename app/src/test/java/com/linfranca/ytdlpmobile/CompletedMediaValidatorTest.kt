package com.linfranca.ytdlpmobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompletedMediaValidatorTest {
    @Test fun rejectsTinySabrControlResponse() {
        val file = File.createTempFile("sabr-response", ".mp4")
        try {
            file.writeText("sabr.malformed_config: no media")
            assertFalse(CompletedMediaValidator.looksLikeMedia(file))
        } finally { file.delete() }
    }

    @Test fun acceptsMediaContainerHeader() {
        val file = File.createTempFile("real-media", ".mp4")
        try {
            file.writeBytes(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(1200))
            assertTrue(CompletedMediaValidator.looksLikeMedia(file))
        } finally { file.delete() }
    }
}
