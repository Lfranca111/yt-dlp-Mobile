package com.linfranca.ytdlpmobile;

import java.io.File;
import java.io.IOException;

/** Guard the exact source copied into a pending export. Never publish a moving target. */
final class RecordingSaveGuard {
    static final class Changed extends IOException {
        Changed() { super("Recording changed during finalization"); }
    }

    static final class Snapshot {
        final long bytes;
        private final long modified;

        Snapshot(File file) throws Changed {
            bytes = file.length();
            modified = file.lastModified();
            verify(file);
        }

        void verify(File file) throws Changed {
            if (bytes <= 0 || !file.isFile() || bytes != file.length() || modified != file.lastModified())
                throw new Changed();
        }
    }

    interface Export<T> { T save(Snapshot snapshot) throws Exception; }

    static <T> T export(File source, boolean waitForQuiet, Runnable retry, Export<T> save) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                Snapshot snapshot = new Snapshot(source);
                if (waitForQuiet || attempt > 0) {
                    Thread.sleep(200);
                    snapshot.verify(source);
                }
                return save.save(snapshot);
            } catch (Changed changed) {
                if (attempt != 0) throw changed;
                retry.run();
            }
        }
    }

    private RecordingSaveGuard() {}
}
