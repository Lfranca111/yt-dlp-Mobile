package com.linfranca.ytdlpmobile;

/** Estimates are lower bounds, never a reservation or a promise that an unknown stream fits. */
final class StoragePolicy {
    static final long RESERVE=32L*1024*1024;
    static final long UNKNOWN_START=128L*1024*1024;
    static long required(long additional, int copies) {
        if(additional<=0) return UNKNOWN_START;
        if(additional>(Long.MAX_VALUE-RESERVE)/Math.max(1,copies)) return Long.MAX_VALUE;
        return additional*Math.max(1,copies)+RESERVE;
    }
    static boolean insufficient(long available,long required) { return available>=0 && available<required; }
    static boolean noSpace(String detail) {
        String s=detail==null?"":detail.toLowerCase(java.util.Locale.ROOT);
        return s.contains("enospc") || s.contains("no space left on device") || s.contains("not enough storage space") ||
            s.contains("insufficient disk space") || s.contains("insufficient storage") || s.contains("disk full") || s.contains("edquot");
    }
    private StoragePolicy() {}
}
