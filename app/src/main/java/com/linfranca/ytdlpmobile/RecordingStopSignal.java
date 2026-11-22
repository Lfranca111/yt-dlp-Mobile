package com.linfranca.ytdlpmobile;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** yt-dlp can register its process after Stop was tapped. Give registration a short deadline. */
final class RecordingStopSignal {
    static boolean cancelWhenRegistered(Supplier<String> id, Predicate<String> cancel, BooleanSupplier stopping,
                                        long timeoutMs, long pollMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (stopping.getAsBoolean()) {
            String current = id.get();
            if (current != null && cancel.test(current)) return true;
            if (System.nanoTime() >= deadline) break;
            Thread.sleep(pollMs);
        }
        return false;
    }

    static void stopPaired(Process writer, Consumer<Runnable> scheduleForce, Runnable forced) {
        if (!writer.isAlive()) return;
        writer.destroy();
        scheduleForce.accept(() -> {
            if (!writer.isAlive()) return;
            writer.destroyForcibly();
            forced.run();
        });
    }

    private RecordingStopSignal() {}
}
