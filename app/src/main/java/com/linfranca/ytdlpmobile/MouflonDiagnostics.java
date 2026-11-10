package com.linfranca.ytdlpmobile;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Structural diagnostics only: never retain custom payloads, URI values or keys. */
final class MouflonDiagnostics {
    static String tag(String line) {
        Matcher m=Pattern.compile("^#EXT-X-MOUFLON:([A-Za-z][A-Za-z0-9_-]{0,23})(?::|$)").matcher(line);
        return m.find() ? m.group(1) : "unrecognized";
    }
    static void record(String body, Consumer<String> log) {
        if(log==null || !body.contains("#EXT-X-MOUFLON:")) return;
        String kind=body.contains("#EXT-X-STREAM-INF:") ? "master" : "media";
        String[] lines=body.split("\\r?\\n");
        Map<String,Integer> tags=new TreeMap<>();
        for(String line:lines) if(line.startsWith("#EXT-X-MOUFLON:")) tags.merge(tag(line),1,Integer::sum);
        log.accept("mouflon_structure; playlist="+kind+"; lines="+lines.length+"; custom tags="+tags);
        for(int i=0;i<Math.min(lines.length,160);i++) {
            String line=lines[i].trim(), safe;
            if(line.startsWith("#EXT-X-MOUFLON:")) safe="#EXT-X-MOUFLON:"+tag(line)+":[payload omitted]";
            else if(line.isEmpty()) safe="[blank]";
            else if(!line.startsWith("#")) safe="[media/rendition URI omitted]";
            else {
                Matcher m=Pattern.compile("^(#(?:EXT[A-Z0-9_-]*|EXTM3U))(?::|$)").matcher(line);
                safe=m.find() ? m.group(1) : "[comment omitted]";
                if(line.matches("#(?:EXTINF|EXT-X-MEDIA-SEQUENCE|EXT-X-DISCONTINUITY-SEQUENCE|EXT-X-TARGETDURATION|EXT-X-VERSION):[0-9.]+,?")) safe=line;
                else if(line.startsWith("#EXT-X-PART:")) {
                    Matcher duration=Pattern.compile("(?:^|,)DURATION=([0-9.]+)(?:,|$)").matcher(line.substring(12));
                    if(duration.find()) safe+=":DURATION="+duration.group(1)+"; URI omitted";
                }
            }
            log.accept("mouflon_snapshot; playlist="+kind+"; line="+(i+1)+"; "+safe);
        }
        if(lines.length>160) log.accept("mouflon_snapshot_truncated; remaining lines="+(lines.length-160));
    }
}
