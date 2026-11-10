package com.linfranca.ytdlpmobile;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Exact public page routes, independent of the Android network and queue. */
final class CaptureSiteRoutes {
    enum Site {
        ARCHIVE("Internet Archive"), DAILY("Dailymotion"), BILI("Bilibili International"),
        KICK("Kick"), NG("Newgrounds"), NICO("Niconico"), ODYSEE("Odysee"),
        PEER("PeerTube"), RUMBLE("Rumble"), RU("RuTube"), SOOP("SOOP"),
        VIMEO("Vimeo"), VK("VK Video");
        final String label;
        Site(String label) { this.label = label; }
    }

    static final class Route {
        final Site site;
        final String id;
        Route(Site site, String id) { this.site=site; this.id=id; }
    }

    private static final String NAME = "[a-zA-Z0-9_.-]+";
    private static final String UUID = "[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}";
    static URI web(String url) {
        if (url == null) return null;
        try {
            URI u=URI.create(url);
            return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
                && u.getHost()!=null && u.getUserInfo()==null && u.getPort()==-1 ? u : null;
        } catch (IllegalArgumentException e) { return null; }
    }
    static String host(URI u) { return u.getHost().toLowerCase(Locale.ROOT); }
    static String path(URI u) { return u.getRawPath().replaceFirst("/+$",""); }
    static boolean domain(String host, String root) { return host.equals(root) || host.endsWith("." + root); }

    static Site site(String url) {
        URI u=web(url); if(u==null) return null;
        return switch(host(u)) {
            case "archive.org","www.archive.org" -> Site.ARCHIVE;
            case "dailymotion.com","www.dailymotion.com","geo.dailymotion.com","dai.ly" -> Site.DAILY;
            case "bilibili.tv","www.bilibili.tv" -> Site.BILI;
            case "kick.com","www.kick.com" -> Site.KICK;
            case "nicovideo.jp","www.nicovideo.jp","sp.nicovideo.jp" -> Site.NICO;
            case "odysee.com","www.odysee.com" -> Site.ODYSEE;
            case "peer.tube","lostpod.space" -> Site.PEER;
            case "rumble.com","www.rumble.com" -> Site.RUMBLE;
            case "rutube.ru","www.rutube.ru" -> Site.RU;
            case "sooplive.com","www.sooplive.com","play.sooplive.com","vod.sooplive.com" -> Site.SOOP;
            case "vimeo.com","www.vimeo.com","player.vimeo.com" -> Site.VIMEO;
            case "vkvideo.ru","www.vkvideo.ru","m.vkvideo.ru","live.vkvideo.ru" -> Site.VK;
            default -> domain(host(u),"newgrounds.com") ? Site.NG : null;
        };
    }
    private static Route match(Site site,String path,String regex) {
        var m=Pattern.compile(regex).matcher(path);
        return m.matches() ? new Route(site,m.group(1)) : null;
    }
    static Route video(String url) {
        Site s=site(url); if(s==null) return null;
        URI u=web(url); String p=path(u), h=host(u);
        return switch(s) {
            case ARCHIVE -> match(s,p,"/details/("+NAME+")");
            case DAILY -> match(s,p,"/(?:embed/)?video/([A-Za-z0-9]+)(?:_[^/]+)?");
            case BILI -> match(s,p,"/(?:[a-z]{2}(?:-[a-zA-Z]{2})?/)?video/(\\d+)");
            case KICK -> match(s,p,"/"+NAME+"/videos/("+UUID+")");
            case NG -> match(s,p,"/portal/view/(\\d+)");
            case NICO -> match(s,p,"/watch/((?:sm|so|nm)\\d+)");
            case ODYSEE -> match(s,p,"/(?:@[A-Za-z0-9_.%-]+(?::[a-fA-F0-9]{1,40})?/)?([^/@:][^/]*:[a-fA-F0-9]{1,40})");
            case PEER -> match(s,p,"/(?:w|videos/watch|videos/embed)/([A-Za-z0-9-]{8,48})");
            case RU -> match(s,p,"/(?:video|play/embed)/([a-fA-F0-9]{32})");
            case SOOP -> h.equals("vod.sooplive.com") ? match(s,p,"/player/(\\d+)") : null;
            case VIMEO -> match(s,p,"/(?:video/)?(\\d+)(?:/[a-f0-9]{6,20})?");
            case VK -> h.equals("live.vkvideo.ru") ? null : match(s,p,"/video(-?\\d+_\\d+)");
            case RUMBLE -> null; // Recorded playback trace is intentionally deferred.
        };
    }
    static boolean isSoopVod(String url) {
        Route r=video(url); return r!=null && r.site==Site.SOOP;
    }
    static Route vkLiveChannel(String url) {
        URI u=web(url);
        if(u==null || !host(u).equals("live.vkvideo.ru")) return null;
        var route=match(Site.VK,path(u),"/("+NAME+")(?:/records)?");
        if(route==null || route.id.equals(".") || route.id.equals("..") ||
            route.id.matches("(?i)(?:app|api|browse|catalog|login|records|search|settings)")) return null;
        return route;
    }
    static String vkLiveRoot(String url) {
        Route r=vkLiveChannel(url);
        return r==null ? null : "https://live.vkvideo.ru/"+r.id;
    }
    static Route live(String url) {
        Site s=site(url); if(s==null) return null;
        URI u=web(url); String p=path(u), h=host(u);
        return switch(s) {
            case KICK -> p.matches("/(?:categories|following|search|browse|login|signup|settings)(?:/.*)?") ? null : match(s,p,"/([a-zA-Z0-9_-]+)(?:/videos|/about|/clips|/schedule)?");
            case ODYSEE -> match(s,p,"/(@[A-Za-z0-9_.%-]+(?::[a-fA-F0-9]{1,40})?)(?:/[^/]+)?");
            case RUMBLE -> p.endsWith(".html") ? match(s,p,"/(v[a-zA-Z0-9]+(?:-[^/]+)?\\.html)") :
                match(s,p,"/((?:user|c)/"+NAME+")(?:/videos)?");
            case SOOP -> h.equals("vod.sooplive.com") ? null : match(s,p,"/(?:station/)?("+NAME+")(?:/\\d+)?");
            case VK -> vkLiveChannel(url);
            default -> null;
        };
    }
    static String livePage(String url) {
        Route r=live(url); if(r==null) return null;
        return switch(r.site) {
            case KICK -> "https://kick.com/"+r.id;
            case SOOP -> host(web(url)).equals("play.sooplive.com") ? url : "https://play.sooplive.com/"+r.id;
            case RUMBLE -> r.id.contains("/") ? "https://rumble.com/"+r.id : url;
            case VK -> vkLiveRoot(url);
            default -> url;
        };
    }
    static String uploads(String url) {
        Site s=site(url); if(s==null) return null;
        URI u=web(url);String p=path(u), h=host(u), root="https://"+h;
        return switch(s) {
            case DAILY -> p.matches("/(?:user|[A-Za-z]{2}/user)/"+NAME+"(?:/videos)?") ? root+p.replaceFirst("/videos$","") :
                p.matches("/"+NAME) && !p.matches("/(?:video|live|playlist|search|discover)") ? root+p : null;
            case BILI -> p.matches("/(?:[a-z]{2}(?:-[a-zA-Z]{2})?/)?space/\\d+(?:/videos)?") ? root+p.replaceFirst("/videos$","") : null;
            case KICK -> live(url)!=null ? "https://kick.com/"+live(url).id+"/videos" : null;
            case NG -> !h.equals("newgrounds.com") && !h.equals("www.newgrounds.com") &&
                !h.equals("uploads.newgrounds.com") && p.matches("(?:/movies)?") ? root+"/movies" : null;
            case NICO -> p.matches("/user/\\d+(?:/video)?") ? root+p.replaceFirst("/video$","")+"/video" : null;
            case ODYSEE -> p.matches("/@[A-Za-z0-9_.%-]+(?::[a-fA-F0-9]{1,40})?") ? root+p+"?view=content" : null;
            case PEER -> p.matches("/(?:c|video-channels)/[^/]+(?:/videos)?") ? root+p.replaceFirst("/videos$","")+"/videos" : null;
            case RUMBLE -> p.matches("/(?:user|c)/"+NAME+"(?:/videos)?") ? root+p.replaceFirst("/videos$","") : null;
            case RU -> p.matches("/(?:channel|u|feeds)/"+NAME+"(?:/videos)?") ? root+p :
                (video(url)!=null || p.matches("/(?:metainfo/tv|series)/"+NAME)) ? url : null;
            case SOOP -> p.matches("/station/"+NAME+"(?:/vod)?") ? "https://www.sooplive.com"+p.replaceFirst("/vod$","") : null;
            case VIMEO -> p.matches("/"+NAME+"(?:/videos)?") && video(url)==null &&
                !p.matches("/(?:watch|search|manage|ondemand|categories)") ? root+p.replaceFirst("/videos$","")+"/videos" : null;
            case VK -> !h.equals("live.vkvideo.ru") && p.matches("/@"+NAME+"(?:/videos)?") ? "https://vkvideo.ru"+p.replaceFirst("/videos$","")+"/videos" : null;
            default -> null;
        };
    }
    static String uploadId(String url,Site expected) {
        Route r=video(url);
        if(r!=null && r.site==expected) {
            // Odysee's readable slug is not a stable identity without its claim.
            if(expected==Site.ODYSEE && !r.id.matches(".+:[a-fA-F0-9]{1,40}")) return null;
            return r.id;
        }
        URI u=web(url);
        if(expected==Site.RUMBLE && site(url)==expected && u!=null)
            return path(u).matches("/v[a-zA-Z0-9]+(?:-[^/]+)?\\.html") ? path(u).substring(1).split("[-.]",2)[0] : null;
        return null;
    }
    private CaptureSiteRoutes() {}
}
