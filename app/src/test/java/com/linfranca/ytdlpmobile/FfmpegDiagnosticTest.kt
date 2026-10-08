package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FfmpegDiagnosticTest {
    @Test fun hidesSignedUrlButKeepsHttpFailure() {
        assertEquals("[https @ 0x1] [media URL] Server returned 403 Forbidden",
            FfmpegDiagnostic.safeLine("[https @ 0x1] https://cdn.example/live.m3u8?session=secret: Server returned 403 Forbidden"))
    }

    @Test fun rejectsHeadersAndPreservesMappingFailure() {
        assertNull(FfmpegDiagnostic.safeLine("Cookie: session=secret"))
        assertEquals("Stream map '1:a:0' matches no streams.",
            FfmpegDiagnostic.safeLine("Stream map '1:a:0' matches no streams."))
    }
}
