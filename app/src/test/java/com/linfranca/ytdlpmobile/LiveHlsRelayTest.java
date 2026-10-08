package com.linfranca.ytdlpmobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LiveHlsRelayTest {
    private static String get(String url) throws Exception {
        return new String(new URL(url).openStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test public void finalAudioClockKeepsLateStartAndMutesVideoHoles() {
        var timeline = new LiveHlsRelay.AudioTimeline(1_000L, 3_101L,
            List.of(new LiveHlsRelay.TimeGap(5_000L, 6_600L)));
        String filter = timeline.filter();
        assertTrue(filter, filter.contains("aresample=async=1:first_pts=0"));
        assertFalse(filter.contains("adelay="));
        assertFalse(filter.contains("asetpts="));
        assertTrue(filter, filter.contains("between(t,4.000,5.600)"));
        assertTrue(filter, filter.contains("volume=0"));
    }

    @Test public void earlyAudioIsTrimmedWithoutMovingLaterAudioAcrossMissingTime() {
        var timeline = new LiveHlsRelay.AudioTimeline(3_000L, 1_700L, List.of());
        String filter = timeline.filter();
        assertTrue(filter, filter.contains("atrim=start=1.300"));
        assertFalse(filter.contains("asetpts="));
        assertFalse(filter.contains("adelay="));
        assertEquals(null, new LiveHlsRelay.AudioTimeline(null, 1_700L, List.of()).filter());
    }

    @Test public void videoHoleUsesTheActualVideoOriginWhenAudioStartsFirst() {
        var timeline = new LiveHlsRelay.AudioTimeline(10_000L, 7_305L,
            List.of(new LiveHlsRelay.TimeGap(14_000L, 15_600L)));
        String filter = timeline.filter();
        assertTrue(filter, filter.contains("atrim=start=2.695"));
        assertTrue(filter, filter.contains("between(t,6.695,8.295)"));
    }

    @Test public void missingSegmentsRecordBothTrackGapsWithoutDroppingAudioTimeline() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicBoolean jump = new java.util.concurrent.atomic.AtomicBoolean();
        origin.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            StringBuilder body = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n");
            if (path.endsWith(".m3u8")) {
                boolean streamJumped = jump.get();
                body.append("#EXT-X-MEDIA-SEQUENCE:").append(streamJumped ? "13\n" : "10\n");
                body.append("#EXT-X-PROGRAM-DATE-TIME:2026-09-24T08:00:")
                    .append(streamJumped ? "04.800Z\n" : "00.000Z\n");
                int first = streamJumped ? 13 : 10;
                int last = streamJumped ? 13 : 11;
                for (int i = first; i <= last; i++)
                    body.append("#EXTINF:1.6,\n/seg").append(i).append(".ts\n");
            } else body = new StringBuilder("MEDIA");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        origin.start();
        Path log = Files.createTempFile("live-hls-gap", ".txt");
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            var access = new LiveHlsRelay.Access("test", "", "", Map.of());
            try (var relay = new LiveHlsRelay(base + "/video.m3u8",
                base + "/audio.m3u8", access, access, log.toFile())) {
                long readyDeadline = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < readyDeadline) {
                    var before = relay.audioTimeline();
                    if (before.videoStartMs != null && before.audioStartMs != null &&
                        before.videoEndMs != null && before.audioEndMs != null &&
                        before.videoEndMs - before.videoStartMs >= 3_200 &&
                        before.audioEndMs - before.audioStartMs >= 3_200) break;
                    Thread.sleep(100);
                }
                jump.set(true);
                long deadline = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < deadline &&
                    (relay.audioTimeline().videoGaps.isEmpty() ||
                     relay.audioTimeline().audioGaps.isEmpty())) Thread.sleep(100);
                var timeline = relay.audioTimeline();
                assertEquals(1, timeline.videoGaps.size());
                assertEquals(1, timeline.audioGaps.size());
                assertEquals(1_600, timeline.videoGaps.get(0).endMs -
                    timeline.videoGaps.get(0).startMs);
                assertEquals(1_600, timeline.audioGaps.get(0).endMs -
                    timeline.audioGaps.get(0).startMs);
                assertTrue(timeline.filter().contains("between(t,3.200,4.800)"));
            }
        } finally {
            origin.stop(0);
            Files.deleteIfExists(log);
        }
    }

    @Test public void followsBothTracksAndLogsVerifiedRequestsWithoutSecrets() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger polls = new AtomicInteger();
        origin.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] response;
            int status = 200;
            if (exchange.getRequestURI().getRawQuery() != null &&
                exchange.getRequestURI().getRawQuery().contains("_HLS_msn")) status = 400;
            if (path.endsWith(".m3u8")) {
                int newest = 11 + Math.min(3, polls.incrementAndGet() / 2);
                StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n" +
                    "#EXT-X-MEDIA-SEQUENCE:10\n#EXT-X-MAP:URI=\"/init.m4s\"\n");
                for (int i = 10; i <= newest; i++) {
                    playlist.append("#EXTINF:1.6,\n/segment-").append(i).append(".m4s\n");
                }
                response = playlist.toString().getBytes(StandardCharsets.UTF_8);
            } else response = (path.endsWith("init.m4s") ? "INIT" : "SEGMENT")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        origin.start();
        Path log = Files.createTempFile("live-hls-test", ".txt");
        LiveHlsRelay relay = null;
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            var access = new LiveHlsRelay.Access("test-agent", "", "secret-cookie", Map.of());
            relay = new LiveHlsRelay(base + "/video.m3u8?token=secret-token&_HLS_msn=10",
                base + "/audio.m3u8?token=secret-token", access, access, log.toFile());
            assertTrue(get(relay.videoUrl()).contains("#EXT-X-MAP:"));
            assertTrue(get(relay.audioUrl()).contains("/audio/10.m4s"));
            String future = "";
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                future = get(relay.videoUrl());
                if (future.contains("/video/12.m4s")) break;
                Thread.sleep(150);
            }
            assertTrue("playlist did not advance: " + future, future.contains("/video/12.m4s"));
            assertTrue(get(relay.videoUrl().replace("video.m3u8", "video/12.m4s"))
                .equals("SEGMENT"));
            HttpURLConnection unknown = (HttpURLConnection) new URL(
                relay.videoUrl().replace("video.m3u8", "video/999.m4s")).openConnection();
            assertTrue(unknown.getResponseCode() == 404);
        } finally {
            if (relay != null) relay.close();
            origin.stop(0);
        }
        String entries = Files.readString(log);
        assertTrue(entries.contains("video segment seq=12 HTTP=200"));
        assertTrue(entries.contains("audio playlist HTTP=200"));
        assertFalse(entries.contains("secret-token"));
        assertFalse(entries.contains("secret-cookie"));
        Files.deleteIfExists(log);
    }

    @Test public void acceptsRefreshedCredentialsWithoutResettingSegmentSequence() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger generation = new AtomicInteger(1);
        AtomicInteger available = new AtomicInteger(12);
        origin.createContext("/", exchange -> {
            int required = generation.get();
            String query = exchange.getRequestURI().getRawQuery();
            boolean authorized = query != null && query.contains("session=s" + required) &&
                ("c" + required).equals(exchange.getRequestHeaders().getFirst("Cookie"));
            int status = authorized ? 200 : 403;
            String body = exchange.getRequestURI().getPath().endsWith(".m3u8") ?
                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:10\n" +
                java.util.stream.IntStream.rangeClosed(10, available.get())
                    .mapToObj(i -> "#EXTINF:1.0,\n/seg" + i + ".ts?session=s" + required + "\n")
                    .reduce("", String::concat) : "MEDIA";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        origin.start();
        Path log = Files.createTempFile("live-hls-renewal", ".txt");
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            try (var relay = new LiveHlsRelay(base + "/video.m3u8?session=s1",
                base + "/audio.m3u8?session=s1",
                new LiveHlsRelay.Access("test", "", "c1", Map.of()),
                new LiveHlsRelay.Access("test", "", "c1", Map.of()), log.toFile())) {
                assertFalse(relay.renew("video", base + "/different.m3u8?session=s2",
                    new LiveHlsRelay.Access("test", "", "c2", Map.of())));
                generation.set(2);
                available.set(15);
                var renewed = new LiveHlsRelay.Access("test", "", "c2", Map.of());
                assertTrue(relay.renew("video", base + "/video.m3u8?session=s2", renewed));
                assertTrue(relay.renew("audio", base + "/audio.m3u8?session=s2", renewed));
                String refreshed = "";
                long deadline = System.currentTimeMillis() + 6_000;
                while (System.currentTimeMillis() < deadline) {
                    refreshed = get(relay.videoUrl());
                    if (refreshed.contains("/video/15.ts") &&
                        get(relay.audioUrl()).contains("/audio/15.ts")) break;
                    Thread.sleep(100);
                }
                assertTrue(refreshed, refreshed.contains("/video/15.ts"));
                assertTrue(refreshed.contains("/video/12.ts"));
            }
            String entries = Files.readString(log);
            assertTrue(entries.contains("renewal verified"));
            assertFalse(entries.contains("session=s2"));
        } finally {
            origin.stop(0);
            Files.deleteIfExists(log);
        }
    }

    @Test public void alignsAudioToVideoByProgramTimeRatherThanSequenceNumber() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean audio = path.contains("audio");
            String body = path.endsWith(".m3u8") ?
                "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:" +
                    (audio ? "20" : "10") + "\n#EXT-X-MAP:URI=\"/init.m4s\"\n" +
                    "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T08:00:0" +
                    (audio ? "3" : "0") + ".250+00:00\n" +
                    "#EXTINF:1.6,\n/segment.m4s\n" : "DATA";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        origin.start();
        Path log = Files.createTempFile("live-hls-alignment", ".txt");
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            var access = new LiveHlsRelay.Access("test", "", "", Map.of());
            try (var relay = new LiveHlsRelay(base + "/video.m3u8",
                base + "/audio.m3u8", access, access, log.toFile())) {
                assertEquals(3.0, relay.initialAudioOffsetSeconds(), 0.001);
                assertTrue(get(relay.videoUrl()).contains("2026-09-24T08:00:00.250Z"));
                assertTrue(get(relay.audioUrl()).contains("2026-09-24T08:00:03.250Z"));
            }
        } finally {
            origin.stop(0);
            Files.deleteIfExists(log);
        }
    }

    @Test public void servesTransportStreamSegmentsWithTheirOriginalExtension() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            String body = exchange.getRequestURI().getPath().endsWith(".m3u8") ?
                "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:9\n" +
                    "#EXTINF:4.0,\n/segment9.ts\n#EXTINF:4.0,\n/segment10.ts\n" : "TS DATA";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        origin.start();
        Path log = Files.createTempFile("live-hls-ts", ".txt");
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            var access = new LiveHlsRelay.Access("test", "", "", Map.of());
            try (var relay = new LiveHlsRelay(base + "/video.m3u8",
                base + "/audio.m3u8", access, access, log.toFile())) {
                String playlist = get(relay.videoUrl());
                assertTrue(playlist.contains("/video/9.ts"));
                assertFalse(playlist.contains("#EXT-X-MAP:"));
                assertTrue(get(relay.videoUrl().replace("video.m3u8", "video/9.ts"))
                    .equals("TS DATA"));
            }
        } finally {
            origin.stop(0);
            Files.deleteIfExists(log);
        }
    }

    @Test public void rejectsFinitePlaylistsWithoutWaitingForLiveSegments() throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            byte[] bytes = ("#EXTM3U\n#EXT-X-TARGETDURATION:4\n" +
                "#EXTINF:4.0,\n/segment.ts\n#EXT-X-ENDLIST\n")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        origin.start();
        Path log = Files.createTempFile("live-hls-vod", ".txt");
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            var access = new LiveHlsRelay.Access("test", "", "", Map.of());
            long started = System.currentTimeMillis();
            boolean rejected = false;
            try (var ignored = new LiveHlsRelay(base + "/video.m3u8",
                base + "/audio.m3u8", access, access, log.toFile())) {
                // A finite playlist should use the existing direct FFmpeg path.
            } catch (IllegalStateException expected) { rejected = true; }
            assertTrue(rejected);
            assertTrue(System.currentTimeMillis() - started < 2_000);
        } finally {
            origin.stop(0);
            Files.deleteIfExists(log);
        }
    }
}
