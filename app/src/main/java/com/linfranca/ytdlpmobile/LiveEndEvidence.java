package com.linfranca.ytdlpmobile;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

/** Positive evidence only. HTTP errors, missing flags and channel-offline states are inconclusive. */
final class LiveEndEvidence {
    private boolean sawLive;
    private long lastSequence = -1;
    private String dashId;
    private java.util.Set<String> periods = new java.util.HashSet<>();
    synchronized String observe(String body) {
        if (body == null || body.length() > 524288) return null;
        String text = body.trim();
        if (text.startsWith("#EXTM3U")) {
            long sequence = 0; int count = 0; boolean end = false, media = false, awaitingUri = false;
            for (String line : text.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try { sequence = Long.parseLong(line.substring(22).trim()); } catch (Exception bad) { return null; }
                }
                if (line.startsWith("#EXTINF:")) {
                    if(awaitingUri) return null;
                    awaitingUri=true;
                } else if(!line.isEmpty() && !line.startsWith("#") && awaitingUri) {
                    count++;media=true;awaitingUri=false;
                }
                if (line.equals("#EXT-X-ENDLIST")) end=true;
            }
            if (awaitingUri || !media || count == 0 || sequence < 0 || sequence > Long.MAX_VALUE-count) return null;
            long last = sequence + count-1;
            if (end && sawLive && last>=lastSequence) return "HLS ENDLIST on previously live media playlist";
            if (!end) { sawLive=true;lastSequence=Math.max(lastSequence,last); }
            return null;
        }
        if (!text.startsWith("<")) return null;
        try {
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setXIncludeAware(false);f.setExpandEntityReferences(false);
            var root=f.newDocumentBuilder().parse(new InputSource(new StringReader(text))).getDocumentElement();
            if (!"MPD".equals(root.getLocalName())) return null;
            String id=root.getAttribute("id"),type=root.getAttribute("type");
            java.util.Set<String> current=new java.util.HashSet<>();
            var list=root.getElementsByTagNameNS("*","Period");
            for(int i=0;i<list.getLength();i++) {
                String p=((org.w3c.dom.Element)list.item(i)).getAttribute("id");if(!p.isEmpty()) current.add(p);
            }
            if ("dynamic".equals(type)) { dashId=id;periods=current;return null; }
            if ("static".equals(type) && dashId!=null && !dashId.isEmpty() && dashId.equals(id) &&
                    !java.util.Collections.disjoint(periods,current) &&
                    finiteDuration(root.getAttribute("mediaPresentationDuration")))
                return "DASH dynamic to finite static presentation with matching MPD and Period identity";
        } catch (Exception ignored) { /* No completion evidence from malformed or unsupported XML. */ }
        return null;
    }
    static boolean independentCompletionApplies(String reason, boolean relayPresent) {
        // A primary-playlist observer cannot certify a paired relay's other rendition.
        // Only the platform's matched whole-broadcast end can supersede track-local evidence.
        return reason != null && (!relayPresent || reason.startsWith("Platform explicitly ended the same "));
    }
    private static boolean finiteDuration(String value) {
        try { return !java.time.Duration.parse(value).isNegative() && !java.time.Duration.parse(value).isZero(); }
        catch(Exception ignored) { return false; }
    }
    static boolean broadcastEnded(String expectedId, String actualId, String liveStatus) {
        return expectedId!=null && !expectedId.isBlank() && expectedId.equals(actualId) &&
            ("was_live".equals(liveStatus) || "post_live".equals(liveStatus));
    }
}
