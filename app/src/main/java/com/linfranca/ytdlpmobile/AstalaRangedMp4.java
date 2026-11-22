package com.linfranca.ytdlpmobile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct MP4 only. A capped 206 response is not the end of the file. */
final class AstalaRangedMp4 {
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Pattern FORBIDDEN = Pattern.compile("\\bHTTP(?: Error)?[ :=]+403\\b", Pattern.CASE_INSENSITIVE);
    private static final int MAX_REQUESTS = 4096;

    static final class Rejected extends IOException {
        Rejected(String reason) { super(reason); }
    }

    interface Open { HttpURLConnection connection() throws IOException; }
    interface SizeCheck { void check(long size) throws IOException; }
    interface Progress { void bytes(long completed, long total) throws IOException; }

    static boolean eligible(String page, String media, String failure) {
        if (!FORBIDDEN.matcher(failure).find()) return false;
        try {
            URI p = URI.create(page), m = URI.create(media);
            return https(p) && https(m)
                && ("astalavr.com".equalsIgnoreCase(p.getHost()) || "www.astalavr.com".equalsIgnoreCase(p.getHost()))
                && "cdn3.astalavr.com".equalsIgnoreCase(m.getHost())
                && m.getPath().toLowerCase(Locale.ROOT).endsWith(".mp4");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean https(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    static long copy(Open open, Map<String, String> headers, OutputStream output,
                     BooleanSupplier active, SizeCheck sizeCheck, Progress progress, Consumer<String> log) throws IOException {
        log.accept("MP4 request headers present: origin=" + hasHeader(headers, "origin") +
            "; referer=" + hasHeader(headers, "referer") + "; userAgent=" + hasHeader(headers, "user-agent") +
            "; cookie=" + hasHeader(headers, "cookie"));
        long offset = 0, total = -1;
        String tag = null;
        byte[] buffer = new byte[64 * 1024];
        for (int requests = 0; requests < MAX_REQUESTS; requests++) {
            ensure(active.getAsBoolean(), "Ranged MP4 download stopped");
            int requestNumber = requests + 1;
            boolean sample = requestNumber <= 3 || requestNumber % 32 == 0;
            if (sample) log.accept("MP4 range request #" + requestNumber + ": start=" + offset + "; ifRange=" + (tag != null));
            HttpURLConnection http = open.connection();
            try {
                http.setConnectTimeout(8000);
                http.setReadTimeout(8000);
                http.setInstanceFollowRedirects(false);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    String key = h.getKey().toLowerCase(Locale.ROOT);
                    if ("origin".equals(key) || "referer".equals(key) || "user-agent".equals(key)
                            || "cookie".equals(key)) http.setRequestProperty(h.getKey(), h.getValue());
                }
                http.setRequestProperty("Accept-Encoding", "identity");
                http.setRequestProperty("Range", "bytes=" + offset + "-");
                if (tag != null) http.setRequestProperty("If-Range", tag);
                int status = http.getResponseCode();
                if (sample || status != 206) log.accept("MP4 range response #" + requestNumber + ": HTTP " + status);
                ensure(status == 206, "Ranged MP4 request returned HTTP " + status);
                String encoding = http.getContentEncoding();
                ensure(encoding == null || encoding.equalsIgnoreCase("identity"), "Unexpected MP4 content encoding");
                String type = http.getContentType();
                ensure(type != null && "video/mp4".equalsIgnoreCase(type.split(";", 2)[0].trim()),
                    "Ranged request did not return MP4");
                String raw = http.getHeaderField("Content-Range");
                Matcher m = RANGE.matcher(raw == null ? "" : raw);
                ensure(m.matches(), "Missing or invalid MP4 Content-Range");
                long start = number(m.group(1)), end = number(m.group(2)), size = number(m.group(3));
                if (sample) log.accept("MP4 response byte range: " + start + "-" + end + "/" + size);
                ensure(start == offset && end >= start && end < size && (total == -1 || size == total),
                    "MP4 byte ranges did not match the original file");
                String nextTag = http.getHeaderField("ETag");
                if (total == -1) {
                    total = size;
                    tag = nextTag;
                    if (end + 1 < total)
                        ensure(tag != null && tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\""),
                            "MP4 server did not provide a stable file identifier");
                    sizeCheck.check(size);
                } else {
                    ensure(tag.equals(nextTag), "MP4 file changed between requests");
                }
                long remaining = end - start + 1;
                long declared = http.getContentLengthLong();
                ensure(declared == -1 || declared == remaining, "MP4 range length did not match Content-Range");
                try (InputStream input = http.getInputStream()) {
                    while (remaining > 0) {
                        ensure(active.getAsBoolean(), "Ranged MP4 download stopped");
                        int n = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                        ensure(n > 0, "MP4 response ended before its advertised byte range");
                        output.write(buffer, 0, n);
                        offset += n;
                        remaining -= n;
                        progress.bytes(offset, total);
                    }
                    ensure(input.read() == -1, "MP4 response exceeded its advertised byte range");
                }
                if (offset == total) {
                    log.accept("MP4 ranges complete: requests=" + requestNumber + "; bytes=" + total);
                    return total;
                }
            } catch (IOException e) {
                log.accept("MP4 range stopped: request=" + requestNumber + "; receivedBytes=" + offset);
                throw e;
            } finally {
                http.disconnect();
            }
        }
        throw new Rejected("MP4 server exceeded the maximum number of range requests");
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        return headers.entrySet().stream().anyMatch(h -> name.equalsIgnoreCase(h.getKey()) && !h.getValue().isBlank());
    }

    private static long number(String value) throws IOException {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new Rejected("Invalid MP4 byte range"); }
    }

    private static void ensure(boolean condition, String message) throws IOException {
        if (!condition) throw new Rejected(message);
    }

    private AstalaRangedMp4() {}
}
