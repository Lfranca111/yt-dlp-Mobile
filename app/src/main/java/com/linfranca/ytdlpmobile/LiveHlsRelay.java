package com.linfranca.ytdlpmobile;

import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fetches completed LL-HLS segments and serves a bounded local HLS window to FFmpeg. */
final class LiveHlsRelay implements Closeable {
    private static final int MAX_PLAYLIST = 512 * 1024;
    private static final int MAX_SEGMENT = 12 * 1024 * 1024;
    private static final int MAX_SEGMENTS = 48;
    private static final long MAX_TRACK_BYTES = 32L * 1024 * 1024;
    private final ServerSocket server;
    private final Track video;
    private final Track audio;
    private final BufferedWriter fullLog;
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final long started = System.currentTimeMillis();
    private long loggedBytes;
    private boolean logLimitReported;
    private volatile boolean closed;

    static final class Access {
        final String agent, referer, cookie;
        final Map<String, String> extra;
        Access(String agent, String referer, String cookie, Map<String, String> extra) {
            this.agent = agent; this.referer = referer; this.cookie = cookie;
            this.extra = extra;
        }
    }

    private static final class Route {
        final URI source;
        final Access access;
        Route(URI source, Access access) { this.source = source; this.access = access; }
    }

    private static final class Segment {
        final long sequence;
        final byte[] bytes;
        final double duration;
        final boolean discontinuity;
        final Long programTimeMs;
        final String extension;
        Segment(long sequence, byte[] bytes, double duration, boolean discontinuity,
                Long programTimeMs, String extension) {
            this.sequence = sequence; this.bytes = bytes;
            this.duration = duration; this.discontinuity = discontinuity;
            this.programTimeMs = programTimeMs;
            this.extension = extension;
        }
    }

    static final class TimeGap {
        final long startMs, endMs;
        TimeGap(long startMs, long endMs) { this.startMs = startMs; this.endMs = endMs; }
    }

    /** Source clock of the first segments and actual holes in the video rendition. */
    static final class AudioTimeline {
        final Long videoStartMs, audioStartMs, videoEndMs, audioEndMs;
        final List<TimeGap> videoGaps, audioGaps, videoClockJumps, audioClockJumps;
        AudioTimeline(Long videoStartMs, Long audioStartMs, List<TimeGap> videoGaps) {
            this(videoStartMs, audioStartMs, videoGaps, List.of());
        }
        AudioTimeline(Long videoStartMs, Long audioStartMs,
                List<TimeGap> videoGaps, List<TimeGap> audioGaps) {
            this(videoStartMs, audioStartMs, videoGaps, audioGaps, List.of(), List.of());
        }
        AudioTimeline(Long videoStartMs, Long audioStartMs,
                List<TimeGap> videoGaps, List<TimeGap> audioGaps,
                List<TimeGap> videoClockJumps, List<TimeGap> audioClockJumps) {
            this(videoStartMs, audioStartMs, null, null,
                videoGaps, audioGaps, videoClockJumps, audioClockJumps);
        }
        AudioTimeline(Long videoStartMs, Long audioStartMs, Long videoEndMs, Long audioEndMs,
                List<TimeGap> videoGaps, List<TimeGap> audioGaps,
                List<TimeGap> videoClockJumps, List<TimeGap> audioClockJumps) {
            this.videoStartMs = videoStartMs;
            this.audioStartMs = audioStartMs;
            this.videoEndMs = videoEndMs;
            this.audioEndMs = audioEndMs;
            this.videoGaps = videoGaps;
            this.audioGaps = audioGaps;
            this.videoClockJumps = videoClockJumps;
            this.audioClockJumps = audioClockJumps;
        }

        String filter() {
            if (videoStartMs == null || audioStartMs == null) return null;
            // Keep the captured packet clock. FFmpeg has already placed both
            // renditions on one timeline; resetting audio PTS here makes it
            // start at zero again, even when video starts several seconds in.
            // Resampling fills missing audio samples without shifting later speech.
            StringBuilder out = new StringBuilder("aresample=async=1:first_pts=0");
            long offset = audioStartMs - videoStartMs;
            if (offset < 0) {
                // Discard the early audio, retaining the timestamps of every
                // remaining sample so speech still matches the video clock.
                out.append(",atrim=start=").append(seconds(-offset / 1000.0));
            }
            // Filter timestamps are relative to the earliest track. The video
            // origin is later only when audio was the first track to start.
            double videoOrigin = Math.max(0, -offset) / 1000.0;
            for (TimeGap gap : videoGaps) {
                double start = videoOrigin + (gap.startMs - videoStartMs) / 1000.0;
                double end = videoOrigin + (gap.endMs - videoStartMs) / 1000.0;
                if (end > 0 && end > start) {
                    out.append(",volume=enable='between(t,").append(seconds(Math.max(0, start)))
                        .append(',').append(seconds(end)).append(")':volume=0");
                }
            }
            out.append(",apad"); // Keep silent audio through the final video frame.
            return out.toString();
        }

        private static String seconds(double seconds) {
            return String.format(java.util.Locale.ROOT, "%.3f", seconds);
        }
    }

    private static final class Entry {
        final long sequence;
        final URI uri;
        final double duration;
        final boolean discontinuity;
        final Long programTimeMs;
        Entry(long sequence, URI uri, double duration, boolean discontinuity, Long programTimeMs) {
            this.sequence = sequence; this.uri = uri;
            this.duration = duration; this.discontinuity = discontinuity;
            this.programTimeMs = programTimeMs;
        }
    }

    private static final class Playlist {
        final List<Entry> entries = new ArrayList<>();
        URI init;
        int target = 2;
        boolean ended;
    }

    private final class Track {
        final String name;
        volatile Route route;
        final LinkedHashMap<Long, Segment> cache = new LinkedHashMap<>();
        URI initUri;
        byte[] initBytes;
        volatile long next = -1;
        long cachedBytes;
        volatile int target = 2;
        volatile boolean ended;
        volatile int lastStatus;
        volatile String unsupported;
        int failures;
        volatile long goodSegments;
        volatile long lastPlaylistResponseMs, lastPlaylistSuccessMs;
        volatile long lastSegmentResponseMs, lastSegmentSuccessMs;
        volatile int lastPlaylistHttp = -1, lastSegmentHttp = -1;
        volatile int renewals;
        volatile boolean awaitingRenewalSegment;
        Long firstSourceTimeMs, lastSourceEndMs;
        Long firstServedTimeMs;
        final List<TimeGap> sourceGaps = new ArrayList<>();
        final List<TimeGap> sourceClockJumps = new ArrayList<>();

        Track(String name, String url, Access access) {
            this.name = name;
            this.route = new Route(cleanPlaylistUrl(url), access);
        }

        synchronized boolean ready() { return !cache.isEmpty(); }
        synchronized int bufferedSegments() { return cache.size(); }

        synchronized Long firstProgramTimeMs() {
            return firstSourceTimeMs;
        }

        synchronized byte[] segment(long seq) {
            Segment segment = cache.get(seq);
            if (segment != null && firstServedTimeMs == null) firstServedTimeMs = segment.programTimeMs;
            return segment == null ? null : segment.bytes;
        }

        synchronized String playlist() {
            StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n");
            out.append("#EXT-X-TARGETDURATION:").append(target).append('\n');
            long first = cache.isEmpty() ? Math.max(0, next) : cache.keySet().iterator().next();
            out.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n');
            if (initBytes != null) out.append("#EXT-X-MAP:URI=\"/").append(token)
                .append('/').append(name).append("/init.mp4\"\n");
            for (Segment segment : cache.values()) {
                if (segment.discontinuity) out.append("#EXT-X-DISCONTINUITY\n");
                if (segment.programTimeMs != null) out.append("#EXT-X-PROGRAM-DATE-TIME:")
                    .append(java.time.Instant.ofEpochMilli(segment.programTimeMs)).append('\n');
                out.append("#EXTINF:").append(segment.duration).append(",\n/")
                    .append(token).append('/')
                    .append(name).append('/').append(segment.sequence)
                    .append(segment.extension).append('\n');
            }
            if (ended) out.append("#EXT-X-ENDLIST\n");
            return out.toString();
        }

        void run() {
            while (!closed) {
                try {
                    Route current = route;
                    Response response = fetch(name, "playlist", current.source, current.access, MAX_PLAYLIST, -1);
                    if (current != route) continue;
                    lastStatus = response.status;
                    if (response.status != 200 || response.bytes == null) failures++;
                    if (response.status == 200 && response.bytes != null) {
                        failures = 0;
                        Playlist playlist = parse(current.source, new String(response.bytes, StandardCharsets.UTF_8));
                        if (renewals > 0 && next >= 0 && !playlist.entries.isEmpty() &&
                            playlist.entries.get(playlist.entries.size() - 1).sequence + 30 < next) {
                            unsupported = "renewed playlist reset segment sequence";
                            event(name + " renewal rejected: segment timeline reset; stop and save the previous portion");
                            break;
                        }
                        if (playlist.ended && next < 0) {
                            unsupported = "finite playlist";
                            break;
                        }
                        if (playlist.entries.isEmpty()) {
                            unsupported = "no media segments";
                            break;
                        }
                        target = playlist.target;
                        if (playlist.init != null && !playlist.init.equals(initUri)) {
                            Response init = fetch(name, "init", playlist.init, current.access, MAX_SEGMENT, -1);
                            if (init.status == 200 && init.bytes != null) {
                                synchronized (this) {
                                    if (current != route) continue;
                                    if (renewals > 0 && initBytes != null &&
                                        !java.util.Arrays.equals(initBytes, init.bytes)) {
                                        event(name + " renewed stream has a different initialization segment; continuity cannot be verified");
                                        unsupported = "renewed stream changed codec or timeline";
                                        break;
                                    }
                                    initUri = playlist.init; initBytes = init.bytes;
                                    if (renewals == 0) { cache.clear(); cachedBytes = 0; next = -1; }
                                }
                            }
                        }
                        if (unsupported != null) break;
                        if (playlist.init == null || playlist.init.equals(initUri)) {
                            if (next < 0 && !playlist.entries.isEmpty()) {
                                // Both renditions may have different segment lengths.
                                // Start at each playlist's earliest still-advertised
                                // segment so their initial media time overlaps.
                                next = playlist.entries.get(0).sequence;
                            }
                            for (Entry entry : playlist.entries) {
                                if (current != route) break;
                                if (closed || entry.sequence < next) continue;
                                if (entry.sequence > next) {
                                    event(name + " missing sequences " + next + ".." + (entry.sequence - 1));
                                    next = entry.sequence;
                                }
                                Response part = fetch(name, "segment", entry.uri, current.access, MAX_SEGMENT, entry.sequence);
                                if (current != route) break;
                                if (part.status != 200 || part.bytes == null) {
                                    failures++;
                                    break;
                                }
                                failures = 0;
                                if (awaitingRenewalSegment) {
                                    awaitingRenewalSegment = false;
                                    event(name + " renewal verified: first refreshed segment HTTP 200");
                                }
                                synchronized (this) {
                                    if (entry.programTimeMs != null) {
                                        if (firstSourceTimeMs == null) firstSourceTimeMs = entry.programTimeMs;
                                        if (lastSourceEndMs != null) {
                                            long deltaMs = entry.programTimeMs - lastSourceEndMs;
                                            if (NativeMedia.gapKind(deltaMs) == 1) {
                                                // A skipped sequence alone does not prove lost media.
                                                sourceGaps.add(new TimeGap(lastSourceEndMs, entry.programTimeMs));
                                                event(name + " source gap " + deltaMs + "ms" +
                                                    (name.equals("video") ? "; final audio will be muted over missing video" : ""));
                                            } else if (NativeMedia.gapKind(deltaMs) == 2) {
                                                // Keep clock jumps for later repair, but do not
                                                // automatically treat them as missing media.
                                                sourceClockJumps.add(new TimeGap(lastSourceEndMs, entry.programTimeMs));
                                                event(name + " source clock jump " + deltaMs + "ms; review timing report");
                                            }
                                        }
                                        lastSourceEndMs = entry.programTimeMs + Math.round(entry.duration * 1000);
                                    } else lastSourceEndMs = null;
                                    cache.put(entry.sequence, new Segment(entry.sequence, part.bytes,
                                        entry.duration, entry.discontinuity, entry.programTimeMs,
                                        segmentExtension(entry.uri)));
                                    cachedBytes += part.bytes.length;
                                    goodSegments++;
                                    while (cache.size() > MAX_SEGMENTS || cachedBytes > MAX_TRACK_BYTES) {
                                        Long oldest = cache.keySet().iterator().next();
                                        cachedBytes -= cache.remove(oldest).bytes.length;
                                    }
                                    next = entry.sequence + 1;
                                }
                            }
                            ended = playlist.ended && (playlist.entries.isEmpty() ||
                                next > playlist.entries.get(playlist.entries.size() - 1).sequence);
                        }
                    }
                    if (ended) break;
                    long interval = NativeMedia.retry(target, failures, false);
                    synchronized (this) { wait(interval); }
                } catch (UnsupportedOperationException unsupportedPlaylist) {
                    unsupported = unsupportedPlaylist.getMessage();
                    break;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); break;
                } catch (Exception failure) {
                    event(name + " playlist parse/network failure (" +
                        failure.getClass().getSimpleName() + "); retrying");
                    failures++;
                    try { synchronized (this) { wait(NativeMedia.retry(target, failures, true)); } } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }
    }

    private static final class Response {
        final int status;
        final byte[] bytes;
        Response(int status, byte[] bytes) { this.status = status; this.bytes = bytes; }
    }

    LiveHlsRelay(String videoUrl, String audioUrl, Access videoAccess, Access audioAccess,
                 File diagnosticFile) throws Exception {
        video = new Track("video", videoUrl, videoAccess);
        audio = new Track("audio", audioUrl, audioAccess);
        fullLog = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(diagnosticFile), StandardCharsets.UTF_8));
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        start("live-hls-server", this::serve);
        start("live-hls-video", video::run);
        start("live-hls-audio", audio::run);
        long deadline = System.currentTimeMillis() + 12_000;
        long firstReady = -1;
        while (!closed && System.currentTimeMillis() < deadline) {
            if (video.ready() && audio.ready()) {
                if (firstReady < 0) firstReady = System.currentTimeMillis();
                if ((video.bufferedSegments() >= 2 && audio.bufferedSegments() >= 2) ||
                    System.currentTimeMillis() - firstReady >= 1500) return;
            }
            if (video.lastStatus == 403 || audio.lastStatus == 403 ||
                video.unsupported != null || audio.unsupported != null) break;
            Thread.sleep(100);
        }
        String failure = "Live stream could not start: video HTTP " + video.lastStatus +
            ", audio HTTP " + audio.lastStatus + "; " +
            (video.unsupported != null ? video.unsupported :
                audio.unsupported != null ? audio.unsupported : "no usable segments") + ".";
        close();
        throw new IllegalStateException(failure);
    }

    String videoUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/" + token + "/video.m3u8"; }
    String audioUrl() { return "http://127.0.0.1:" + server.getLocalPort() + "/" + token + "/audio.m3u8"; }

    boolean renew(String kind, String url, Access access) {
        Track track = "video".equals(kind) ? video : "audio".equals(kind) ? audio : null;
        if (closed || track == null || access == null) {
            event("renewal rejected: recorder stopped or track missing");
            return false;
        }
        URI fresh;
        try { fresh = cleanPlaylistUrl(url); } catch (Exception ignored) {
            event(kind + " renewal rejected: invalid playlist URI");
            return false;
        }
        synchronized (track) {
            if (track.ended || track.unsupported != null) {
                event(kind + " renewal rejected: live track is no longer running");
                return false;
            }
            URI previous = track.route.source;
            // A credential change for the same rendition is safe to try. Never
            // substitute an ad, another video, or a different quality variant.
            if (!previous.getScheme().equalsIgnoreCase(fresh.getScheme()) ||
                !previous.getRawPath().equals(fresh.getRawPath())) {
                event(kind + " renewal rejected: different rendition or scheme");
                return false;
            }
            Access old = track.route.access;
            if (previous.equals(fresh) && old.cookie.equals(access.cookie) &&
                old.referer.equals(access.referer) && old.agent.equals(access.agent) &&
                old.extra.equals(access.extra)) {
                event(kind + " renewal skipped: current credentials unchanged");
                return false;
            }
            track.route = new Route(fresh, access);
            track.renewals++;
            track.awaitingRenewalSegment = true;
            track.failures = 0;
            track.notifyAll();
        }
        event(kind + " renewal route updated; waiting for refreshed media segment");
        return true;
    }

    void logDiagnosticEvent(String safeEvent) { event("app " + safeEvent); }

    /** Difference between the first buffered audio and video segment origins, if provided. */
    Double initialAudioOffsetSeconds() {
        Long videoStart = video.firstProgramTimeMs();
        Long audioStart = audio.firstProgramTimeMs();
        return videoStart == null || audioStart == null ? null :
            (audioStart - videoStart) / 1000.0;
    }

    AudioTimeline audioTimeline() {
        Long videoStart, videoEnd;
        List<TimeGap> gaps, audioGaps, videoJumps, audioJumps;
        synchronized (video) {
            videoStart = video.firstServedTimeMs != null ? video.firstServedTimeMs : video.firstSourceTimeMs;
            videoEnd = video.lastSourceEndMs;
            gaps = new ArrayList<>(video.sourceGaps);
            videoJumps = new ArrayList<>(video.sourceClockJumps);
        }
        Long audioStart, audioEnd;
        synchronized (audio) {
            audioStart = audio.firstServedTimeMs != null ? audio.firstServedTimeMs : audio.firstSourceTimeMs;
            audioEnd = audio.lastSourceEndMs;
            audioGaps = new ArrayList<>(audio.sourceGaps);
            audioJumps = new ArrayList<>(audio.sourceClockJumps);
        }
        return new AudioTimeline(videoStart, audioStart, videoEnd, audioEnd,
            gaps, audioGaps, videoJumps, audioJumps);
    }

    private void start(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    private Response fetch(String track, String kind, URI uri, Access access, int limit, long seq) {
        HttpURLConnection connection = null;
        int status = -1;
        byte[] data = null;
        long begun = System.currentTimeMillis();
        event(track + " " + kind + (seq >= 0 ? " seq=" + seq : "") + " request started");
        try {
            URL url = uri.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            if (!access.agent.isEmpty()) connection.setRequestProperty("User-Agent", access.agent);
            if (!access.referer.isEmpty()) connection.setRequestProperty("Referer", access.referer);
            if (!access.cookie.isEmpty()) connection.setRequestProperty("Cookie", access.cookie);
            for (Map.Entry<String, String> header : access.extra.entrySet()) {
                String key = header.getKey();
                if (!key.equalsIgnoreCase("cookie") && !key.equalsIgnoreCase("referer") &&
                    !key.equalsIgnoreCase("user-agent") && !key.equalsIgnoreCase("host")) {
                    connection.setRequestProperty(key, header.getValue());
                }
            }
            status = connection.getResponseCode();
            if (status == 200) {
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16_384];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        if (output.size() + size > limit) throw new IllegalStateException("Response exceeds limit");
                        output.write(buffer, 0, size);
                    }
                    data = output.toByteArray();
                }
            }
        } catch (Exception failure) {
            event(track + " " + kind + (seq >= 0 ? " seq=" + seq : "") +
                " request error=" + failure.getClass().getSimpleName());
            if (status == 200) status = -1; // Truncated response is not a successful segment.
        } finally {
            if (connection != null) connection.disconnect();
        }
        event(track + " " + kind + (seq >= 0 ? " seq=" + seq : "") + " HTTP=" + status +
            " bytes=" + (data == null ? 0 : data.length) +
            " elapsed=" + (System.currentTimeMillis() - begun) + "ms");
        Track observed = "video".equals(track) ? video : "audio".equals(track) ? audio : null;
        if (observed != null) {
            long now = System.currentTimeMillis();
            if ("playlist".equals(kind)) {
                observed.lastPlaylistHttp = status;
                observed.lastPlaylistResponseMs = now;
                if (status == 200 && data != null) observed.lastPlaylistSuccessMs = now;
            } else if ("segment".equals(kind)) {
                observed.lastSegmentHttp = status;
                observed.lastSegmentResponseMs = now;
                if (status == 200 && data != null) observed.lastSegmentSuccessMs = now;
            }
        }
        return new Response(status, data);
    }

    private static long parseLong(String text) {
        Long value = NativeMedia.decimal(text);
        if (value == null) throw new NumberFormatException("Invalid integer");
        return value;
    }

    private static int parseInt(String text) {
        long value = parseLong(text);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new NumberFormatException("Integer overflow");
        return (int)value;
    }

    private static Playlist parse(URI base, String text) {
        if (!text.startsWith("#EXTM3U")) throw new IllegalArgumentException("Not an HLS media playlist");
        Playlist result = new Playlist();
        long sequence = 0;
        double duration = -1;
        boolean discontinuity = false;
        Long programTimeMs = null;
        for (String line : NativeMedia.lines(text, true)) {
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) sequence = parseLong(line.substring(22).trim());
            else if (line.startsWith("#EXT-X-TARGETDURATION:")) result.target =
                Math.max(1, Math.min(15, parseInt(line.substring(22).trim())));
            else if (line.startsWith("#EXT-X-BYTERANGE") ||
                line.startsWith("#EXT-X-PART:")) {
                if (line.startsWith("#EXT-X-BYTERANGE"))
                    throw new UnsupportedOperationException("byte range playlist");
                // Partial segments are intentionally ignored; only EXTINF is complete.
            } else if (line.startsWith("#EXT-X-KEY:") &&
                !line.toUpperCase(java.util.Locale.ROOT).contains("METHOD=NONE")) {
                throw new UnsupportedOperationException("encrypted playlist");
            }
            else if (line.startsWith("#EXT-X-MAP:")) {
                String path = attribute(line, "URI");
                if (path != null) result.init = base.resolve(path);
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                discontinuity = true;
                programTimeMs = null;
            } else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                programTimeMs = OffsetDateTime.parse(line.substring(25).trim()).toInstant().toEpochMilli();
            }
            else if (line.equals("#EXT-X-ENDLIST")) result.ended = true;
            else if (line.startsWith("#EXTINF:")) duration = Double.parseDouble(line.substring(8, line.indexOf(',', 8) < 0 ? line.length() : line.indexOf(',', 8)));
            else if (!line.isEmpty() && !line.startsWith("#") && duration >= 0) {
                result.entries.add(new Entry(sequence++, base.resolve(line), duration,
                    discontinuity, programTimeMs));
                if (programTimeMs != null) programTimeMs += Math.round(duration * 1000);
                duration = -1; discontinuity = false;
            }
        }
        return result;
    }

    private static String segmentExtension(URI uri) {
        String path = uri.getPath().toLowerCase(java.util.Locale.ROOT);
        if (path.endsWith(".ts")) return ".ts";
        if (path.endsWith(".aac")) return ".aac";
        if (path.endsWith(".mp4")) return ".mp4";
        return ".m4s";
    }

    private static String attribute(String line, String key) {
        String prefix = key + "=\"";
        int from = line.indexOf(prefix);
        if (from < 0) return null;
        int end = line.indexOf('"', from + prefix.length());
        return end < 0 ? null : line.substring(from + prefix.length(), end);
    }

    // A resource entry captured from a player may contain a fixed LL-HLS
    // delivery directive. Reusing that old media sequence stalls live reloads.
    private static URI cleanPlaylistUrl(String url) {
        int question = url.indexOf('?');
        if (question < 0) return URI.create(url);
        StringBuilder result = new StringBuilder(url.substring(0, question));
        for (String parameter : url.substring(question + 1).split("&")) {
            String key = parameter.split("=", 2)[0];
            if (key.equalsIgnoreCase("_HLS_msn") || key.equalsIgnoreCase("_HLS_part") ||
                key.equalsIgnoreCase("_HLS_skip")) continue;
            result.append(result.indexOf("?") < 0 ? '?' : '&').append(parameter);
        }
        return URI.create(result.toString());
    }

    private void serve() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                start("live-hls-response", () -> respond(socket));
            } catch (SocketException exception) {
                if (closed) break;
            } catch (Exception ignored) { /* Next request can still succeed. */ }
        }
    }

    private void respond(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(3000);
            InputStream input = client.getInputStream();
            byte[] request = new byte[4096];
            int count = 0, c;
            // One bounded buffer; no copy of the growing request after each byte.
            while (count < request.length && (c = input.read()) != -1) {
                request[count++] = (byte)c;
                if (count >= 4 && request[count-4] == '\r' && request[count-3] == '\n' &&
                    request[count-2] == '\r' && request[count-1] == '\n') break;
            }
            int firstEnd = 0;
            while (firstEnd < count && request[firstEnd] != '\n') firstEnd++;
            if (firstEnd > 0 && request[firstEnd-1] == '\r') firstEnd--;
            String line = new String(request, 0, firstEnd, StandardCharsets.US_ASCII);
            String path = NativeMedia.httpPath(line, false);
            if (path.startsWith("/" + token + "/")) path = path.substring(token.length() + 1);
            else path = "";
            Track track = path.startsWith("/video") ? video : path.startsWith("/audio") ? audio : null;
            byte[] body = null;
            String type = "video/mp4";
            if (track != null && path.equals("/" + track.name + ".m3u8")) {
                body = track.playlist().getBytes(StandardCharsets.UTF_8);
                type = "application/vnd.apple.mpegurl";
            } else if (track != null && path.equals("/" + track.name + "/init.mp4")) {
                synchronized (track) { body = track.initBytes; }
            } else if (track != null && path.startsWith("/" + track.name + "/") &&
                (path.endsWith(".m4s") || path.endsWith(".ts") ||
                 path.endsWith(".aac") || path.endsWith(".mp4"))) {
                try {
                    long seq = parseLong(path.substring(track.name.length() + 2,
                        path.lastIndexOf('.')));
                    body = track.segment(seq);
                } catch (NumberFormatException ignored) { /* Reply 404 below. */ }
            }
            if (body == null && track != null) event(track.name + " local segment unavailable");
            byte[] payload = body == null ? new byte[0] : body;
            String headers = "HTTP/1.1 " + (body == null ? "404 Not Found" : "200 OK") +
                "\r\nContent-Type: " + type + "\r\nContent-Length: " + payload.length +
                "\r\nConnection: close\r\n\r\n";
            client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(payload);
            client.getOutputStream().flush();
        } catch (Exception ignored) { /* FFmpeg may disconnect when Stop is tapped. */ }
    }

    private synchronized void event(String text) {
        if (events.size() >= 80) events.removeFirst();
        String entry = "+" + ((System.currentTimeMillis() - started) / 1000) + "s " + text;
        events.addLast(entry);
        try {
            if (loggedBytes < 10L * 1024 * 1024) {
                fullLog.write(entry); fullLog.newLine(); fullLog.flush();
                loggedBytes += entry.length() + 1;
            } else if (!logLimitReported) {
                fullLog.write("Request log reached its 10 MiB size limit.");
                fullLog.newLine(); fullLog.flush();
                logLimitReported = true;
            }
        } catch (Exception ignored) { /* Keep recording if diagnostics storage fills. */ }
    }

    synchronized String report() {
        return "Live HLS requests: video segments=" + video.goodSegments +
            ", audio segments=" + audio.goodSegments + ". Recent responses:\n" +
            String.join("\n", events);
    }

    String stallSnapshot() {
        long now = System.currentTimeMillis();
        return "Live relay at stall (URL-free): " + trackSnapshot(video, now) + "; " +
            trackSnapshot(audio, now) + ". " + relayObservation(now);
    }

    boolean hasRecentHttp403() {
        long now = System.currentTimeMillis();
        return rejectedRecently(video, now) || rejectedRecently(audio, now);
    }

    private static boolean rejectedRecently(Track track, long now) {
        return (track.lastPlaylistHttp == 403 && recent(now, track.lastPlaylistResponseMs)) ||
            (track.lastSegmentHttp == 403 && recent(now, track.lastSegmentResponseMs));
    }

    private String trackSnapshot(Track track, long now) {
        return track.name + " playlist HTTP=" + track.lastPlaylistHttp +
            " last=" + age(now, track.lastPlaylistResponseMs) +
            " last 200=" + age(now, track.lastPlaylistSuccessMs) +
            ", segment HTTP=" + track.lastSegmentHttp +
            " last=" + age(now, track.lastSegmentResponseMs) +
            " last 200=" + age(now, track.lastSegmentSuccessMs) +
            ", accepted=" + track.goodSegments + ", next sequence=" + track.next +
            ", buffered=" + track.bufferedSegments() + ", route updates=" + track.renewals +
            ", awaiting verification=" + track.awaitingRenewalSegment +
            ", track error=" + (track.unsupported == null ? "none" : track.unsupported);
    }

    private static String age(long now, long then) {
        return then == 0L ? "never" : Math.max(0L, (now - then) / 1000L) + "s ago";
    }

    private String relayObservation(long now) {
        if (rejectedRecently(video, now) && rejectedRecently(audio, now)) {
            return "Both tracks received HTTP 403; the server rejected current playlist or segment requests. A refreshed browser session may be needed.";
        }
        if (rejectedRecently(video, now) || rejectedRecently(audio, now)) {
            return "One track received HTTP 403; the server rejected its playlist or segment request.";
        }
        if (recent(now, video.lastSegmentSuccessMs) && recent(now, audio.lastSegmentSuccessMs)) {
            return "Both tracks delivered segments recently; inspect FFmpeg/output progress downstream.";
        }
        if (!recent(now, video.lastPlaylistResponseMs) || !recent(now, audio.lastPlaylistResponseMs)) {
            return "At least one playlist has no recent response; playback, session, or connectivity may have paused.";
        }
        return "Playlists responded recently, but one or both tracks have no recent successful segment.";
    }

    private static boolean recent(long now, long then) {
        return then > 0L && now - then < 20_000L;
    }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (Exception ignored) { }
        synchronized (this) { try { fullLog.close(); } catch (Exception ignored) { } }
    }
}
