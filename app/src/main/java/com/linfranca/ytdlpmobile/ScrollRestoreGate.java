package com.linfranca.ytdlpmobile;

/** Invalidates an unperformed restore as soon as the user interacts or config changes again. */
final class ScrollRestoreGate {
    private long generation;
    long schedule() { return ++generation; }
    void cancel() { generation++; }
    boolean allows(long token) { return token == generation; }
}
