package com.linfranca.ytdlpmobile;

import java.net.HttpURLConnection;
import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounded preflight for HLS requests that previously went straight to hlsnative. */
final class LiveHlsRecordingPlan {
    enum Kind { LIVE, EXISTING, CUSTOM }
    final Kind kind;
    final String video, audio, detail;
    LiveHlsRecordingPlan(Kind kind, String video, String audio, String detail) {
        this.kind = kind; this.video = video; this.audio = audio; this.detail = detail;
    }
    interface Loader { String load(String url) throws Exception; }
    static LiveHlsRecordingPlan inspect(String url, LiveHlsRelay.Access access) {
        return inspect(url, access, null);
    }
    static LiveHlsRecordingPlan inspect(String url, LiveHlsRelay.Access access, java.util.function.Consumer<String> diagnostic) {
        try { return resolve(url, source -> {
            String body=read(source, access);
            MouflonDiagnostics.record(body, diagnostic);
            return body;
        }); }
        catch (Exception error) { return new LiveHlsRecordingPlan(Kind.EXISTING, url, "",
            "HLS preflight inconclusive; error type=" + error.getClass().getSimpleName()); }
    }
    static LiveHlsRecordingPlan inspectMonitoredLive(String url, LiveHlsRelay.Access access) {
        try {
            return resolve(url, source -> {
                String body = read(source, access, null, true);
                if (!body.trim().startsWith("#EXTM3U")) throw new LiveHlsStartupFailure("Live playlist", 200);
                if (!body.contains("#EXT-X-ENDLIST") && !body.contains("#EXT-X-STREAM-INF:") &&
                    !body.contains("#EXT-X-MOUFLON:") && !body.contains("#EXTINF:"))
                    throw new LiveHlsStartupFailure("Live playlist with no complete segments", 200);
                return body;
            });
        } catch (LiveHlsStartupFailure failure) {
            throw failure;
        } catch (java.io.IOException failure) {
            throw new LiveHlsStartupFailure("Live playlist", -1);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Live playlist could not be inspected");
        }
    }

    static LiveHlsRecordingPlan resolve(String url, Loader loader) throws Exception {
        url = LiveHlsRelay.cleanPlaylistUrl(url).toString();
        String body = loader.load(url).trim();
        if (!body.startsWith("#EXTM3U")) return existing(url, "Response is not HLS");
        if (body.contains("#EXT-X-MOUFLON:")) return custom(url);
        String selected = url, audio = "";
        if (body.contains("#EXT-X-STREAM-INF:")) {
            // Select one rendition deterministically; don't mix qualities or audio groups.
            String[] lines = body.split("\\r?\\n");
            String group = null;
            long best = -1;
            for (int i = 0; i < lines.length - 1; i++) {
                String line = lines[i].trim();
                if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
                int next = i + 1;
                while (next < lines.length && lines[next].trim().isEmpty()) next++;
                if (next == lines.length || lines[next].trim().startsWith("#")) continue;
                long bandwidth = 0;
                java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)").matcher(line.substring(line.indexOf(':') + 1));
                if (match.find()) bandwidth = Long.parseLong(match.group(1));
                if (bandwidth > best) {
                    selected = httpUrl(url, lines[next].trim()); best = bandwidth;
                    group = attribute(line, "AUDIO");
                }
            }
            if (best < 0) return existing(url, "No usable master rendition");
            if (group != null) {
                for (String line : lines) if (line.startsWith("#EXT-X-MEDIA:") &&
                        line.contains("TYPE=AUDIO") && group.equals(attribute(line, "GROUP-ID"))) {
                    String path = attribute(line, "URI");
                    if (path != null) { audio = httpUrl(url, path); break; }
                }
                if (audio.isEmpty()) return existing(url, "Selected audio group could not be resolved");
            }
            body = loader.load(selected).trim();
        }
        if (body.contains("#EXT-X-MOUFLON:")) return custom(url);
        if (!media(body)) return existing(url, "Selected playlist is ended, empty, or unsupported");
        if (!audio.isEmpty()) {
            String audioBody = loader.load(audio).trim();
            if (audioBody.contains("#EXT-X-MOUFLON:")) return custom(url);
            if (!media(audioBody)) return existing(url, "Selected audio playlist is ended or unsupported");
        }
        return new LiveHlsRecordingPlan(Kind.LIVE, selected, audio,
            "Live-capable HLS media verified; separate audio=" + !audio.isEmpty() +
            "; partial segments=" + body.contains("#EXT-X-PART:") +
            "; initialization file=" + body.contains("#EXT-X-MAP:") +
            "; segment verification follows before capture");
    }
    private static boolean media(String body) {
        return body.startsWith("#EXTM3U") && !body.contains("#EXT-X-ENDLIST") &&
            body.contains("#EXTINF:") && !body.contains("#EXT-X-BYTERANGE") &&
            (!body.contains("#EXT-X-KEY:") || !java.util.regex.Pattern.compile("#EXT-X-KEY:(?!METHOD=NONE)").matcher(body).find());
    }
    private static LiveHlsRecordingPlan existing(String url, String detail) {
        return new LiveHlsRecordingPlan(Kind.EXISTING, url, "", detail + "; retaining extractor path");
    }
    private static LiveHlsRecordingPlan custom(String url) {
        return new LiveHlsRecordingPlan(Kind.CUSTOM, url, "",
            "Custom MOUFLON playlist detected; ordinary segment URLs may be placeholders. Trying the original page's site extractor rather than guessing URL or byte transformations");
    }
    private static String attribute(String line, String name) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?:^|[:,])" + name + "=\"([^\"]*)\"").matcher(line);
        return match.find() ? match.group(1) : null;
    }
    private static String httpUrl(String base, String relative) {
        URI uri = URI.create(base).resolve(relative);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("Unsupported URI scheme");
        return uri.toString();
    }
    static String read(String source, LiveHlsRelay.Access access) throws Exception {
        return read(source, access, null);
    }
    static String read(String source, LiveHlsRelay.Access access, java.util.function.Consumer<String> diagnostic) throws Exception {
        return read(source,access,diagnostic,false);
    }

    private static String read(String source, LiveHlsRelay.Access access, java.util.function.Consumer<String> diagnostic,
                               boolean classifyStartup) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(source).toURL().openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        if (diagnostic != null) {
            connection.setInstanceFollowRedirects(false);
            diagnostic.accept("mouflon_preflight_playlist_request; header names=" + access.extra.keySet() + "; cookies present=" + !access.cookie.isEmpty());
        }
        try {
            for (Map.Entry<String,String> header : access.extra.entrySet())
                if (!header.getKey().equalsIgnoreCase("Host") && !header.getKey().equalsIgnoreCase("Range"))
                    connection.setRequestProperty(header.getKey(), header.getValue());
            if (!access.agent.isEmpty()) connection.setRequestProperty("User-Agent", access.agent);
            if (!access.referer.isEmpty()) connection.setRequestProperty("Referer", access.referer);
            if (!access.cookie.isEmpty()) connection.setRequestProperty("Cookie", access.cookie);
            int status = connection.getResponseCode();
            if (diagnostic != null) diagnostic.accept("mouflon_preflight_playlist_response; HTTP=" + status);
            if (status != 200) {
                if (classifyStartup) throw new LiveHlsStartupFailure("Live playlist", status);
                throw new java.io.IOException("Playlist HTTP=" + status);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
                long deadline = System.nanoTime() + 5_000_000_000L; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 512 * 1024 || System.nanoTime() > deadline)
                        throw new java.io.IOException("Playlist bounds exceeded");
                    out.write(buffer, 0, count);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            if (diagnostic != null) diagnostic.accept("mouflon_preflight_playlist_failed; error type=" + error.getClass().getSimpleName());
            throw error;
        } finally { connection.disconnect(); }
    }
}
