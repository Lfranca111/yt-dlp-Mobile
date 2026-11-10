package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

public class WebpageAccessDiagnosticTest {
    @Test public void redirectDoesNotForwardPageCookiesToAnotherOrigin() throws Exception {
        try (ServerSocket first=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
             ServerSocket second=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            first.setSoTimeout(5000); second.setSoTimeout(5000);
            ExecutorService executor=Executors.newFixedThreadPool(2);
            try {
                Future<?> redirect=executor.submit(() -> {
                    try (Socket socket=first.accept()) {
                        readRequest(socket);
                        socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:"+second.getLocalPort()+"/other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    } catch (IOException error) { throw new RuntimeException(error); }
                });
                Future<String> target=executor.submit(() -> {
                    try (Socket socket=second.accept()) {
                        String request=readRequest(socket);
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        return request;
                    }
                });
                List<String> log=new ArrayList<>();
                WebpageAccessDiagnostic.probe("http://127.0.0.1:"+first.getLocalPort()+"/page","test","session=private",Collections.emptyMap(),log::add);
                redirect.get(5,TimeUnit.SECONDS);
                assertFalse(target.get(5,TimeUnit.SECONDS).toLowerCase(Locale.ROOT).contains("cookie:"));
                assertTrue(String.join("\n",log).contains("same origin=false"));
            } finally { executor.shutdownNow(); }
        }
    }
    private static String readRequest(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
        StringBuilder request=new StringBuilder(); String line;
        while ((line=reader.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
        return request.toString();
    }
    @Test public void excludesMediaHeadersAndUnsafeValues() {
        Map<String,String> input = new HashMap<>();
        input.put("Range","bytes=0-4"); input.put("Origin","https://cdn.example");
        input.put("Authorization","secret"); input.put("Cookie","a=secret");
        input.put("Accept","video/mp4"); input.put("Accept-Language","en-US");
        Map<String,String> output = WebpageAccessDiagnostic.pageHeaders(input);
        assertEquals(2,output.size()); assertEquals("en-US",output.get("Accept-Language"));
        assertTrue(output.get("Accept").contains("text/html"));
        input.put("Accept-Language","en-US\r\nCookie: bad");
        assertEquals(1,WebpageAccessDiagnostic.pageHeaders(input).size());
    }
    @Test public void cookiesAreExactHostSessionScoped() {
        String jar = WebpageAccessDiagnostic.cookieFile("https://page.example/read", "a=one; b=two=three; invalid=bad\nvalue");
        assertTrue(jar.contains("page.example\tFALSE\t/\tTRUE\t0\ta\tone"));
        assertTrue(jar.contains("b\ttwo=three")); assertFalse(jar.contains("invalid"));
        assertFalse(WebpageAccessDiagnostic.sameOrigin("https://page.example","https://cdn.example"));
        assertFalse(WebpageAccessDiagnostic.sameOrigin("https://page.example","http://page.example"));
        assertTrue(WebpageAccessDiagnostic.sameOrigin("https://page.example/a","https://PAGE.example:443/b"));
    }
    @Test public void errorExcerptRemovesSecretsAndActiveMarkup() {
        String result=WebpageAccessDiagnostic.excerpt("<script>secret code</script><p>Not acceptable</p><p>token=do-not-log</p>");
        assertTrue(result.contains("Not acceptable")); assertFalse(result.contains("do-not-log")); assertFalse(result.contains("secret code"));
        assertEquals("reason not provided",WebpageAccessDiagnostic.excerpt(""));
    }
    @Test public void probeLogs406BodyWithoutCookieValues() throws Exception {
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                Future<String> received = executor.submit(() -> {
                    try (Socket socket=server.accept()) {
                        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        StringBuilder request=new StringBuilder(); String line;
                        while ((line=reader.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                        byte[] body="<p>Not acceptable: request format</p><p>token=private</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        OutputStream out=socket.getOutputStream();
                        out.write(("HTTP/1.1 406 Not Acceptable\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII)); out.write(body); out.flush();
                        return request.toString();
                    }
                });
                List<String> log=new ArrayList<>();
                WebpageAccessDiagnostic.probe("http://127.0.0.1:"+server.getLocalPort()+"/page","test","session=private",Collections.emptyMap(),log::add);
                String request=received.get(5,TimeUnit.SECONDS);
                assertTrue(request.contains("Cookie: session=private"));
                String joined=String.join("\n",log);
                assertTrue(joined.contains("HTTP=406")); assertTrue(joined.contains("request format"));
                assertTrue(joined.contains("independent HTTP check")); assertFalse(joined.contains("private"));
            } finally { executor.shutdownNow(); }
        }
    }
}
