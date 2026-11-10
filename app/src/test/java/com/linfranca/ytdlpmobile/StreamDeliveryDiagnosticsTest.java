package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class StreamDeliveryDiagnosticsTest {
    @Test public void masterDoesNotClaimLiveOrExposeMediaUris() {
        String summary = StreamDeliveryDiagnostics.describe("https://example.org/master.m3u8?secret=abc",
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8?secret=abc\"\n#EXT-X-STREAM-INF:BANDWIDTH=100\nvideo.m3u8?secret=abc\n");
        assertTrue(summary.contains("HLS master"));
        assertTrue(summary.contains("audio declarations=1"));
        assertTrue(summary.contains("requires inspecting"));
        assertFalse(summary.contains("abc"));
        assertFalse(summary.contains("example.org"));
    }
    @Test public void recordsEndedAndLowLatencyEvidence() {
        String summary = StreamDeliveryDiagnostics.describe("", "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-PART:DURATION=0.2\n#EXT-X-ENDLIST\n");
        assertTrue(summary.contains("ENDLIST=true"));
        assertTrue(summary.contains("complete segments=1"));
        assertTrue(summary.contains("low-latency tags=true"));
    }
    @Test public void dashAndUnknownRemainDistinct() {
        assertTrue(StreamDeliveryDiagnostics.describe("", "<MPD type=\"dynamic\"><ContentProtection/></MPD>").contains("dynamic=true"));
        assertTrue(StreamDeliveryDiagnostics.describeUrl("https://example.org/player?url=x.m3u8").contains("unknown"));
        assertTrue(StreamDeliveryDiagnostics.describeUrl("https://example.org/video.mp4?secret=x").contains("direct media"));
    }
    @Test public void recoveryRequiresUsableAuthorizedLiveManifest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean authorized = "session-cookie".equals(exchange.getRequestHeaders().getFirst("Cookie"));
            int code = authorized ? 200 : 403;
            String text = path.contains("html") ? "<html>verification</html>" :
                "#EXTM3U\n#EXTINF:1,\nseg.ts\n" + (path.contains("ended") ? "#EXT-X-ENDLIST\n" : "");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertFalse(StreamDeliveryDiagnostics.verifyHls(base + "/live", "", "", "", Map.of()).valid);
            assertTrue(StreamDeliveryDiagnostics.verifyHls(base + "/live", "", "", "session-cookie", Map.of()).valid);
            assertFalse(StreamDeliveryDiagnostics.verifyHls(base + "/ended", "", "", "session-cookie", Map.of()).valid);
            assertFalse(StreamDeliveryDiagnostics.verifyHls(base + "/html", "", "", "session-cookie", Map.of()).valid);
        } finally { server.stop(0); }
    }
}
