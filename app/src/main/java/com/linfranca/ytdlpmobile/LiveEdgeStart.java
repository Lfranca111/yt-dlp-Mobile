package com.linfranca.ytdlpmobile;

/** Shared source-clock live edge, independent of rendition sequence numbers. */
final class LiveEdgeStart {
    static long sharedAnchor(long[] videoTimes, double[] videoDurations,
                             long[] audioTimes, double[] audioDurations, int targetSeconds) {
        if (videoTimes.length == 0 || audioTimes.length == 0) throw new IllegalArgumentException("No live segments");
        long earliest = Math.max(videoTimes[0], audioTimes[0]);
        long end = Math.min(end(videoTimes, videoDurations), end(audioTimes, audioDurations));
        if (end <= earliest) throw new IllegalArgumentException("Audio and video live windows do not overlap");
        return Math.max(earliest, end - Math.max(6000L, 3_000L * targetSeconds));
    }
    static int index(long[] times, double[] durations, Long anchor, int targetSeconds) {
        if (durations.length == 0) throw new IllegalArgumentException("No live segments");
        if (anchor != null && times.length == durations.length) {
            int result = 0;
            for (int i = 0; i < times.length; i++) {
                if (times[i] <= anchor) result = i;
                else break;
            }
            return result;
        }
        double holdback = Math.max(6, targetSeconds * 3);
        double remaining = 0;
        for (int i = durations.length - 1; i >= 0; i--) {
            remaining += durations[i];
            if (remaining >= holdback) return i;
        }
        return 0;
    }
    private static long end(long[] times, double[] durations) {
        return times[times.length-1] + Math.round(durations[durations.length-1] * 1000);
    }
}
