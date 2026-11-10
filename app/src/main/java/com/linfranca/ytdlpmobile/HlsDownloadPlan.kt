package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Content-based VOD planning. URL extensions and response MIME types are not evidence. */
internal object HlsDownloadPlan {
    data class Variant(val url: String,val attrs: Map<String,String>)
    data class Playlist(val variants: List<Variant>,val audio: List<Map<String,String>>,val duration: Double,val segments: Int,val ended: Boolean,val encrypted: Boolean,val firstSegment: String="")
    fun attributes(line: String): Map<String,String> = Regex("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))").findAll(line.substringAfter(':')).associate {
        it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] }.trim()
    }
    fun resolve(base: String,value: String): String=URI(base).resolve(value.trim()).toString().also {
        val u=URI(it);require(u.scheme in setOf("https","http") && !u.host.isNullOrBlank() && u.userInfo==null) { "Invalid playlist resource" }
    }
    fun parse(url: String,text: String): Playlist {
        require(text.trimStart('\uFEFF',' ','\r','\n','\t').startsWith("#EXTM3U")) { "Response is not an HLS playlist" }
        val lines=text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val variants=mutableListOf<Variant>();val audio=mutableListOf<Map<String,String>>()
        var duration=0.0;var segments=0;var protected=false;var firstSegment=""
        for(i in lines.indices) {
            val line=lines[i]
            if(line.startsWith("#EXT-X-STREAM-INF:")) {
                val next=lines.drop(i+1).firstOrNull { !it.startsWith('#') } ?: error("Variant address missing")
                variants+=Variant(resolve(url,next),attributes(line))
            }
            if(line.startsWith("#EXT-X-MEDIA:")) {
                val attrs=attributes(line)
                if(attrs["TYPE"]=="AUDIO" && !attrs["URI"].isNullOrBlank()) audio+=attrs+mapOf("URI" to resolve(url,attrs.getValue("URI")))
            }
            if(line.startsWith("#EXTINF:")) {
                val d=line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: error("Invalid segment duration")
                require(d.isFinite() && d>=0);duration+=d;segments++
                if(firstSegment.isEmpty()) lines.drop(i+1).firstOrNull { !it.startsWith('#') }?.let { firstSegment=resolve(url,it) }
            }
            if(line.startsWith("#EXT-X-KEY:")) {
                val a=attributes(line)
                if(a["METHOD"] !in setOf("NONE","AES-128") || (a["KEYFORMAT"]!=null && a["KEYFORMAT"]!="identity")) protected=true
            }
        }
        return Playlist(variants,audio,duration,segments,lines.contains("#EXT-X-ENDLIST"),protected,firstSegment)
    }
    private fun pHasVideo(formats: JSONArray): Boolean=(0 until formats.length()).any { formats.getJSONObject(it).optString("vcodec")!="none" }
    fun build(url: String,text: String,page: String,title: String,headers: (String)->Map<String,String>,allowAudioOnly: Boolean=false,fetch: (String)->String): JSONObject {
        val formats=JSONArray();var expected=0.0;var segmentCount=0;val visited=mutableSetOf<String>();var requests=0;var unavailable=0
        fun media(target: String,body: String): Playlist {
            val p=parse(target,body)
            check(!p.encrypted) { "Protected HLS encryption is not supported" }
            check(p.ended && p.segments>0 && p.duration>0) { "Playlist is not a complete on-demand video; use stream recording for an ongoing stream" }
            return p
        }
        fun read(target: String): String { check(++requests<=24) { "Playlist inspection limit reached" };return fetch(target) }
        fun add(target: String,p: Playlist,attrs: Map<String,String>,audio: Boolean,separate: Boolean) {
            expected=maxOf(expected,p.duration);segmentCount=maxOf(segmentCount,p.segments)
            val f=JSONObject().put("format_id",if(audio) "audio-${formats.length()}" else "video-${formats.length()}")
                .put("url",target).put("protocol","m3u8_native").put("ext",if(audio) "m4a" else "mp4")
                .put("http_headers",JSONObject(headers(target))).put("_app_first_segment_url",p.firstSegment).put("_app_segment_count",p.segments).put("_app_duration",p.duration)
            val codecs=attrs["CODECS"].orEmpty().split(',').map(String::trim)
            f.put("vcodec",if(audio) "none" else codecs.firstOrNull { it.startsWith("avc") || it.startsWith("hev") || it.startsWith("hvc") || it.startsWith("av01") || it.startsWith("vp") } ?: "unknown")
            f.put("acodec",if(!audio && separate) "none" else codecs.firstOrNull { it.startsWith("mp4a") || it.startsWith("opus") || it.startsWith("ac-3") || it.startsWith("ec-3") } ?: "unknown")
            attrs["RESOLUTION"]?.split('x')?.takeIf { it.size==2 }?.let { r -> r[0].toIntOrNull()?.let { f.put("width",it) };r[1].toIntOrNull()?.let { f.put("height",it) } }
            attrs["BANDWIDTH"]?.toDoubleOrNull()?.let { f.put("tbr",it/1000) }
            attrs["LANGUAGE"]?.let { f.put("language",it) }
            attrs["NAME"]?.let { f.put("format_note",it) }
            (attrs["GROUP-ID"] ?: attrs["AUDIO"])?.let { f.put("_app_audio_group",it) }
            formats.put(f)
        }
        fun visit(target: String,body: String,depth: Int) {
            check(depth<=3 && visited.add(target)) { "Recursive or overly nested playlist" }
            val p=parse(target,body);check(!p.encrypted) { "Protected HLS encryption is not supported" }
            if(p.variants.isEmpty()) { add(target,media(target,body),emptyMap(),false,false);return }
            check(p.variants.size<=16 && p.audio.size<=16) { "Too many playlist renditions; narrow the source" }
            // Keep each declared audio group/language. yt-dlp applies the existing format preset.
            val availableAudio=mutableSetOf<String>()
            for(a in p.audio) {
                val u=a.getValue("URI")
                try { if(visited.add(u)) { add(u,media(u,read(u)),a,true,false);availableAudio+=a["GROUP-ID"].orEmpty() } }
                catch(e: java.util.concurrent.CancellationException) { throw e }
                catch(_: Exception) { unavailable++ }
            }
            for(v in p.variants) {
                if(!visited.add(v.url)) continue
                val separate=p.audio.any { it["GROUP-ID"]==v.attrs["AUDIO"] }
                if(separate && v.attrs["AUDIO"] !in availableAudio) continue
                try {
                    val child=read(v.url);val cp=parse(v.url,child)
                    if(cp.variants.isNotEmpty()) { visited.remove(v.url);visit(v.url,child,depth+1) }
                    else add(v.url,media(v.url,child),v.attrs,false,separate)
                } catch(e: java.util.concurrent.CancellationException) { throw e }
                catch(_: Exception) { unavailable++ }
            }
        }
        visit(url,text,0)
        check(formats.length()>0) { "No verified HLS renditions" }
        val complete=JSONArray()
        for(i in 0 until formats.length()) {
            val f=formats.getJSONObject(i)
            if(kotlin.math.abs(f.optDouble("_app_duration")-expected)<=maxOf(10.0,expected*0.03)) complete.put(f)
        }
        check(complete.length()>0 && (allowAudioOnly || pHasVideo(complete))) { "No full-duration HLS renditions" }
        if(allowAudioOnly) for(i in 0 until complete.length()) complete.getJSONObject(i).put("vcodec","none").put("ext","m4a")
        return JSONObject().put("_app_audio_only",allowAudioOnly).put("id","verified-hls").put("title",title.ifBlank { "Browser selected video" }).put("webpage_url",page)
            .put("formats",complete).put("duration",expected).put("is_live",false).put("_app_expected_duration",expected).put("_app_expected_segments",segmentCount).put("_app_unavailable_renditions",unavailable)
    }
}
