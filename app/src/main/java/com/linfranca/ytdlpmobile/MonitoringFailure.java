package com.linfranca.ytdlpmobile;

import java.util.Locale;

/** Fixed user-facing categories. Never include request URLs, response HTML or credentials. */
final class MonitoringFailure {
    static String kind(String detail) {
        String s=detail==null ? "" : detail.toLowerCase(Locale.ROOT);
        if(s.contains("time limit already exhausted") || s.contains("foreground-service time budget") || s.contains("foreground service time budget")) return "quota";
        if(s.contains("age confirmation required") || s.contains("agreement or consent required")) return "interaction";
        if(s.contains("captcha") || s.contains("verify you are human") || s.contains("are you human") || s.contains("human verification required")) return "verification";
        if(s.contains("http 429") || s.contains("http error 429") || s.contains("too many requests")) return "rate";
        if(s.contains("http 403") || s.contains("http 401") || s.contains("http error 403") || s.contains("status=403") || s.contains("403 forbidden")) return "access";
        if(s.contains("foregroundservicestartnotallowed") || s.contains("startforegroundservice() not allowed") || s.contains("foreground service start not allowed") || s.contains("background handoff unavailable")) return "background";
        if(s.contains("canceledexception") || s.contains("cancelled") || s.contains("canceled") || s.contains("interrupted")) return "canceled";
        if(s.contains("no address associated") || s.contains("name or service not known") || s.contains("unknownhost") || s.contains("name resolution") || s.contains("getaddrinfo")) return "dns";
        if(!s.contains("monitoringrenderdeferred") && (s.contains("timed out") || s.contains("timeout") || s.contains("network is unreachable") || s.contains("connection reset") || s.contains("connection refused"))) return "network";
        if(s.contains("unable to recognize tab") || s.contains("unsupported url") || s.contains("unable to extract")) return "recognition";
        if(s.contains("sign in") || s.contains("login required") || s.contains("authentication") || s.contains("cookies are no longer valid")) return "authentication";
        if(s.contains("browsercheckrequired")) return "interaction";
        if(s.contains("monitoringrenderdeferred")) return "browser";
        return "extractor";
    }
    static String message(String detail) {
        return switch(kind(detail)) {
            case "quota" -> "Android foreground-service time budget is unavailable. Queued work is retained; opening the app allows eligibility to be rechecked. Battery exemption does not remove this limit.";
            case "rate" -> "Website returned HTTP 429 (too many requests). Wait before retrying; existing monitoring state is retained.";
            case "access" -> "Website denied access (HTTP 401/403); cause unconfirmed. Fresh detection will be retried within limits. Review sign-in/verification, stream expiry and request/session details if it persists.";
            case "verification" -> "Website reports human verification is needed. Complete it in the monitoring browser, then retry; saved rules are retained.";
            case "browser" -> "Temporary browser verification did not complete. Stream state remains unknown; bounded retries are scheduled. Use Open browser to check or Retry live stream in Browser Assist if needed.";
            case "interaction" -> "The website needs browser interaction. Open the channel to sign in or complete verification, then retry. Stream state remains unknown.";
            case "background" -> "Android blocked background media startup; existing queued item retained.";
            case "canceled" -> "Check interrupted or canceled; existing baseline retained.";
            case "dns" -> "DNS lookup failed. Check the connection, VPN or private DNS and retry. Existing baseline retained.";
            case "network" -> "Network request failed or timed out. Retry when connected. Existing baseline retained.";
            case "recognition" -> "The extractor could not recognize this page. Update yt-dlp and check the channel/profile link. Existing baseline retained.";
            case "authentication" -> "Website access needs sign-in or cookies. Inspect the channel and retry; existing baseline retained.";
            default -> "Website/extractor check failed. Review the detailed monitoring log; existing baseline retained.";
        };
    }
    static String stop(int code) {
        return switch(code) {
            case -256 -> "not stopped / no stop reason";
            case -512 -> "unknown stop reason";
            case 0 -> "unspecified system stop";
            case 1 -> "canceled by app or replacement work";
            case 2 -> "preempted by another job";
            case 3 -> "execution timeout";
            case 4 -> "device state restriction";
            case 5 -> "battery-not-low requirement no longer met";
            case 6 -> "charging requirement no longer met";
            case 7 -> "connectivity requirement no longer met";
            case 8 -> "device-idle requirement no longer met";
            case 9 -> "storage-not-low requirement no longer met";
            case 10 -> "background execution quota exceeded";
            case 11 -> "Android background restriction; review app battery/background settings";
            case 12 -> "app standby bucket restriction";
            case 13 -> "user stopped the job";
            case 14 -> "system processing stop";
            case 15 -> "estimated app launch time changed";
            default -> "unrecognized Android stop reason ("+code+")";
        };
    }
}
