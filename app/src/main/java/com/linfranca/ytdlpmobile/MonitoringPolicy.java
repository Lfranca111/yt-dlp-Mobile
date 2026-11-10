package com.linfranca.ytdlpmobile;

import java.net.URI;
import java.time.*;
import java.util.*;

/** Pure scheduling/routing policy, independent of downloads and Android UI. */
public final class MonitoringPolicy {
    public static final int[] INTERVALS = {5, 15, 30, 60, 180, 360, 720, 1440};
    public static String normalize(String input) {
        return MonitoringLinkPolicy.input(input,true);
    }
    public static String handler(String url) {
        String h = URI.create(normalize(url)).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        if (CaptureSiteRoutes.vkLiveChannel(url)!=null) return "VK Video Live";
        String shortHandler=MonitoringLinkPolicy.shortHandler(h);if(shortHandler!=null) return shortHandler;
        if (h.equals("chaturbate.com") || h.equals("chaturbate.eu") || h.equals("chaturbate.global")) return "Chaturbate";
        if (h.equals("stripchat.com")) return "Stripchat";
        if (h.equals("xhamsterlive.com")) return "XHamsterLive";
        Map<String,String> sites = new LinkedHashMap<>();
        sites.put("youtube.com", "YouTube"); sites.put("twitch.tv", "Twitch"); sites.put("kick.com", "Kick");
        sites.put("x.com", "X"); sites.put("instagram.com", "Instagram"); sites.put("facebook.com", "Facebook");
        sites.put("tiktok.com", "TikTok"); sites.put("vimeo.com", "Vimeo"); sites.put("dailymotion.com", "Dailymotion");
        sites.put("rumble.com", "Rumble"); sites.put("odysee.com", "Odysee"); sites.put("bitchute.com", "BitChute");
        sites.put("rutube.ru", "RuTube"); sites.put("picarto.tv", "Picarto"); sites.put("bilibili.com", "Bilibili");
        sites.put("nicovideo.jp", "Niconico"); sites.put("vk.com", "VK"); sites.put("chzzk.naver.com", "CHZZK");
        sites.put("huya.com", "Huya"); sites.put("douyu.com", "Douyu"); sites.put("sooplive.com", "SOOP");
        sites.put("aniwaves.ru", "AniWaves"); sites.put("h123movies.com", "h123movies"); sites.put("wcostream.tv", "WCOStream");
        sites.put("newgrounds.com", "Newgrounds"); sites.put("reddit.com", "Reddit");
        for (Map.Entry<String,String> e : sites.entrySet())
            if (h.equals(e.getKey()) || h.endsWith("." + e.getKey())) return e.getValue();
        return "General fallback";
    }
    public static String target(String url, boolean live) {
        url=normalize(url);
        URI parsed=URI.create(url);
        String path=parsed.getRawPath()==null ? "" : parsed.getRawPath().replaceAll("/+$","");
        String root=parsed.getScheme()+"://"+parsed.getRawAuthority();
        String query=MonitoringLinkPolicy.query(parsed.getRawQuery(),Set.of("feature","si","app","view"));
        if(handler(url).equals("YouTube")) {
            if(live) path=path.replaceAll("/(videos|shorts|streams|live|featured)$","")+"/streams";
            else if(!path.matches(".*/(videos|shorts|streams)$")) path=path.replaceAll("/(live|featured)$","")+"/videos";
            return root+path+query;
        }
        if(handler(url).equals("Twitch")) {
            if(live) return root+path.replaceAll("/(videos|about|schedule|clips)$","");
            if(path.endsWith("/videos") || path.endsWith("/clips")) return root+path+query;
            String q=query;
            String raw=parsed.getRawQuery()==null ? "" : parsed.getRawQuery();
            if(!raw.matches("(?s)(^|.*&)filter=.*")) q+=(q.isEmpty() ? "?" : "&")+"filter=archives";
            if(!raw.matches("(?s)(^|.*&)sort=.*")) q+=(q.isEmpty() ? "?" : "&")+"sort=time";
            return root+path.replaceAll("/(about|schedule)$","")+"/videos"+q;
        }
        return url;
    }
    public static boolean inWindow(long now, String zone, int start, int end, Set<Integer> days) {
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone));
        int minute = t.getHour()*60 + t.getMinute();
        int day = t.getDayOfWeek().getValue();
        if (start < 0) return days.contains(day);
        if (start == end) return days.contains(day);
        if (start < end) return days.contains(day) && minute >= start && minute < end;
        // Overnight window belongs to the day on which it starts.
        return minute >= start ? days.contains(day) : minute < end && days.contains(day == 1 ? 7 : day-1);
    }
    public static long next(long now, int interval, int failures, String zone, int start, int end, Set<Integer> days) {
        if (days.isEmpty()) throw new IllegalArgumentException("Select at least one monitoring day.");
        long delay = Math.max(5, interval) * 60000L;
        if (failures > 0) delay = Math.max(delay, Math.min(1440L*60000, 5L*60000*(1L << Math.min(failures,8))));
        long candidate = now + delay;
        if (inWindow(candidate, zone, start, end, days)) return candidate;
        // Minute stepping naturally handles DST transitions and overnight windows.
        candidate = ((candidate / 60000) + 1)*60000;
        for (int i=0; i<8*24*60; i++, candidate+=60000)
            if (inWindow(candidate, zone, start, end, days)) return candidate;
        throw new IllegalArgumentException("No monitoring window could be scheduled.");
    }
    private MonitoringPolicy() {}
}
