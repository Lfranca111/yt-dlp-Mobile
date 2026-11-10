package com.linfranca.ytdlpmobile

import java.net.URI

/** Keep a rendition with its declared audio group; verify fresh live sequences on both tracks. */
internal object YouTubeLiveHls {
    data class Plan(val video: String,val audio: String="",val height: Int=0,val bandwidth: Long=0)
    private fun attr(line: String,name: String)=NativeMedia.attributes(arrayOf(line),name).firstOrNull()
    private fun resolve(base: String,value: String?)=value?.let { runCatching { URI(base).resolve(it).toString() }.getOrNull() }
    private fun hasAudio(codecs: String)=listOf("mp4a","aac","opus","vorbis","ac-3","ec-3").any { codecs.contains(it,true) }
    private fun protected(body: String)=body.contains("ContentProtection",true) || body.lineSequence().any { line ->
        line.startsWith("#EXT-X-KEY:") && (attr(line,"METHOD").orEmpty().startsWith("SAMPLE-AES") ||
            attr(line,"KEYFORMAT")?.let { it!="identity" }==true)
    }
    fun select(master: String,body: String): Plan {
        check(body.trimStart().startsWith("#EXTM3U")) { "YouTube manifest did not contain an HLS playlist" }
        check(!protected(body)) { "YouTube playlist reports protected playback; no recording started" }
        val lines=body.lineSequence().map(String::trim).toList()
        check(lines.size<=10000) { "YouTube master playlist exceeds inspection limit" }
        val audio=lines.filter { it.startsWith("#EXT-X-MEDIA:") && attr(it,"TYPE")=="AUDIO" }
        val plans=lines.indices.mapNotNull { n ->
            val line=lines[n]
            if(!line.startsWith("#EXT-X-STREAM-INF:")) return@mapNotNull null
            val child=lines.drop(n+1).firstOrNull { it.isNotBlank() }?.takeIf { !it.startsWith('#') }
            val video=resolve(master,child)?.takeIf { YouTubeLivePolicy.manifest(it) } ?: return@mapNotNull null
            val codecs=attr(line,"CODECS").orEmpty()
            val group=attr(line,"AUDIO")
            val matching=audio.filter { attr(it,"GROUP-ID")==group }
            if(group!=null && matching.isEmpty()) return@mapNotNull null
            val chosen=matching.sortedWith(compareByDescending<String> { attr(it,"DEFAULT")=="YES" }
                .thenByDescending { attr(it,"AUTOSELECT")=="YES" }).firstOrNull()
            val audioValue=chosen?.let { attr(it,"URI") }
            val audioUrl=resolve(master,audioValue)
            if(audioValue!=null && (audioUrl==null || !YouTubeLivePolicy.manifest(audioUrl))) return@mapNotNull null
            if(audioUrl==null && !hasAudio(codecs)) return@mapNotNull null
            val height=attr(line,"RESOLUTION")?.substringAfter('x')?.toIntOrNull()?.coerceIn(0,10000) ?: 0
            Plan(video,audioUrl.orEmpty(),height,attr(line,"BANDWIDTH")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
        }
        return plans.maxWithOrNull(compareBy<Plan> { it.height }.thenBy { it.bandwidth })
            ?: error("YouTube master has no video rendition with verified audio; no silent recording started")
    }
    fun verify(master: String,read: (String)->String,waitSeconds: (Long)->Unit): Plan {
        check(YouTubeLivePolicy.manifest(master)) { "YouTube manifest address is invalid or expired" }
        val plan=select(master,read(master))
        val first=read(plan.video)
        val audioFirst=if(plan.audio.isNotBlank()) read(plan.audio) else ""
        check(!protected(first) && !protected(audioFirst)) { "YouTube tracks report protected playback; no recording started" }
        check(!CustomLivePolicy.ended(first) && (audioFirst.isBlank() || !CustomLivePolicy.ended(audioFirst))) {
            "YouTube playlist is completed/VOD; live playback was not verified"
        }
        val seconds=Regex("""(?m)^#EXT-X-TARGETDURATION:(\d+)""").find(first)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(2,8) ?: 3L
        repeat(2) {
            waitSeconds(seconds)
            val second=read(plan.video)
            val audioSecond=if(plan.audio.isNotBlank()) read(plan.audio) else ""
            check(!protected(second) && !protected(audioSecond)) { "YouTube tracks report protected playback; no recording started" }
            if(CustomLivePolicy.manifestLive(first,second,false) &&
                (plan.audio.isBlank() || CustomLivePolicy.manifestLive(audioFirst,audioSecond,false))) return plan
        }
        error("YouTube live video/audio playlists did not advance; live state unknown")
    }
}
