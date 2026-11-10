package com.linfranca.ytdlpmobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingTsTailTest {
    private static final int SIZE = 188;

    private static byte[] packet(int pid, int stream, int length) {
        byte[] p = new byte[SIZE];
        p[0] = 0x47;
        p[1] = (byte)((pid >> 8) | (stream >= 0 ? 0x40 : 0));
        p[2] = (byte)pid;
        p[3] = 0x10;
        if (stream >= 0) {
            p[6] = 1;
            p[7] = (byte)stream;
            p[8] = (byte)(length >> 8);
            p[9] = (byte)length;
        }
        return p;
    }

    private static byte[] prefix() {
        var out = new ByteArrayOutputStream();
        for (int i = 0; i < 380; i++) out.writeBytes(packet(0x1fff, -1, 0));
        out.writeBytes(packet(256, 0xe0, 0));
        out.writeBytes(packet(257, 0xc0, 50));
        return out.toByteArray();
    }

    @Test public void dropsOpenMediaUnitsButKeepsInterleavedCompleteAudioAndOriginal() throws IOException {
        var out = new ByteArrayOutputStream();
        out.writeBytes(prefix());
        out.writeBytes(packet(256, 0xe0, 0)); // Open video unit.
        byte[] goodAudio = packet(257, 0xc0, 51);
        out.writeBytes(goodAudio);
        out.writeBytes(packet(256, -1, 0));
        out.writeBytes(Arrays.copyOf(packet(256, -1, 0), 12));
        Path file = Files.createTempFile("recording-tail", ".ts");
        try {
            byte[] original = out.toByteArray();
            Files.write(file, original);
            RecordingTsTail.Plan p = RecordingTsTail.plan(file.toFile());
            assertNotNull(p);
            assertEquals(0L, p.prefixBytes);
            assertEquals(prefix().length + SIZE, p.exportBytes());
            assertArrayEquals(goodAudio, Arrays.copyOfRange(p.suffix, p.suffix.length-SIZE, p.suffix.length));
            assertArrayEquals(original, Files.readAllBytes(file));
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void alsoOmitsIncompleteDeclaredAudioWithoutDroppingPreviousAudio() throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] good = prefix();
        out.writeBytes(good);
        out.writeBytes(packet(256, 0xe0, 0));
        out.writeBytes(packet(257, 0xc0, 700));
        out.writeBytes(Arrays.copyOf(packet(257, -1, 0), 40));
        Path file = Files.createTempFile("recording-tail", ".ts");
        try {
            Files.write(file, out.toByteArray());
            RecordingTsTail.Plan p = RecordingTsTail.plan(file.toFile());
            assertNotNull(p);
            assertArrayEquals(good, p.suffix);
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void leavesAlignedOrUnrecognizedMediaAlone() throws IOException {
        Path file = Files.createTempFile("recording-tail", ".ts");
        try {
            Files.write(file, prefix());
            assertNull(RecordingTsTail.plan(file.toFile()));
            byte[] bad = Arrays.copyOf(prefix(), prefix().length + 12);
            bad[5*SIZE] = 0;
            Files.write(file, bad);
            assertNull(RecordingTsTail.plan(file.toFile()));
        } finally { Files.deleteIfExists(file); }
    }
}
