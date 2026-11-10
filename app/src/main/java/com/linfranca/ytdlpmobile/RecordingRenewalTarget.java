package com.linfranca.ytdlpmobile;

import java.net.URI;

/** Shared path/scheme requirement for browser offers and recorder acceptance. */
final class RecordingRenewalTarget {
    static boolean sameRoute(String original, String fresh) {
        try {
            URI source = URI.create(original), candidate = URI.create(fresh);
            return ("http".equalsIgnoreCase(source.getScheme()) || "https".equalsIgnoreCase(source.getScheme())) &&
                source.getScheme().equalsIgnoreCase(candidate.getScheme()) &&
                source.getRawPath() != null && source.getRawPath().equals(candidate.getRawPath());
        } catch (Exception ignored) { return false; }
    }
}
