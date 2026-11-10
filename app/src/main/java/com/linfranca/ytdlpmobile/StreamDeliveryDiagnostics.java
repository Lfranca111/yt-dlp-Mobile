package com.linfranca.ytdlpmobile;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Observation only. Never selects a recorder or logs URLs, keys, cookies, or headers. */
final class StreamDeliveryDiagnostics {
    static String describeUrl(String url) {
        String path;
        try { path = URI.create(url).getPath().toLowerCase(Locale.ROOT); }
        catch (Exception e) { return "Delivery hint: unknown; invalid or absent URL."; }
        String type = path.endsWith(".m3u8") ? "HLS" : path.endsWith(".mpd") ? "DASH" :
            path.matches(".*\\.(mp4|m4v|webm)$") ? "direct media" : "unknown/extractor page";
        return "Delivery hint: " + type + "; URL naming is not proof of live status or track contents.";
    }

    static String describe(String url, String body) {
        String text = body.trim();
        if (text.startsWith("#EXTM3U")) {
            boolean master = text.contains("#EXT-X-STREAM-INF:");
            boolean end = text.contains("#EXT-X-ENDLIST");
            boolean ll = text.contains("#EXT-X-PART:") || text.contains("#EXT-X-SERVER-CONTROL:") || text.contains("#EXT-X-PRELOAD-HINT:");
            int video = 0, audio = 0, segments = 0;
            for (String line : text.split("\\r?\\n")) {
                if (line.startsWith("#EXT-X-STREAM-INF:")) video++;
                if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) audio++;
                if (line.startsWith("#EXTINF:")) segments++;
            }
            return "Playlist evidence: HLS " + (master ? "master" : "media") + "; variants=" + video +
                ", audio declarations=" + audio + ", complete segments=" + segments +
                ", ENDLIST=" + end + ", low-latency tags=" + ll +
                ", custom MOUFLON tags=" + text.contains("#EXT-X-MOUFLON:") +
                ", initialization file=" + text.contains("#EXT-X-MAP:") +
                ", encryption tags=" + text.contains("#EXT-X-KEY:") +
                ", byte-range tags=" + text.contains("#EXT-X-BYTERANGE:") +
                ", program time tags=" + text.contains("#EXT-X-PROGRAM-DATE-TIME:") + ". " +
                (master ? "Live/end status requires inspecting selected media playlists." :
                 end ? "Playlist marks its listed presentation ended." : "No ENDLIST observed; live-capable, not proof that new segments will arrive.");
        }
        if (text.contains("<MPD") || text.contains(":MPD")) {
            return "Playlist evidence: DASH; dynamic=" + (text.contains("type=\"dynamic\"") || text.contains("type='dynamic'")) +
                ", content protection=" + text.contains("ContentProtection") + "; tracks not fully inspected.";
        }
        return describeUrl(url) + " Response body is not a recognized HLS/DASH manifest.";
    }

    static final class Verification {
        final boolean valid;
        final String detail;
        Verification(boolean valid, String detail) { this.valid = valid; this.detail = detail; }
    }

    /** Recovery preflight only; segment acceptance remains verified by LiveHlsRelay. */
    static Verification verifyHls(String url, String agent, String referer, String cookie, Map<String,String> headers) {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return new Verification(false, "invalid URL scheme");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            for (Map.Entry<String,String> entry : headers.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("host") && !entry.getKey().equalsIgnoreCase("range"))
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (!agent.isEmpty()) connection.setRequestProperty("User-Agent", headers.getOrDefault("User-Agent", agent));
            if (!referer.isEmpty()) connection.setRequestProperty("Referer", headers.getOrDefault("Referer", referer));
            String sentCookie = cookie;
            for (Map.Entry<String,String> entry : headers.entrySet())
                if (entry.getKey().equalsIgnoreCase("cookie")) sentCookie = entry.getValue();
            if (!sentCookie.isEmpty()) connection.setRequestProperty("Cookie", sentCookie);
            int status = connection.getResponseCode();
            if (status != 200) return new Verification(false, "HTTP=" + status);
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = new byte[262144]; int count = 0, read;
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (count < bytes.length && (read = in.read(bytes, count, bytes.length - count)) != -1) {
                    count += read;
                    if (System.nanoTime() > deadline) return new Verification(false, "verification deadline exceeded");
                }
                String body = new String(bytes, 0, count, StandardCharsets.UTF_8).trim();
                boolean valid = body.startsWith("#EXTM3U") && !body.contains("#EXT-X-ENDLIST") &&
                    (body.contains("#EXTINF:") || body.contains("#EXT-X-PART:") || body.contains("#EXT-X-STREAM-INF:"));
                return new Verification(valid, "HTTP=200; HLS=" + body.startsWith("#EXTM3U") +
                    "; ENDLIST=" + body.contains("#EXT-X-ENDLIST") + "; playlist accepted=" + valid);
            }
        } catch (Exception e) { return new Verification(false, "error type=" + e.getClass().getSimpleName()); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
