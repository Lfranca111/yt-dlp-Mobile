package com.linfranca.ytdlpmobile;
import java.net.URI;
/** One equivalent spelling; never retry a broader creator root or drop the selected tab/query. */
final class MonitoringLinkFallback {
    static String alternative(String input) {
        URI u=URI.create(input);
        String host=switch(u.getHost()) {
            case "www.youtube.com" -> "youtube.com";
            case "www.twitch.tv" -> "twitch.tv";
            default -> null;
        };
        if(host==null || u.getUserInfo()!=null || u.getPort()!=-1) return null;
        return u.getScheme()+"://"+host+u.getRawPath()+(u.getRawQuery()==null ? "" : "?"+u.getRawQuery());
    }
}
