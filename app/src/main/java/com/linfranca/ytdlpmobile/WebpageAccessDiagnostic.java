package com.linfranca.ytdlpmobile;

import java.net.HttpURLConnection;
import java.net.URI;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Page-only access. Never reuse CDN headers, never forward cookies across origins. */
final class WebpageAccessDiagnostic {
    static Map<String,String> pageHeaders(Map<String,String> captured) {
        Map<String,String> result = new LinkedHashMap<>();
        result.put("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
        for (Map.Entry<String,String> e : captured.entrySet()) {
            if (e.getKey().equalsIgnoreCase("Accept-Language") && valid(e.getValue()))
                result.put("Accept-Language", e.getValue());
        }
        return result;
    }
    private static boolean valid(String value) {
        return value != null && value.length() <= 2048 && !value.contains("\r") && !value.contains("\n");
    }
    static boolean sameOrigin(String a, String b) {
        try {
            URI x = URI.create(a), y = URI.create(b);
            return x.getHost() != null && y.getHost() != null &&
                x.getHost().equalsIgnoreCase(y.getHost()) &&
                x.getScheme().equalsIgnoreCase(y.getScheme()) && port(x) == port(y);
        } catch (Exception ignored) { return false; }
    }
    private static int port(URI u) { return u.getPort() >= 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80); }
    static String cookieFile(String page, String cookies) {
        URI u = URI.create(page);
        if (u.getHost() == null || !("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())))
            throw new IllegalArgumentException("Invalid page origin");
        StringBuilder out = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (String pair : cookies.split(";")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String name = pair.substring(0,equals).trim(), value = pair.substring(equals+1).trim();
            if (!name.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]+") || !valid(value) || value.contains("\t")) continue;
            out.append(u.getHost()).append("\tFALSE\t/\t")
                .append("https".equalsIgnoreCase(u.getScheme()) ? "TRUE" : "FALSE")
                .append("\t0\t").append(name).append('\t').append(value).append('\n');
        }
        return out.toString();
    }
    static String excerpt(String html) {
        String text = html.replaceAll("(?is)<(script|style|form)\\b[^>]*>.*?</\\1\\s*>", " ")
            .replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        String safe = RecorderErrorDiagnostic.safeLine(text);
        return safe == null ? "reason not provided" : safe;
    }
    /** Independent diagnostic request, not a capture of the extractor's own response. */
    static void probe(String page, String userAgent, String cookies, Map<String,String> headers,
                      Consumer<String> log) {
        long deadline = System.nanoTime() + 8_000_000_000L;
        String current = page;
        log.accept("page_probe_started; independent HTTP check; not the extractor response");
        for (int hop=0; hop<4; hop++) {
            HttpURLConnection connection = null;
            try {
                URI uri = URI.create(current);
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                    log.accept("page_probe_redirect_rejected; non-HTTP scheme"); return;
                }
                int remaining = (int)((deadline-System.nanoTime())/1_000_000L);
                if (remaining <= 0) { log.accept("page_probe_timeout"); return; }
                connection = (HttpURLConnection)uri.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(Math.min(2500,remaining));
                connection.setReadTimeout(Math.min(2500,remaining));
                pageHeaders(headers).forEach(connection::setRequestProperty);
                if (valid(userAgent)) connection.setRequestProperty("User-Agent",userAgent);
                boolean scoped = sameOrigin(page,current);
                boolean cookiesSent = scoped && valid(cookies) && !cookies.isEmpty();
                if (cookiesSent) connection.setRequestProperty("Cookie",cookies);
                int status = connection.getResponseCode();
                log.accept("page_probe_response; hop="+hop+"; HTTP="+status+"; original origin="+scoped+
                    "; page cookies sent="+cookiesSent);
                for (String name : Arrays.asList("Content-Type","Server","Vary","Retry-After","CF-Mitigated","X-Cache")) {
                    String value = RecorderErrorDiagnostic.safeLine(connection.getHeaderField(name));
                    if (value != null) log.accept("page_probe_header; "+name+"="+value);
                }
                String location = connection.getHeaderField("Location");
                if (status >= 300 && status < 400 && location != null) {
                    String next = uri.resolve(location).toString();
                    log.accept("page_probe_redirect; same origin="+sameOrigin(current,next)+"; URL omitted");
                    current=next; continue;
                }
                if (status >= 400) {
                    String type = connection.getContentType();
                    if (type != null && (type.toLowerCase(Locale.ROOT).contains("text/") || type.toLowerCase(Locale.ROOT).contains("json"))) {
                        try (InputStream stream = connection.getErrorStream()) {
                            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[1024];
                            while (stream != null && body.size() < 8192) {
                                int timeLeft = (int)((deadline-System.nanoTime())/1_000_000L);
                                if (timeLeft <= 0) break;
                                connection.setReadTimeout(Math.min(2500,timeLeft));
                                int count = stream.read(buffer,0,Math.min(buffer.length,8192-body.size()));
                                if (count < 0) break;
                                body.write(buffer,0,count);
                            }
                            byte[] bytes = body.toByteArray();
                            log.accept("page_probe_error_excerpt (sanitized, server-supplied): "+excerpt(new String(bytes,StandardCharsets.UTF_8)));
                        }
                    } else log.accept("page_probe_error_excerpt: reason not provided; absent or non-text body");
                }
                log.accept("page_probe_completed; HTTP="+status+"; extractor result remains authoritative"); return;
            } catch (Exception error) {
                log.accept("page_probe_failed; type="+error.getClass().getSimpleName()+"; reason not provided"); return;
            } finally { if (connection != null) connection.disconnect(); }
        }
        log.accept("page_probe_redirect_limit; reason not established");
    }
}
