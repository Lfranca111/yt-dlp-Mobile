package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;

public class Version118FailureTest {
    @Test public void classifiesTheReportedDnsFailure() { assertEquals("dns",MonitoringFailure.kind("Unable to download API page: [Errno 7] No address associated with hostname")); }
    @Test public void stoppedWorkIsNotBlamedOnItsLastDnsMessage() { assertEquals("canceled",MonitoringFailure.kind("No address associated with hostname; CanceledException")); }
    @Test public void recognizesTabFailureAndNetworkTimeoutSeparately() {
        assertEquals("recognition",MonitoringFailure.kind("Unable to recognize tab page"));assertEquals("network",MonitoringFailure.kind("connection timed out"));
    }
    @Test public void messagesDoNotEchoSecretsFromRawErrors() {
        String result=MonitoringFailure.message("Unable to recognize tab page https://example.com/?token=secret");
        assertFalse(result.contains("secret"));assertFalse(result.contains("https://"));assertTrue(result.contains("baseline retained"));
    }
    @Test public void stopElevenAndNoStopAreDifferent() {
        assertTrue(MonitoringFailure.stop(11).contains("background restriction"));assertTrue(MonitoringFailure.stop(-256).contains("not stopped"));
        assertTrue(MonitoringFailure.stop(7).contains("connectivity"));assertTrue(MonitoringFailure.stop(5).contains("battery"));
    }
    @Test public void unknownStopCodesRemainUnknown() { assertTrue(MonitoringFailure.stop(999).contains("999"));assertTrue(MonitoringFailure.stop(15).contains("launch time")); }
}
