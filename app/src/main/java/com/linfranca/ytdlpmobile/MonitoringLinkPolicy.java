package com.linfranca.ytdlpmobile;

import java.net.URI;
import java.util.*;

/** Exact aliases only. Unknown hosts and declarative custom-source addresses are never guessed. */
final class MonitoringLinkPolicy {
    static final Map<String,String> ALIASES;
    static {
        Map<String,String> a=new LinkedHashMap<>();
        for(String h:List.of("youtube.com","m.youtube.com")) a.put(h,"www.youtube.com");
        for(String h:List.of("twitter.com","www.twitter.com","mobile.twitter.com","mobile.x.com","www.x.com")) a.put(h,"x.com");
        for(String h:List.of("m.facebook.com","mobile.facebook.com","mbasic.facebook.com")) a.put(h,"www.facebook.com");
        for(String h:List.of("twitch.tv","m.twitch.tv","go.twitch.tv")) a.put(h,"www.twitch.tv");
        a.put("m.vk.com","vk.com");a.put("m.huya.com","www.huya.com");a.put("m.douyu.com","www.douyu.com");
        a.put("sp.nicovideo.jp","www.nicovideo.jp");
        ALIASES=Collections.unmodifiableMap(a);
    }
    static String input(String raw,boolean builtIn) {
        URI u=URI.create(raw.trim());String h=u.getHost();
        if(h==null || u.getUserInfo()!=null || !("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme())))
            throw new IllegalArgumentException("Enter a complete HTTP or HTTPS channel link without embedded credentials.");
        if(!builtIn) return raw.trim();
        h=h.toLowerCase(Locale.ROOT);h=ALIASES.getOrDefault(h,h);
        return u.getScheme().toLowerCase(Locale.ROOT)+"://"+h+(u.getPort()<0 ? "" : ":"+u.getPort())+
            (u.getRawPath()==null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())+
            (u.getRawQuery()==null ? "" : "?"+u.getRawQuery());
    }
    static boolean shortLink(String url) {
        URI u=URI.create(url);String h=u.getHost().toLowerCase(Locale.ROOT);
        return Set.of("t.co","vm.tiktok.com","vt.tiktok.com","b23.tv","fb.watch","www.fb.watch","dai.ly","nico.ms").contains(h) ||
            ((h.equals("www.tiktok.com") || h.equals("tiktok.com")) && u.getPath().startsWith("/t/"));
    }
    static String shortHandler(String host) {
        return switch(host) {
            case "youtu.be" -> "YouTube";
            case "vm.tiktok.com","vt.tiktok.com" -> "TikTok";
            case "t.co" -> "X";
            case "b23.tv" -> "Bilibili";
            case "fb.watch" -> "Facebook";
            case "dai.ly" -> "Dailymotion";
            case "nico.ms" -> "Niconico";
            default -> null;
        };
    }
    static void validateChannel(String url) {
        if(shortLink(url)) return;
        URI u=URI.create(url);String p=u.getPath().replaceAll("/+$","");String handler=MonitoringPolicy.handler(url);
        boolean item=switch(handler) {
            case "YouTube" -> u.getHost().equals("youtu.be") || p.equals("/watch") || p.matches("/(shorts|live|embed)/.+") || p.equals("/playlist");
            case "X" -> p.matches("/.*/status/.*") || p.startsWith("/i/");
            case "TikTok" -> p.contains("/video/");
            case "Instagram" -> p.matches("/(p|reel|reels|tv)/.+");
            case "Twitch" -> p.matches("/videos/.*") || p.contains("/clip/") || p.startsWith("/collections/");
            case "Dailymotion" -> p.startsWith("/video/") || p.startsWith("/playlist/");
            case "Bilibili" -> p.startsWith("/video/") || p.startsWith("/bangumi/");
            default -> false;
        };
        if(item) throw new IllegalArgumentException("This is an individual item or playlist link. For monitoring, paste the creator's channel/profile page instead.");
    }
    static boolean changedUploadScope(String url) {
        URI u=URI.create(url);String path=u.getRawPath()==null ? "" : u.getRawPath();
        String handler=MonitoringPolicy.handler(url);String oldPath=path.replaceAll("/+$","");String oldQuery=u.getRawQuery();
        if(handler.equals("YouTube")) { oldPath=oldPath.replaceAll("/(videos|shorts|streams|live|featured)$","")+"/videos";oldQuery=null; }
        else if(handler.equals("Twitch")) { oldPath=oldPath.replaceAll("/(videos|about|schedule)$","")+"/videos";oldQuery="filter=archives&sort=time"; }
        else return false;
        URI now=URI.create(MonitoringPolicy.target(url,false));
        return !oldPath.equals(now.getRawPath()) || !Objects.equals(oldQuery,now.getRawQuery());
    }
    static String query(String raw,Set<String> omit) {
        if(raw==null || raw.isBlank()) return "";
        return Arrays.stream(raw.split("&")).filter(s->!omit.contains(s.split("=",2)[0].toLowerCase(Locale.ROOT))).reduce((a,b)->a+"&"+b).map(s->"?"+s).orElse("");
    }
}
