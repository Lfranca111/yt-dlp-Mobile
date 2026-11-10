package com.linfranca.ytdlpmobile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded-memory PES timestamp scan. Never changes packet timestamps or estimates lip sync. */
final class TransportStreamTiming {
    static final class Range {
        Long first90k, last90k;
        long samples, backwards;
        private Long previousRaw;
        private long wrap;
        private final java.util.ArrayDeque<Long> frameSteps = new java.util.ArrayDeque<>();
        void add(long raw) {
            if (previousRaw != null && previousRaw - raw > (1L << 32)) wrap += 1L << 33;
            long time = raw + wrap;
            // Video PTS can be reordered by B frames; that alone is not a discontinuity.
            if (last90k != null && time < last90k - 90000) backwards++;
            if (last90k != null && time > last90k && time - last90k < 90000) {
                frameSteps.add(time-last90k);
                if (frameSteps.size() > 64) frameSteps.removeFirst();
            }
            first90k = first90k == null ? time : Math.min(first90k, time);
            last90k = last90k == null ? time : Math.max(last90k, time);
            previousRaw = raw; samples++;
        }
        Double firstSeconds() { return first90k == null ? null : first90k / 90000.0; }
        Double lastSeconds() { return last90k == null ? null : last90k / 90000.0; }
        Double estimatedFrameSeconds() {
            if (frameSteps.isEmpty()) return null;
            long[] sorted = frameSteps.stream().mapToLong(Long::longValue).sorted().toArray();
            return sorted[sorted.length/2] / 90000.0;
        }
        Double spanSeconds() { return first90k == null || last90k == null ? null : (last90k-first90k)/90000.0; }
    }
    final Range video = new Range(), audio = new Range();
    long bytesScanned;
    boolean complete;
    static TransportStreamTiming inspect(File file) {
        var result = new TransportStreamTiming();
        if (!file.isFile()) return result;
        // First/last 8 MiB are enough for bounds of ordinary recordings. Avoid a multi-GB
        // sequential scan when Stop & save is pressed. Report sampling explicitly.
        try (var input = new java.io.RandomAccessFile(file, "r")) {
            byte[] packet = new byte[188];
            long limit = Math.min(file.length(), 8L * 1024 * 1024);
            long offset = 0;
            while (offset + 188 <= limit) {
                input.readFully(packet); result.packet(packet); offset += 188;
            }
            if (file.length() > limit) {
                long tail = Math.max(offset, (file.length() - limit) / 188 * 188);
                input.seek(tail);
                while (input.getFilePointer() + 188 <= file.length()) {
                    input.readFully(packet); result.packet(packet);
                }
            }
            result.complete = file.length() <= limit;
        } catch (Exception ignored) { }
        return result;
    }
    private void packet(byte[] p) {
        bytesScanned += 188;
        if ((p[0]&255) != 0x47 || (p[1]&0x80) != 0 || (p[1]&0x40) == 0) return;
        int control = (p[3] >> 4) & 3;
        if (control != 1 && control != 3) return;
        int at = control == 3 ? 5 + (p[4]&255) : 4;
        if (at + 14 > 188 || p[at] != 0 || p[at+1] != 0 || p[at+2] != 1) return;
        int id = p[at+3]&255;
        if ((p[at+7]&0x80) == 0 || (p[at+8]&255) < 5) return;
        int t = at+9;
        long pts = ((long)(p[t]&14)<<29) | ((long)(p[t+1]&255)<<22) |
            ((long)(p[t+2]&254)<<14) | ((long)(p[t+3]&255)<<7) | ((p[t+4]&254)>>1);
        if (id >= 0xe0 && id <= 0xef) video.add(pts);
        else if (id >= 0xc0 && id <= 0xdf) audio.add(pts);
    }
    Double originSeconds() {
        Double v = video.firstSeconds(), a = audio.firstSeconds();
        return v == null ? a : a == null ? v : Math.min(v,a);
    }
}
