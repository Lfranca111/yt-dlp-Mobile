package com.linfranca.ytdlpmobile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Session-local MOUFLON v2 adapter learned only from the player's own requests.
 * No decoding keys, request URLs, or header values are written to diagnostics. */
final class MouflonPlaylistResolver {
    private static final long TTL_NS = 120_000_000_000L;
    private static final int MAX_OBSERVATIONS = 512;
    private static final LinkedHashMap<String, Observation> OBSERVED = new LinkedHashMap<>();
    private static final Pattern SEGMENT = Pattern.compile(
        "^(https://[^/]+/b-hls-\\d+/\\d+/)(\\d+)_([A-Za-z0-9]+_[A-Za-z0-9]+)_(\\d+)_([^_]+)_(\\d+)(_part\\d+)?\\.mp4(?:\\?[^#]*)?$");
    private static final Pattern MASTER = Pattern.compile("^/hls/(\\d+)/master/");
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    interface Clock { long now(); }
    private static final Clock CLOCK = System::nanoTime;
    private final String model;
    private final Consumer<String> log;
    private final Clock clock;
    private final Map<String, Mapping> mappings = new HashMap<>();
    private long revision;
    private final Set<String> diagnosticShapes=new HashSet<>();
    private void recordStructure(String body) {
        Set<String> tags=new TreeSet<>();
        for(String line:body.split("\\r?\\n")) if(line.startsWith("#EXT-X-MOUFLON:")) tags.add(MouflonDiagnostics.tag(line));
        String shape=(body.contains("#EXT-X-STREAM-INF:") ? "master" : "media")+tags;
        if(diagnosticShapes.size()<16 && diagnosticShapes.add(shape)) MouflonDiagnostics.record(body,log);
    }

    private static final class Observation {
        final String url;
        final Map<String,String> headers;
        final long time;
        Observation(String url, Map<String,String> headers, long time) {
            this.url = url; this.headers = new HashMap<>(headers); this.time = time;
        }
    }
    private static final class Address {
        final String url, prefix, model, quality, sequence, token, timestamp, part;
        Address(String url, Matcher m) {
            this.url=url; prefix=m.group(1); model=m.group(2); quality=m.group(3);
            sequence=m.group(4); token=m.group(5); timestamp=m.group(6);
            part=m.group(7)==null ? "" : m.group(7);
        }
        String identity() { return prefix + model + ":" + quality + ":" + sequence + ":" + timestamp + ":" + part; }
        String replace(String plain) {
            int at = prefix.length() + model.length() + quality.length() + sequence.length() + 3;
            return url.substring(0,at) + plain + url.substring(at + token.length());
        }
    }
    private static final class Mapping {
        final byte[] mask;
        final long learned;
        final long revision;
        Mapping(byte[] mask, long learned, long revision) { this.mask=mask; this.learned=learned; this.revision=revision; }
    }
    static final class Pending extends Exception {
        private static final long serialVersionUID = 1L;
        Pending() { super("Fresh browser mapping not verified"); }
    }

    MouflonPlaylistResolver(String master, Consumer<String> log) {
        this(master,log,CLOCK);
    }
    MouflonPlaylistResolver(String master, Consumer<String> log, Clock clock) {
        URI uri = URI.create(master);
        Matcher m = MASTER.matcher(uri.getPath());
        if (!allowed(uri) || !m.find()) throw new IllegalArgumentException("Unverified MOUFLON master identity");
        model=m.group(1); this.log=log; this.clock=clock;
    }
    static boolean compatibleMaster(String url) {
        try { URI u=URI.create(url); return allowed(u) && MASTER.matcher(u.getPath()).find(); }
        catch(RuntimeException invalid) { return false; }
    }
    static boolean observedMaster(String url) {
        return snapshot().stream().anyMatch(o -> o.url.equals(url));
    }
    private static boolean allowed(URI uri) {
        String host=uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo()==null &&
            (uri.getPort()==-1 || uri.getPort()==443) && host!=null &&
            (host.endsWith(".doppiocdn.net") || host.endsWith(".doppiocdn.media") || host.endsWith(".doppiocdn.live"));
    }
    static synchronized boolean observe(String url, Map<String,String> headers) {
        try {
            URI uri=URI.create(url);
            if (!allowed(uri) || !(uri.getPath().endsWith(".m3u8") || uri.getPath().endsWith(".mp4"))) return false;
            long now=CLOCK.now();
            OBSERVED.entrySet().removeIf(e -> now-e.getValue().time > TTL_NS);
            OBSERVED.remove(url);
            OBSERVED.put(url,new Observation(url,headers,now));
            while (OBSERVED.size()>MAX_OBSERVATIONS) OBSERVED.remove(OBSERVED.keySet().iterator().next());
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
    private static synchronized List<Observation> snapshot() {
        long now=CLOCK.now();
        OBSERVED.entrySet().removeIf(e -> now-e.getValue().time > TTL_NS);
        return new ArrayList<>(OBSERVED.values());
    }
    static synchronized void clearObservations() { OBSERVED.clear(); }
    private static Address address(String url) {
        try {
            if (!allowed(URI.create(url))) return null;
            Matcher m=SEGMENT.matcher(url);
            return m.matches() && m.group(1).endsWith("/"+m.group(2)+"/") ? new Address(url,m) : null;
        } catch (RuntimeException bad) { return null; }
    }
    /** Choose the rendition the browser is actually playing, not an unrelated quality. */
    String preferredRendition(String masterUrl, String body) throws Pending {
        recordStructure(body);
        Set<String> variants=new HashSet<>();
        String[] lines=body.split("\\r?\\n");
        for(int i=0;i<lines.length-1;i++) if(lines[i].startsWith("#EXT-X-STREAM-INF:")) {
            int j=i+1; while(j<lines.length && lines[j].trim().isEmpty()) j++;
            if(j<lines.length && !lines[j].startsWith("#")) {
                URI u=URI.create(masterUrl).resolve(lines[j].trim());
                if(allowed(u) && u.getPath().contains("/"+model+"/")) variants.add(u.getScheme()+"://"+u.getAuthority()+u.getPath());
            }
        }
        List<Observation> observed=snapshot();
        for(int i=observed.size()-1;i>=0;i--) {
            URI u=URI.create(observed.get(i).url);
            if(variants.contains(u.getScheme()+"://"+u.getAuthority()+u.getPath())) {
                log.accept("mouflon_browser_rendition_matched; stream identity verified; header values omitted");
                return observed.get(i).url;
            }
        }
        log.accept("mouflon_browser_rendition_pending; no fresh request matches master variants");
        throw new Pending();
    }
    LiveHlsRelay.Access accessFor(URI uri, LiveHlsRelay.Access fallback) {
        List<Observation> entries=snapshot();
        Observation sameOrigin=null;
        for (Observation entry:entries) {
            URI seen=URI.create(entry.url);
            if (seen.getAuthority().equalsIgnoreCase(uri.getAuthority())) sameOrigin=entry;
            if (entry.url.equals(uri.toString())) { sameOrigin=entry; break; }
        }
        Map<String,String> headers=new HashMap<>();
        if (sameOrigin!=null) for(Map.Entry<String,String> h:sameOrigin.headers.entrySet()) {
            String key=h.getKey().toLowerCase(Locale.ROOT);
            if (Set.of("user-agent","referer","cookie","accept","accept-language","origin").contains(key)) headers.put(key,h.getValue());
        }
        return new LiveHlsRelay.Access(headers.getOrDefault("user-agent",fallback.agent),
            headers.getOrDefault("referer",fallback.referer),headers.getOrDefault("cookie",""),headers);
    }
    synchronized void mediaResponse(URI uri, int status, boolean verifiedMp4) {
        Address a=address(uri.toString());
        if(a==null || !model.equals(a.model)) return;
        if(status==200 && verifiedMp4) {
            for(Map.Entry<String,Mapping> e:mappings.entrySet()) if(e.getKey().endsWith(":"+a.quality)) {
                Mapping m=e.getValue(); e.setValue(new Mapping(m.mask,clock.now(),m.revision));
            }
        } else if(status==403 || status==406) {
            mappings.entrySet().removeIf(e -> e.getKey().endsWith(":"+a.quality));
            log.accept("mouflon_mapping_recovery_required; segment HTTP="+status+"; fresh browser observations needed");
        }
    }
    synchronized String rewrite(URI base, String body) throws Pending {
        mappings.entrySet().removeIf(e -> clock.now()-e.getValue().learned > TTL_NS);
        if(!allowed(base)) throw new IllegalArgumentException("Unverified MOUFLON playlist host");
        if(!body.startsWith("#EXTM3U")) throw new IllegalArgumentException("Not HLS");
        if(!body.contains("#EXT-X-MOUFLON:")) throw new IllegalArgumentException("MOUFLON processing disappeared; fresh detection required");
        recordStructure(body);
        List<String> schemes=new ArrayList<>();
        List<Address> encrypted=new ArrayList<>();
        int lineNumber=0;
        for(String line:body.split("\\r?\\n")) {
            lineNumber++;
            if(line.startsWith("#EXT-X-MOUFLON:PSCH:")) {
                if(!line.startsWith("#EXT-X-MOUFLON:PSCH:v2:")) throw new IllegalArgumentException("Unsupported MOUFLON scheme");
                schemes.add(line.substring("#EXT-X-MOUFLON:PSCH:".length()));
            } else if(line.startsWith("#EXT-X-MOUFLON:URI:")) {
                Address a=address(base.resolve(line.substring("#EXT-X-MOUFLON:URI:".length()).trim()).toString());
                if(a==null || !model.equals(a.model)) throw new IllegalArgumentException("MOUFLON segment identity mismatch");
                encrypted.add(a);
            } else if(line.startsWith("#EXT-X-MOUFLON:EXT-REF:")) {
                // Player parser publishes this as latencyExtraInformationAvailable.
                // It changes neither the segment URI nor the source media timeline.
            } else if(line.startsWith("#EXT-X-MOUFLON:")) {
                String detail="Unsupported MOUFLON tag="+MouflonDiagnostics.tag(line)+"; playlist=media; line="+lineNumber;
                log.accept("mouflon_failure; stage=tag inspection; "+detail);
                throw new IllegalArgumentException(detail);
            }
        }
        if(schemes.isEmpty() || encrypted.isEmpty()) throw new IllegalArgumentException("Incomplete MOUFLON media playlist");
        Map<String,Address> actual=new HashMap<>();
        for(Observation o:snapshot()) {
            Address a=address(o.url); if(a!=null && model.equals(a.model)) actual.put(a.identity(),a);
        }
        int matched=0;
        for(Address a:encrypted) if(actual.containsKey(a.identity())) matched++;
        log.accept("mouflon_browser_comparison; playlist entries="+encrypted.size()+"; matching browser segments="+matched+"; stage=mapping verification");
        Map<String,List<Address>> qualities=new HashMap<>();
        for(Address a:encrypted) qualities.computeIfAbsent(a.quality,k -> new ArrayList<>()).add(a);
        Map<String,Mapping> selected=new HashMap<>();
        for(Map.Entry<String,List<Address>> group:qualities.entrySet()) {
            String key=String.join(";",schemes)+":"+group.getKey();
            Mapping old=mappings.get(key);
            byte[] candidate=null; Set<String> sequences=new HashSet<>(); boolean conflict=false;
            for(Address a:group.getValue()) {
                Address seen=actual.get(a.identity()); if(seen==null) continue;
                byte[] cipher=decode(a.token), plain=seen.token.getBytes(StandardCharsets.US_ASCII);
                if(cipher.length!=16 || plain.length!=16 || !seen.token.matches("[A-Za-z0-9]{16}")) continue;
                byte[] mask=new byte[16]; for(int i=0;i<16;i++) mask[i]=(byte)(cipher[i]^plain[i]);
                if(candidate==null) candidate=mask; else if(!Arrays.equals(candidate,mask)) conflict=true;
                sequences.add(a.sequence+":"+a.timestamp);
            }
            if(conflict || (candidate!=null && old!=null && !Arrays.equals(candidate,old.mask))) {
                mappings.remove(key); old=null;
                log.accept("mouflon_mapping_invalidated; conflicting fresh browser observations; relearning");
            }
            if(!conflict && candidate!=null && sequences.size()>=2) {
                if(old==null) {
                    old=new Mapping(candidate,clock.now(),++revision);
                    if(mappings.size()>=32) mappings.clear();
                    mappings.put(key,old);
                    log.accept("mouflon_mapping_verified; revision="+revision+"; independent sequences="+sequences.size()+"; stream/quality/timestamp/part matched; decoding values omitted");
                }
            }
            if(old==null || clock.now()-old.learned>TTL_NS) {
                mappings.remove(key);
                log.accept("mouflon_mapping_pending; matched independent sequences="+sequences.size()+"; waiting for fresh browser requests");
                throw new Pending();
            }
            // Refresh lifetime only when current observations corroborate the mapping.
            if(candidate!=null && !conflict && Arrays.equals(candidate,old.mask)) {
                old=new Mapping(old.mask,clock.now(),old.revision); mappings.put(key,old);
            }
            selected.put(group.getKey(),old);
        }
        StringBuilder result=new StringBuilder(); String pending=null; int count=0;
        for(String line:body.split("\\r?\\n")) {
            if(line.startsWith("#EXT-X-MOUFLON:URI:")) {
                if(pending!=null) throw new IllegalArgumentException("Unconsumed custom URI");
                Address a=address(base.resolve(line.substring("#EXT-X-MOUFLON:URI:".length()).trim()).toString());
                Mapping m=selected.get(a.quality); byte[] cipher=decode(a.token);
                if(cipher.length!=m.mask.length) throw new IllegalArgumentException("MOUFLON token length changed");
                for(int i=0;i<cipher.length;i++) cipher[i]^=m.mask[i];
                String plain=new String(cipher,StandardCharsets.US_ASCII);
                if(!plain.matches("[A-Za-z0-9]{16}")) throw new IllegalArgumentException("Resolved token failed validation");
                pending=a.replace(plain); count++;
            } else if(line.startsWith("#EXT-X-MOUFLON:EXT-REF:")) {
                // Optional player latency telemetry; payload deliberately not logged.
            } else if(line.startsWith("#EXT-X-MOUFLON:PSCH:")) {
                // Consumed above; all timing and discontinuity tags remain verbatim.
            } else if(line.startsWith("#EXT-X-PART:") || line.startsWith("#EXT-X-PRELOAD-HINT:")) {
                Matcher attr=URI_ATTRIBUTE.matcher(line);
                if(pending==null || !attr.find()) throw new IllegalArgumentException("Unmatched custom part");
                result.append(line.substring(0,attr.start(1))).append(pending).append(line.substring(attr.end(1))).append('\n'); pending=null;
            } else if(!line.isEmpty() && !line.startsWith("#")) {
                if(pending==null) throw new IllegalArgumentException("Unmatched placeholder segment");
                result.append(pending).append('\n'); pending=null;
            } else {
                if(line.startsWith("#EXT-X-MAP:")) {
                    Matcher attr=URI_ATTRIBUTE.matcher(line);
                    if(!attr.find() || !allowed(base.resolve(attr.group(1))) ||
                        !base.resolve(attr.group(1)).getPath().contains("/"+model+"/"))
                        throw new IllegalArgumentException("Invalid initialization URI");
                }
                result.append(line).append('\n');
            }
        }
        if(pending!=null) throw new IllegalArgumentException("Unconsumed custom URI");
        if(body.contains("#EXT-X-MOUFLON:EXT-REF:"))
            log.accept("mouflon_ext_ref_handled; player latency metadata omitted; source media timestamps unchanged");
        log.accept("mouflon_playlist_resolved; entries="+count+"; source timing and discontinuities preserved; HTTP segment verification follows");
        return result.toString();
    }
    private static byte[] decode(String token) {
        return Base64.getDecoder().decode(new StringBuilder(token).reverse().toString());
    }
}
