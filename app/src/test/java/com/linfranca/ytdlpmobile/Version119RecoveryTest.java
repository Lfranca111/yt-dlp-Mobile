package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.Instant;
import java.util.Set;

public class Version119RecoveryTest {
    private static final Set<Integer> ALL=Set.of(1,2,3,4,5,6,7);
    @Test public void temporaryFailureBurstIsBounded() {
        assertEquals(60_000,MonitoringRecoveryPolicy.delay(1));
        assertEquals(120_000,MonitoringRecoveryPolicy.delay(2));
        assertEquals(300_000,MonitoringRecoveryPolicy.delay(3));
        assertEquals(0,MonitoringRecoveryPolicy.delay(4));
        assertEquals(0,MonitoringRecoveryPolicy.delay(100));
    }
    @Test public void dnsRetriesBeforeThirtyMinuteIntervalWithoutChangingNormalSchedule() {
        long now=Instant.parse("2026-10-27T17:26:29Z").toEpochMilli();
        assertEquals(now+60_000,MonitoringRecoveryPolicy.nextCheck(now,30,1,"UTC",-1,-1,ALL,"dns"));
        assertEquals(now+120_000,MonitoringRecoveryPolicy.nextCheck(now,30,2,"UTC",-1,-1,ALL,"network"));
        assertEquals(MonitoringPolicy.next(now,30,4,"UTC",-1,-1,ALL),MonitoringRecoveryPolicy.nextCheck(now,30,4,"UTC",-1,-1,ALL,"dns"));
        assertEquals(now+1800_000,MonitoringRecoveryPolicy.nextCheck(now,30,0,"UTC",-1,-1,ALL,""));
    }
    @Test public void retryNeverEscapesSelectedWindow() {
        long now=Instant.parse("2026-10-27T21:59:30Z").toEpochMilli();
        assertEquals(MonitoringPolicy.next(now,30,1,"UTC",8*60,22*60,ALL),MonitoringRecoveryPolicy.nextCheck(now,30,1,"UTC",8*60,22*60,ALL,"dns"));
    }
    @Test public void foregroundRestrictionIsNotBrowserAuthenticationOrDns() {
        String error="ForegroundServiceStartNotAllowedException: startForegroundService() not allowed";
        assertEquals("background",MonitoringFailure.kind(error));
        assertTrue(MonitoringRecoveryPolicy.transientFailure(MonitoringFailure.kind(error)));
        assertEquals("dns",MonitoringFailure.kind("[Errno 7] No address associated with hostname"));
        assertFalse(MonitoringRecoveryPolicy.transientFailure("authentication"));
        assertFalse(MonitoringRecoveryPolicy.transientFailure("recognition"));
        assertFalse(MonitoringRecoveryPolicy.transientFailure("canceled"));
    }
    @Test public void oldAlarmAndScheduledWorkCannotRepeatAdvancedCheck() {
        assertTrue(MonitoringRecoveryPolicy.currentCheck(1000,1000,1000));
        assertFalse(MonitoringRecoveryPolicy.currentCheck(1000,2000,2500));
        assertFalse(MonitoringRecoveryPolicy.currentCheck(2000,2000,1999));
        assertTrue(MonitoringRecoveryPolicy.currentCheck(0,1000,1001)); // v1.0.18 pending work
        assertFalse(MonitoringRecoveryPolicy.currentCheck(0,2000,1001));
    }
}
