package com.linfranca.ytdlpmobile;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Conservative item identity: signed URLs and unknown-site query parameters are preserved. */
final class TransferIdentity {
    static String key(String value, boolean playlist) {
        try {
            URI uri=URI.create(value.trim());
            String host=uri.getHost();
            if(host==null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return "";
            host=host.toLowerCase(Locale.ROOT);
            String path=uri.getRawPath();
            String id="";
            if(!playlist) {
                if(host.equals("youtu.be")) id=uri.getPath().substring(1).split("/")[0];
                else if(host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com")) {
                    String[] parts=uri.getPath().split("/");
                    if(parts.length>=3 && (parts[1].equals("shorts") || parts[1].equals("live") || parts[1].equals("embed"))) id=parts[2];
                    else if(uri.getPath().equals("/watch") && uri.getRawQuery()!=null) {
                        for(String pair:uri.getRawQuery().split("&")) if(pair.startsWith("v=")) id=URLDecoder.decode(pair.substring(2),"UTF-8");
                    }
                }
            }
            String normalized;
            if(id.matches("[A-Za-z0-9_-]{11}")) normalized="youtube-video:"+id;
            else {
                // A fragment can select different media on an unknown site. Keep it too.
                normalized=uri.getScheme().toLowerCase(Locale.ROOT)+"://"+host+
                    (uri.getPort()<0 ? "" : ":"+uri.getPort())+(path==null || path.isEmpty()?"/":path)+
                    (uri.getRawQuery()==null?"":"?"+uri.getRawQuery())+(uri.getRawFragment()==null?"":"#"+uri.getRawFragment());
            }
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(((playlist?"collection:":"item:")+normalized).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte v:digest) hex.append(String.format(Locale.ROOT,"%02x",v & 255));return hex.toString();
        } catch(Exception ignored) { return ""; }
    }
}
