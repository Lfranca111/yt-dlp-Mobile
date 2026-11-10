package com.linfranca.ytdlpmobile;

import java.util.Locale;

/** Exact extractor outcomes only: an access or transport error never proves offline. */
final class MonitoringOfflinePolicy {
    static boolean confirmed(String message) {
        String s=message.toLowerCase(Locale.ROOT).trim();
        if(s.startsWith("error: ")) s=s.substring(7).trim();
        s=s.replaceFirst("^\\[[a-z0-9:_-]+\\]\\s+[^\\r\\n:]{1,180}:\\s*", "");
        return s.equals("the channel is not currently live") || s.equals("the channel is not currently live.") ||
            s.equals("the channel is not live") || s.equals("the channel is not live.");
    }
    private MonitoringOfflinePolicy() {}
}
