package com.linfranca.ytdlpmobile;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class AstalaRangedMp4Test {
    @Test public void onlyKnownBrowserMediaHostsAndExplicit403Qualify() {
        String page = "https://astalavr.com/example", media = "https://cdn3.astalavr.com/test.mp4";
        assertTrue(AstalaRangedMp4.eligible(page, media, "HTTP Error 403: Forbidden"));
        assertFalse(AstalaRangedMp4.eligible(page, media, "HTTP Error 429"));
        assertFalse(AstalaRangedMp4.eligible(page, media + ".m3u8", "HTTP 403"));
        assertFalse(AstalaRangedMp4.eligible(page, "https://cdn3.astalavr.com.attacker.invalid/test.mp4", "HTTP 403"));
        assertFalse(AstalaRangedMp4.eligible("https://other.invalid", media, "HTTP 403"));
    }

    @Test public void capped206ResponsesAssembleTheExactObject() throws Exception {
        try (Fixture f = new Fixture("ok")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(8, f.copy(out, () -> true));
            assertArrayEquals(new byte[] {0,1,2,3,4,5,6,7}, out.toByteArray());
            assertEquals(List.of("bytes=0-", "bytes=3-", "bytes=6-"), f.ranges);
            assertEquals(List.of("", "\"v1\"", "\"v1\""), f.ifRanges);
            assertTrue(f.logs.contains("MP4 range response #1: HTTP 206"));
            assertTrue(f.logs.contains("MP4 response byte range: 0-2/8"));
            assertTrue(f.logs.contains("MP4 ranges complete: requests=3; bytes=8"));
            assertTrue(f.logs.get(0).contains("cookie=true"));
            assertFalse(String.join("\n", f.logs).contains("test_secret"));
        }
    }

    @Test public void gapsChangesRedirectsAndTruncationFail() throws Exception {
        for (String mode : List.of("gap", "changed", "redirect", "truncated", "forbidden", "whole")) {
            try (Fixture f = new Fixture(mode)) {
                assertThrows(IOException.class, () -> f.copy(new ByteArrayOutputStream(), () -> true));
                assertEquals(2, f.ranges.size());
            }
        }
    }

    @Test public void cancellationDoesNotStartAnotherRange() throws Exception {
        try (Fixture f = new Fixture("ok")) {
            AtomicBoolean active = new AtomicBoolean(true);
            assertThrows(IOException.class, () -> AstalaRangedMp4.copy(f::open, Map.of(), new ByteArrayOutputStream(),
                active::get, total -> {}, (copied, total) -> active.set(false), line -> {}));
            assertEquals(List.of("bytes=0-"), f.ranges);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final List<String> logs = new ArrayList<>();
        final List<String> ranges = Collections.synchronizedList(new ArrayList<>());
        final List<String> ifRanges = Collections.synchronizedList(new ArrayList<>());
        Fixture(String mode) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/video", exchange -> {
                try {
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    ranges.add(range);
                    String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
                    ifRanges.add(ifRange == null ? "" : ifRange);
                    int offset = Integer.parseInt(range.substring(6, range.length() - 1));
                    if (offset > 0 && List.of("redirect", "forbidden", "whole").contains(mode)) {
                        exchange.getResponseHeaders().set("Location", "/video");
                        exchange.sendResponseHeaders("redirect".equals(mode) ? 302 : "forbidden".equals(mode) ? 403 : 200, -1);
                        return;
                    }
                    int start = "gap".equals(mode) && offset > 0 ? offset + 1 : offset;
                    int end = Math.min(start + 2, 7);
                    var h = exchange.getResponseHeaders();
                    h.set("Content-Type", "video/mp4");
                    h.set("Content-Range", "bytes " + start + "-" + end + "/8");
                    h.set("ETag", "changed".equals(mode) && offset > 0 ? "\"v2\"" : "\"v1\"");
                    boolean truncate = "truncated".equals(mode) && offset > 0;
                    exchange.sendResponseHeaders(206, truncate ? 0 : end - start + 1);
                    for (int i = start; i <= end - (truncate ? 1 : 0); i++) exchange.getResponseBody().write(i);
                } finally { exchange.close(); }
            });
            server.start();
        }
        HttpURLConnection open() throws IOException {
            return (HttpURLConnection) URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/video").toURL().openConnection();
        }
        long copy(ByteArrayOutputStream out, java.util.function.BooleanSupplier active) throws IOException {
            return AstalaRangedMp4.copy(this::open, Map.of("Cookie", "session=test_secret"), out, active, total -> assertEquals(8, total), (at, total) -> {}, logs::add);
        }
        public void close() { server.stop(0); }
    }
}
