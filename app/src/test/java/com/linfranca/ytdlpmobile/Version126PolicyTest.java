package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.Instant;
import java.util.Set;

public class Version126PolicyTest {
    private final long now=Instant.parse("2026-10-30T12:00:00Z").toEpochMilli();
    private final Set<Integer> days=Set.of(1,2,3,4,5,6,7);
    private long next(int failures,String kind,boolean backoff) {
        return MonitoringRecoveryPolicy.nextCheck(now,30,failures,"UTC",-1,-1,days,kind,backoff);
    }
    @Test public void failedExtractorKeepsThirtyMinutesWithoutBackoff() {
        for(int failures: new int[]{1,4,8,100}) assertEquals(now+30*60_000,next(failures,"extractor",false));
    }
    @Test public void exhaustedShortRetryBurstReturnsToChosenInterval() {
        assertEquals(now+30*60_000,next(4,"browser",false));
        assertEquals(now+30*60_000,next(5,"dns",false));
    }
    @Test public void shortRecoveryRetriesDoNotRequireBackoff() {
        assertEquals(now+60_000,next(1,"network",false));
        assertEquals(now+120_000,next(2,"background",false));
        assertEquals(now+300_000,next(3,"browser",false));
    }
    @Test public void optionalBackoffStillExtendsFailedChecks() {
        assertEquals(now+160*60_000,next(5,"extractor",true));
    }
    @Test public void selectedTimeframeStillAppliesWithoutBackoff() {
        long due=MonitoringRecoveryPolicy.nextCheck(now,30,10,"UTC",14*60,18*60,days,"extractor",false);
        assertEquals(Instant.parse("2026-10-30T14:00:00Z").toEpochMilli(),due);
    }
    @Test public void offlineCannotHideNetworkOrAccessErrors() {
        assertTrue(MonitoringOfflinePolicy.confirmed("ERROR: The channel is not currently live."));
        assertTrue(MonitoringOfflinePolicy.confirmed("ERROR: [twitch:stream] example: The channel is not currently live"));
        for(String s:new String[]{"HTTP Error 403: Forbidden","DNS lookup failed","Login required", "The channel is not currently live. Network error", "Unable to extract live status"})
            assertFalse(s,MonitoringOfflinePolicy.confirmed(s));
    }
    @Test public void reportedQuotaStaysDistinctFromBackgroundAndBattery() {
        assertEquals("quota",MonitoringFailure.kind("Time limit already exhausted for foreground service type dataSync"));
        assertEquals("background",MonitoringFailure.kind("startForegroundService() not allowed"));
        assertFalse(MonitoringFailure.kind("battery=4%").equals("quota"));
    }
    @Test public void successfulCheckReturnsToNormalEvenWithBackoffEnabled() {
        assertEquals(now+30*60_000,next(0,"",true));
    }
}
