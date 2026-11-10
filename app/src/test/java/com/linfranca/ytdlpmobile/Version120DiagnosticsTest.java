package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class Version120DiagnosticsTest {
    @Test public void monitoringContextRetainsTheCauseBeyondTheOldLineLimit() {
        String prefix="powerSave=false; batteryExempt=false; ".repeat(15);
        String message=prefix+"confirmed=system reports background restriction; possibleContributors=battery optimization enabled";
        assertTrue(RecorderErrorDiagnostic.safeLine(message,2000).contains("confirmed=system reports background restriction"));
        assertTrue(RecorderErrorDiagnostic.safeLine(message).length()<=400);
    }
    @Test public void longerContextStillRedactsCredentialsAndAddresses() {
        String message="context; ".repeat(60)+"https://example.com/private token=secret-value";
        String clean=RecorderErrorDiagnostic.safeLine(message,2000);
        assertFalse(clean.contains("example.com"));
        assertFalse(clean.contains("secret-value"));
        assertTrue(clean.contains("[credential removed]"));
    }
}
