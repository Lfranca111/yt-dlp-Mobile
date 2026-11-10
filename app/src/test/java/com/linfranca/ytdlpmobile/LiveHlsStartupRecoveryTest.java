package com.linfranca.ytdlpmobile;

import static org.junit.Assert.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LiveHlsStartupRecoveryTest {
    private static final byte[] LIVE = ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n" +
        "#EXT-X-MEDIA-SEQUENCE:308\n#EXTINF:1,\n/seg.ts\n").getBytes(StandardCharsets.UTF_8);
    private static final LiveHlsRelay.Access ACCESS = new LiveHlsRelay.Access("test","","",Map.of());

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,int status,byte[] bytes) throws java.io.IOException {
        exchange.sendResponseHeaders(status,bytes.length==0 ? -1 : bytes.length);
        try(var output=exchange.getResponseBody()) { output.write(bytes); }
    }

    @Test public void segment403IsReportedAndFreshAccessCanOpenSameLiveTimeline() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var denied=new AtomicInteger();
        var accepted=new AtomicInteger();
        server.createContext("/live.m3u8",e -> respond(e,200,LIVE));
        server.createContext("/seg.ts",e -> {
            if(!"fresh".equals(e.getRequestHeaders().getFirst("X-Test-Route"))) {
                denied.incrementAndGet();respond(e,403,new byte[0]);
            } else {
                byte[] ts=new byte[376];ts[0]=ts[188]=0x47;
                accepted.incrementAndGet();respond(e,200,ts);
            }
        });
        server.start();
        var log=Files.createTempFile("startup-forbidden",".txt");
        var freshLog=Files.createTempFile("startup-fresh",".txt");
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/live.m3u8";
        try {
            var failure=assertThrows(LiveHlsStartupFailure.class,() ->
                new LiveHlsRelay(url,url,ACCESS,ACCESS,log.toFile(),true,true));
            assertEquals(403,failure.status);
            assertTrue(failure.retryable);
            assertTrue(failure.getMessage().contains("media segment request failed (HTTP 403)"));
            assertEquals(1,denied.get());
            assertTrue(new String(Files.readAllBytes(log), StandardCharsets.UTF_8).contains("playlist HTTP=200"));
            assertFalse(failure.getMessage().contains(url));
            var fresh=new LiveHlsRelay.Access("test","","",Map.of("X-Test-Route","fresh"));
            try(var relay=new LiveHlsRelay(url,url,fresh,fresh,freshLog.toFile(),true,true)) {
                assertTrue(accepted.get()>0);
                assertTrue(new String(new URL(relay.videoUrl()).openStream().readAllBytes(),StandardCharsets.UTF_8).contains("/308.ts"));
            }
        } finally { server.stop(0);Files.deleteIfExists(log);Files.deleteIfExists(freshLog); }
    }

    @Test public void rateLimitStopsStartupWithoutAnImmediateRefresh() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/live.m3u8",e -> respond(e,200,LIVE));
        server.createContext("/seg.ts",e -> respond(e,429,new byte[0]));
        server.start();
        var log=Files.createTempFile("startup-rate",".txt");
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/live.m3u8";
        try {
            var failure=assertThrows(LiveHlsStartupFailure.class,() ->
                new LiveHlsRelay(url,url,ACCESS,ACCESS,log.toFile(),true,true));
            assertEquals(429,failure.status);
            assertFalse(failure.retryable);
        } finally { server.stop(0);Files.deleteIfExists(log); }
    }

    @Test public void nonMediaHttp200CannotMakeTheRelayReady() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var attempts=new AtomicInteger();
        server.createContext("/live.m3u8",e -> respond(e,200,LIVE));
        server.createContext("/seg.ts",e -> {
            if(attempts.incrementAndGet()==1)
                respond(e,200,"<html>Sign in</html>".getBytes(StandardCharsets.UTF_8));
            else respond(e,403,new byte[0]);
        });
        server.start();
        var log=Files.createTempFile("startup-non-media",".txt");
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/live.m3u8";
        try {
            var failure=assertThrows(LiveHlsStartupFailure.class,() ->
                new LiveHlsRelay(url,url,ACCESS,ACCESS,log.toFile(),true,true));
            assertEquals(403,failure.status);
            assertTrue(new String(Files.readAllBytes(log), StandardCharsets.UTF_8).contains("segment startup body rejected"));
        } finally { server.stop(0);Files.deleteIfExists(log); }
    }

    @Test public void startupPlaylistFailuresPreserveHttpStatusAndEmptyEvidence() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        for(int status:new int[]{401,404,410,429,503})
            server.createContext("/"+status+".m3u8",e -> respond(e,status,new byte[0]));
        server.createContext("/empty.m3u8",e -> respond(e,200,"#EXTM3U\n#EXT-X-TARGETDURATION:2\n".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/ended.m3u8",e -> respond(e,200,(new String(LIVE,StandardCharsets.UTF_8)+"#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8)));
        server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort()+"/";
        try {
            for(int status:new int[]{401,404,410,429,503}) {
                var failure=assertThrows(LiveHlsStartupFailure.class,() -> LiveHlsRecordingPlan.inspectMonitoredLive(base+status+".m3u8",ACCESS));
                assertEquals(status,failure.status);assertEquals(status!=429,failure.retryable);
            }
            var empty=assertThrows(LiveHlsStartupFailure.class,() -> LiveHlsRecordingPlan.inspectMonitoredLive(base+"empty.m3u8",ACCESS));
            assertEquals(200,empty.status);assertTrue(empty.retryable);
            assertEquals(LiveHlsRecordingPlan.Kind.EXISTING,LiveHlsRecordingPlan.inspectMonitoredLive(base+"ended.m3u8",ACCESS).kind);
            assertEquals(LiveHlsRecordingPlan.Kind.EXISTING,LiveHlsRecordingPlan.inspect(base+"404.m3u8",ACCESS).kind);
        } finally { server.stop(0); }
    }
}
