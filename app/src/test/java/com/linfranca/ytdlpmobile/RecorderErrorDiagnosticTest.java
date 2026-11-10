package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecorderErrorDiagnosticTest {
    @Test public void preservesExtractorFailureWhileRemovingSignedUrl() {
        String safe = String.join("\n", RecorderErrorDiagnostic.describe(new Exception(
            "ERROR: Unsupported URL: https://example.test/watch?token=VERY_PRIVATE_VALUE")));
        assertTrue(safe.contains("Unsupported URL"));
        assertFalse(safe.contains("example.test"));
        assertFalse(safe.contains("VERY_PRIVATE_VALUE"));
    }
    @Test public void removesHeadersAccountsPathsAndUnlabelledLongKeys() {
        assertFalse(RecorderErrorDiagnostic.safeLine("HTTP 403; Authorization: Bearer private value").contains("private value"));
        assertFalse(RecorderErrorDiagnostic.safeLine("ERROR: api_key=private value").contains("private value"));
        String safe = RecorderErrorDiagnostic.safeLine("ERROR: file /data/user/0/app/private user@example.test abcdef0123456789abcdef0123456789abcdef0123456789");
        assertFalse(safe.contains("/data/")); assertFalse(safe.contains("user@example")); assertFalse(safe.contains("abcdef0123"));
        assertNull(RecorderErrorDiagnostic.safeLine("[debug] Command-line config: secret"));
    }
    @Test public void retainsActualErrorAtEndOfLargeOutputAndCause() {
        Exception error = new Exception("progress\n".repeat(5000) + "ERROR: Stream is offline", new Exception("HTTP Error 403: Forbidden"));
        String safe = String.join("\n", RecorderErrorDiagnostic.describe(error));
        assertTrue(safe.contains("Stream is offline")); assertTrue(safe.contains("403: Forbidden"));
        assertTrue(safe.length() < 5000);
    }
    @Test public void nullMessagesStillProduceUsefulFailureType() {
        assertEquals("Failure detail type=Exception", RecorderErrorDiagnostic.describe(new Exception()).get(0));
    }
}
