package com.linfranca.ytdlpmobile;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RecordingShutdownTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void retriesFromFreshSnapshotAfterSourceGrows() throws Exception {
        File file = recording();
        AtomicInteger exports = new AtomicInteger(), retries = new AtomicInteger();
        long saved = RecordingSaveGuard.export(file, true, retries::incrementAndGet, snapshot -> {
            if (exports.incrementAndGet() == 1) append(file);
            snapshot.verify(file);
            return snapshot.bytes;
        });
        assertEquals(4, saved);
        assertEquals(2, exports.get());
        assertEquals(1, retries.get());
    }

    @Test public void changingSourceIsNotPublishedAfterSecondAttempt() throws Exception {
        File file = recording();
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RecordingSaveGuard.Changed.class, () ->
            RecordingSaveGuard.export(file, false, () -> {}, snapshot -> {
                attempts.incrementAndGet();
                append(file);
                snapshot.verify(file);
                fail("Unstable export must not be committed");
                return null;
            }));
        assertEquals(2, attempts.get());
        assertEquals(5, file.length()); // original file remains available for recovery
    }

    @Test public void unrelatedExportFailureIsNotRetried() throws Exception {
        File file = recording();
        IOException storage = new IOException("Storage full");
        assertSame(storage, assertThrows(IOException.class, () ->
            RecordingSaveGuard.export(file, false, () -> fail("Must not retry storage errors"),
                snapshot -> { throw storage; })));
    }

    @Test public void retriesCancellationUntilTheProcessRegisters() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertTrue(RecordingStopSignal.cancelWhenRegistered(() -> "capture",
            id -> calls.incrementAndGet() == 3, () -> true, 500, 1));
        assertEquals(3, calls.get());
    }

    @Test public void stopsPollingWhenTheTaskIsOverOrDeadlineExpires() throws Exception {
        assertFalse(RecordingStopSignal.cancelWhenRegistered(() -> "capture",
            id -> { fail("Completed task should not be signaled"); return true; }, () -> false, 500, 1));
        assertFalse(RecordingStopSignal.cancelWhenRegistered(() -> null,
            id -> { fail("No process available"); return true; }, () -> true, 15, 2));
    }

    @Test public void forceStopsAWriterThatIgnoresTheFirstSignal() throws Exception {
        org.junit.Assume.assumeTrue(new File("/bin/sh").canExecute());
        Process writer = new ProcessBuilder("/bin/sh", "-c", "trap '' TERM; echo ready; while :; do :; done").start();
        try {
            assertEquals("ready", new BufferedReader(new InputStreamReader(writer.getInputStream())).readLine());
            AtomicReference<Runnable> pending = new AtomicReference<>();
            AtomicInteger force = new AtomicInteger();
            RecordingStopSignal.stopPaired(writer, pending::set, force::incrementAndGet);
            assertTrue(writer.isAlive());
            assertNotNull(pending.get());
            pending.get().run();
            assertTrue(writer.waitFor(2, TimeUnit.SECONDS));
            assertEquals(1, force.get());
        } finally {
            writer.destroyForcibly();
        }
    }

    private File recording() throws IOException {
        File file = folder.newFile();
        Files.write(file.toPath(), new byte[] {1, 2, 3});
        return file;
    }

    private static void append(File file) throws IOException {
        Files.write(file.toPath(), new byte[] {4}, StandardOpenOption.APPEND);
    }
}
