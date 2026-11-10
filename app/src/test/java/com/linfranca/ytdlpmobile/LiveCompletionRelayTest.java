package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class LiveCompletionRelayTest {
    @Test public void bothTracksMustFinishAndFinalSegmentsRemainReadableBeforeDrain() throws Exception {
        var videoEnd=new AtomicBoolean();var audioEnd=new AtomicBoolean();var refuse=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            String path=e.getRequestURI().getPath();boolean playlist=path.endsWith(".m3u8");
            if(playlist && refuse.get()) { e.sendResponseHeaders(403,-1);e.close();return; }
            boolean end=path.contains("video") ? videoEnd.get() : audioEnd.get();
            String body=playlist ? "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:1,\n/s10.ts\n#EXTINF:1,\n/s11.ts\n"+(end ? "#EXT-X-ENDLIST\n" : "") : "MEDIA";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();var log=Files.createTempFile("completion-relay",".txt");
        String base="http://127.0.0.1:"+server.getAddress().getPort();var access=new LiveHlsRelay.Access("test","","",Map.of());
        try(var relay=new LiveHlsRelay(base+"/video.m3u8",base+"/audio.m3u8",access,access,log.toFile())) {
            refuse.set(true);Thread.sleep(1200);assertFalse(relay.sourceEnded());refuse.set(false);
            videoEnd.set(true);Thread.sleep(2000);assertFalse(relay.sourceEnded());
            audioEnd.set(true);long deadline=System.currentTimeMillis()+12000;
            while(!relay.sourceEnded() && System.currentTimeMillis()<deadline) Thread.sleep(50);
            assertTrue(relay.sourceEnded());assertFalse(relay.finalSegmentsServed());
            for(String local:new String[]{relay.videoUrl(),relay.audioUrl()}) {
                String list=new String(new URL(local).openStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(list.contains("#EXT-X-ENDLIST"));
                for(String line:list.split("\n")) if(line.startsWith("/")) {
                    String data=new String(new URL(new URL(local),line).openStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals("MEDIA",data);
                }
            }
            assertTrue(relay.finalSegmentsServed());
            assertFalse(relay.renew("video",base+"/video.m3u8",access,true));
        } finally { server.stop(0);Files.deleteIfExists(log); }
    }
}
