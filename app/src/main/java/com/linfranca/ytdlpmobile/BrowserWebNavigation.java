package com.linfranca.ytdlpmobile;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Navigation policy for the in-app browser. The submitted media URL stays unchanged. */
final class BrowserWebNavigation {
    static final String APP_LINK_BLOCKED =
        "This site tried to open its app. If the video plays here, continue as usual. "
        + "Otherwise, check whether the post plays in a web browser.";

    private static final Pattern THREADS_POST =
        Pattern.compile("/@[A-Za-z0-9_.]+/post/[A-Za-z0-9_-]+/?");

    private BrowserWebNavigation() {}

    static boolean isAppScheme(String scheme) {
        if (scheme == null) return false;
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http": case "https": case "about": case "blob": case "data":
            case "javascript": case "file": case "content": case "chrome":
            case "chrome-error": case "view-source": case "ws": case "wss":
                return false;
            default: return true;
        }
    }

    static boolean isWebScheme(String scheme) {
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    /** A single budget for an explicit navigation and all of its redirects. */
    static final class Session {
        private URI original;
        private URI committed;
        private boolean retried;

        String open(String url) {
            String entry = entryUrl(url);
            original = webUrl(entry);
            committed = null;
            retried = false;
            return entry;
        }

        void pageVisible(String url) {
            URI current = webUrl(url);
            if (sameSite(current, original)) committed = current;
        }

        String recover(String appLink, boolean userGesture) {
            if (original == null || retried || appLink == null || appLink.length() > 16384) return null;
            URI chosen = null;
            if (appLink.regionMatches(true, 0, "intent:", 0, 7)) {
                int separator = appLink.indexOf("#Intent;");
                if (separator >= 7 && appLink.endsWith(";end")) {
                    String scheme = null;
                    String fallback = null;
                    for (String field : appLink.substring(separator + 8, appLink.length() - 3).split(";")) {
                        if (field.startsWith("scheme=")) {
                            if (scheme != null) return null;
                            scheme = field.substring(7);
                        } else if (field.startsWith("S.browser_fallback_url=")) {
                            if (fallback != null) return null;
                            try {
                                // Intent fields use percent encoding, where + is a literal plus.
                                fallback = URLDecoder.decode(field.substring("S.browser_fallback_url=".length()).replace("+", "%2B"),
                                    StandardCharsets.UTF_8.name());
                            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException badEncoding) {
                                return null;
                            }
                        }
                    }
                    URI embedded = "https".equalsIgnoreCase(scheme)
                        ? httpsUrl("https:" + appLink.substring(7, separator)) : null;
                    URI offered = httpsUrl(fallback);
                    if (matchesNavigation(offered, userGesture) &&
                        (embedded == null || samePage(offered, embedded))) {
                        chosen = offered;
                    } else if (matchesNavigation(embedded, userGesture)) {
                        chosen = embedded;
                    }
                }
            }
            // A custom app scheme without a usable web URL is opaque. Retry only our known web page.
            if (chosen == null && committed == null &&
                (appLink.regionMatches(true, 0, "intent:", 0, 7) || appLink.contains("://"))) {
                chosen = httpsUrl(original.toString());
            }
            if (chosen == null) return null;
            String target = entryUrl(chosen.toString());
            URI normalizedTarget = httpsUrl(target);
            if (normalizedTarget == null || (samePage(normalizedTarget, committed) &&
                normalizedTarget.getScheme().equalsIgnoreCase(committed.getScheme()) &&
                Objects.equals(normalizedTarget.getRawQuery(), committed.getRawQuery()))) return null;
            retried = true;
            return target;
        }

        private boolean matchesNavigation(URI target, boolean userGesture) {
            return sameSite(target, original) &&
                (samePage(target, original) || samePage(target, committed) ||
                    (userGesture && target.getPath().length() > 1));
        }
    }

    private static URI webUrl(String value) {
        if (value == null) return null;
        try {
            URI uri = new URI(value);
            if (!isWebScheme(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null ||
                uri.getPort() != -1 || uri.getRawAuthority().endsWith(":") ||
                !uri.getRawPath().equals(uri.normalize().getRawPath())) return null;
            String path = uri.getRawPath().toLowerCase(Locale.ROOT);
            if (path.contains("%2f") || path.contains("%5c") || path.contains("%2e")) return null;
            return uri;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private static URI httpsUrl(String value) {
        URI uri = webUrl(value);
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) ? uri : null;
    }

    private static boolean sameSite(URI left, URI right) {
        if (left == null || right == null) return false;
        String a = left.getHost().toLowerCase(Locale.ROOT);
        String b = right.getHost().toLowerCase(Locale.ROOT);
        return bareHost(a).equals(bareHost(b)) || (isThreadsHost(a) && isThreadsHost(b));
    }

    private static String bareHost(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static boolean samePage(URI left, URI right) {
        return sameSite(left, right) &&
            left.getRawPath().replaceFirst("/+$", "").equals(right.getRawPath().replaceFirst("/+$", ""));
    }

    private static boolean isThreadsHost(String host) {
        switch (host.toLowerCase(Locale.ROOT)) {
            case "threads.com": case "www.threads.com":
            case "threads.net": case "www.threads.net":
                return true;
            default:
                return false;
        }
    }

    static String entryUrl(String original) {
        try {
            URI uri = new URI(original);
            String host = uri.getHost();
            String query = uri.getRawQuery();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getPort() != -1 || host == null
                || !isThreadsHost(host)
                || !THREADS_POST.matcher(uri.getRawPath()).matches() || query == null) {
                return original;
            }

            // xmt is the share parameter seen on the link that opened the Android app.
            // Preserve the post path, host, other query values and fragment exactly.
            var kept = new ArrayList<String>();
            boolean removed = false;
            for (String field : query.split("&", -1)) {
                if (field.startsWith("xmt=") || field.equals("xmt")) removed = true;
                else kept.add(field);
            }
            if (!removed) return original;
            return original.substring(0, original.indexOf('?'))
                + (kept.isEmpty() ? "" : "?" + String.join("&", kept))
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (URISyntaxException e) {
            return original;
        }
    }
}
