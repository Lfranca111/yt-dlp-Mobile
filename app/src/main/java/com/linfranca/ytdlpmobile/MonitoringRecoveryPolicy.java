package com.linfranca.ytdlpmobile;

/** Bounded recovery delays; no Android dependency. Zero means the automatic burst is exhausted. */
final class MonitoringRecoveryPolicy {
    static long delay(int failures) {
        return switch(failures) { case 1 -> 60_000L; case 2 -> 120_000L; case 3 -> 300_000L; default -> 0L; };
    }
    static long quotaDelay(int failures) {
        return switch(failures) { case 1 -> 900_000L; case 2 -> 1_800_000L; case 3 -> 3_600_000L; default -> 0L; };
    }
    static boolean transientFailure(String kind) {
        return "dns".equals(kind) || "network".equals(kind) || "background".equals(kind) || "access".equals(kind) || "browser".equals(kind);
    }
    static int checkFailures(int previous,String previousKind,String currentKind) {
        return "browser".equals(currentKind) && !"browser".equals(previousKind) ? 1 : previous+1;
    }
    static boolean currentCheck(long expected, long persisted, long now) {
        return (expected<=0 || expected==persisted) && (persisted<=0 || now>=persisted);
    }
    static long nextCheck(long now,int interval,int failures,String zone,int start,int end,java.util.Set<Integer> days,String kind) {
        return nextCheck(now,interval,failures,zone,start,end,days,kind,true);
    }
    static long nextCheck(long now,int interval,int failures,String zone,int start,int end,java.util.Set<Integer> days,String kind,boolean backoff) {
        long normal=MonitoringPolicy.next(now,interval,backoff ? failures : 0,zone,start,end,days);
        long retry=transientFailure(kind) ? delay(failures) : 0;
        long candidate=now+retry;
        return retry>0 && MonitoringPolicy.inWindow(candidate,zone,start,end,days) ? Math.min(normal,candidate) : normal;
    }
    private MonitoringRecoveryPolicy() {}
}
