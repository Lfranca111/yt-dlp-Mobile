package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class NativeMediaTest {
    @Test fun serializersPreserveUnicodeAndPdfEscapes() {
        assertEquals("0061D83DDE42", NativeMedia.hex("a🙂".toByteArray(Charsets.UTF_16BE)))
        assertEquals("a\\(b\\)\\\\漢", NativeMedia.pdfLiteral("a(b)\\漢"))
        assertEquals("", NativeMedia.hex(byteArrayOf()))
    }
    @Test fun playlistLinesKeepTheTwoExistingTrimRules() {
        val input = "\u00a0#EXTM3U\r\n  segment.ts \rnext\n"
        assertArrayEquals(arrayOf("#EXTM3U", "segment.ts", "next", ""), NativeMedia.lines(input, false))
        assertArrayEquals(arrayOf("\u00a0#EXTM3U", "segment.ts \rnext", ""), NativeMedia.lines(input, true))
    }
    @Test fun hlsAttributesPreserveCommasAndWhitespace() {
        assertArrayEquals(arrayOf("a,b.m3u8", "x.m3u8", null, " right"), NativeMedia.attributes(arrayOf(
            "#EXT-X-MEDIA:URI=\"a,b.m3u8\"", "X:uri=x.m3u8", "X: URI=wrong", "X:URI= right"
        ), "URI"))
    }
    @Test fun htmlAttributesRetainRegexBoundaryAndQuoteBehavior() {
        assertArrayEquals(arrayOf("one", "two", null, null, "mixed"), NativeMedia.htmlAttributes(arrayOf(
            "<a HREF = 'one'>", "<a data-href=\"two\">", "<a xhref='wrong'>",
            "<a 漢href='wrong'>", "<a href='mixed\">"
        ), "href"))
    }
    @Test fun decimalOverflowAndUnicodeStayCompatible() {
        assertEquals(Long.MIN_VALUE, NativeMedia.decimal("-9223372036854775808"))
        assertEquals(Long.MAX_VALUE, NativeMedia.decimal("+9223372036854775807"))
        assertNull(NativeMedia.decimal("9223372036854775808"))
        assertNull(NativeMedia.decimal(" 1"))
        assertEquals(12L, NativeMedia.decimal("١٢"))
        assertFalse(NativeMedia.asciiDigits("١٢"))
    }
    @Test fun requestTargetsPreservePairedAndRelayRules() {
        assertEquals("/paired.m3u8", NativeMedia.httpPath("GET /paired.m3u8 HTTP/1.1", true))
        assertEquals("", NativeMedia.httpPath("GET /paired.m3u8", true))
        assertEquals("/paired.m3u8", NativeMedia.httpPath("GET /paired.m3u8", false))
        assertEquals("", NativeMedia.httpPath("POST /paired.m3u8 HTTP/1.1", false))
    }
    @Test fun signatureChecksRespectBytesActuallyRead() {
        val bytes = ByteArray(564).apply { this[0] = 71; this[188] = 71; this[376] = 71 }
        assertFalse(NativeMedia.magic(bytes, 376, 6))
        assertTrue(NativeMedia.magic(bytes, 377, 6))
        assertFalse(NativeMedia.magic(bytes, bytes.size + 1, 6))
    }
    @Test fun timingPoliciesRetainThresholdsAndBackoff() {
        assertEquals(0, NativeMedia.gapKind(299))
        assertEquals(1, NativeMedia.gapKind(300))
        assertEquals(1, NativeMedia.gapKind(30_000))
        assertEquals(2, NativeMedia.gapKind(30_001))
        assertEquals(2, NativeMedia.gapKind(-300))
        assertEquals(800L, NativeMedia.retry(2, 0, false))
        assertEquals(15_000L, NativeMedia.retry(15, 8, false))
    }
    @Test fun xrefPreservesOffsetsBeyondTenDigits() {
        val actual = NativeMedia.xref(longArrayOf(0, 123, 12_345_678_901)).toString(Charsets.US_ASCII)
        assertEquals("0000000123 00000 n \n12345678901 00000 n \n", actual)
    }
    @Test fun pageRotationAndSupportedHostsRetainTheirMeaning() {
        assertArrayEquals(intArrayOf(720, 1440), NativeMedia.pdfSize(2000, 1000, 6))
        assertEquals(6, NativeMedia.site("2.multporn.net"))
        assertEquals(0, NativeMedia.site("multporn.net.evil.example"))
    }
}
