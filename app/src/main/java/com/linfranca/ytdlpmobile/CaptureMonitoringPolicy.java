package com.linfranca.ytdlpmobile;

import java.util.regex.Pattern;

final class CaptureMonitoringPolicy {
    static final String RULE_BINDING = "captureMonitoringUrl";
    static final String VK_DASH = "capture_verified_vk_dash";
    private static final Pattern ATTEMPT = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    static boolean eligibleRule(String url) {
        return CaptureSiteRoutes.livePage(url) != null || CaptureSiteRoutes.uploads(url) != null;
    }

    static boolean useCapturedRoutes(String binding, String url, String customSource) {
        return binding != null && !binding.isBlank() && binding.equals(url) &&
            (customSource == null || customSource.isBlank()) && eligibleRule(url);
    }

    static String bindingOnSave(boolean newRule, String previousBinding, String previousUrl,
                                String previousCustomSource, String url, String customSource) {
        if (!newRule && !useCapturedRoutes(previousBinding, previousUrl, previousCustomSource)) return null;
        if (customSource != null && !customSource.isBlank()) return null;
        return eligibleRule(url) ? url : null;
    }

    static boolean isCaptureId(String item, CaptureSiteRoutes.Site site) {
        if (item == null || site == null) return false;
        String prefix = "Capture:" + site.name() + ":";
        return item.startsWith(prefix) && item.length() > prefix.length();
    }

    // Keep the queue attempt ID intact. Only allow the coordinator's UUID suffix
    // when checking it against the complete provider broadcast ID.
    static boolean matchesQueuedSource(String source, String item, CaptureSiteRoutes.Site site) {
        if (!isCaptureId(source, site) || !isCaptureId(item, site)) return false;
        if (source.equals(item)) return true;
        String prefix = source + ":";
        return item.startsWith(prefix) && ATTEMPT.matcher(item.substring(prefix.length())).matches();
    }
    private CaptureMonitoringPolicy() {}
}
