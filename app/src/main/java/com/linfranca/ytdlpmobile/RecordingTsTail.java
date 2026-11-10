package com.linfranca.ytdlpmobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/** Salvage a packet-aligned export after an interrupted 188-byte transport-stream write. */
final class RecordingTsTail {
    private static final int PACKET = 188;
    private static final int MAX_PACKETS = 8192;

    static final class Plan {
        final long sourceBytes, prefixBytes;
        final byte[] suffix;
        Plan(long sourceBytes, long prefixBytes, byte[] suffix) {
            this.sourceBytes = sourceBytes;
            this.prefixBytes = prefixBytes;
            this.suffix = suffix;
        }
        long exportBytes() { return prefixBytes + suffix.length; }
    }

    private static final class Pes {
        final int start, required;
        final boolean video;
        final int starts;
        int received;
        Pes(int start, int required, boolean video, int starts) {
            this.start = start;
            this.required = required;
            this.video = video;
            this.starts = starts;
        }
    }

    static Plan plan(File source) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            long length = input.length();
            // An aligned file provides no evidence of a partial TS write. Leave it alone.
            if (length % PACKET == 0 || length < 65536) return null;
            for (int i = 0; i < 5; i++) {
                input.seek((long)i * PACKET);
                if (input.readUnsignedByte() != 0x47) return null;
            }
            int count = (int)Math.min(length / PACKET, MAX_PACKETS);
            long prefix = (length / PACKET - count) * PACKET;
            byte[] tail = new byte[count * PACKET];
            input.seek(prefix);
            input.readFully(tail);
            Map<Integer, Pes> latest = new HashMap<>();
            boolean hasVideo = false;
            for (int pos = 0; pos < tail.length; pos += PACKET) {
                if (tail[pos] != 0x47 || (tail[pos+1] & 0x80) != 0) return null;
                int control = (tail[pos+3] >> 4) & 3;
                if (control == 0) return null;
                if (control == 2) continue; // adaptation-only packet
                int data = pos + (control == 3 ? 5 + (tail[pos+4] & 255) : 4);
                if (data >= pos + PACKET) return null;
                int pid = pid(tail, pos);
                Pes pes = latest.get(pid);
                if ((tail[pos+1] & 0x40) != 0) {
                    if (data + 6 > pos + PACKET || tail[data] != 0 || tail[data+1] != 0 || tail[data+2] != 1) {
                        if (pes != null) return null; // Unrecognized start on a tracked media PID.
                        continue;
                    }
                    int stream = tail[data+3] & 255;
                    boolean video = stream >= 0xe0 && stream <= 0xef;
                    boolean audio = (stream >= 0xc0 && stream <= 0xdf) || stream == 0xbd;
                    if (!video && !audio) {
                        if (pes != null) return null;
                        continue;
                    }
                    if ((tail[pos+3] & 0xc0) != 0) return null; // Do not interpret scrambled media.
                    hasVideo |= video;
                    int declared = ((tail[data+4] & 255) << 8) | (tail[data+5] & 255);
                    pes = new Pes(pos, declared == 0 ? 0 : declared + 6, video, pes == null ? 1 : pes.starts + 1);
                    latest.put(pid, pes);
                }
                if (pes != null) pes.received += pos + PACKET - data;
            }
            if (!hasVideo || input.length() != length) return null;
            Map<Integer, Integer> omit = new HashMap<>();
            for (Map.Entry<Integer, Pes> entry : latest.entrySet()) {
                Pes p = entry.getValue();
                // Video PES commonly has no declared length. A hard cutoff gives
                // no closing evidence for that last unit, even if its TS packets fit.
                boolean unclosed = p.required == 0 || p.received < p.required;
                if (!unclosed) continue;
                if (p.starts < 2) return null; // Don't risk removing a track's only observed PES.
                omit.put(entry.getKey(), p.start);
            }
            ByteArrayOutputStream kept = new ByteArrayOutputStream(tail.length);
            for (int pos = 0; pos < tail.length; pos += PACKET) {
                Integer from = omit.get(pid(tail, pos));
                if (from == null || pos < from) kept.write(tail, pos, PACKET);
            }
            Plan plan = new Plan(length, prefix, kept.toByteArray());
            return plan.exportBytes() >= 65536 ? plan : null;
        }
    }

    private static int pid(byte[] packet, int pos) {
        return ((packet[pos+1] & 31) << 8) | (packet[pos+2] & 255);
    }

    private RecordingTsTail() {}
}
