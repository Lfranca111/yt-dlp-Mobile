package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Explicit English choice for this handler only; never guesses from track order or movie metadata. */
internal object H123AudioLanguage {
    fun english(value: String): Boolean {
        val tag=value.trim().lowercase(java.util.Locale.ROOT).replace('_','-')
        return tag in setOf("en","eng","english") || tag.startsWith("en-")
    }
    fun plan(info: JSONObject,log: (String)->Unit = {}) {
        info.put("_app_audio_language","en")
        val formats=info.optJSONArray("formats") ?: return
        val entries=(0 until formats.length()).map { formats.getJSONObject(it) }
        val audio=entries.filter { it.optString("vcodec")=="none" }
        val selected=audio.filter { english(it.optString("language")) || english(it.optString("format_note")) }
        val muxed=entries.any { it.optString("vcodec")!="none" && it.optString("acodec")!="none" }
        check(selected.isNotEmpty() || audio.isEmpty() || muxed) { "English audio is not available in the verified playlist; no other language substituted" }
        if(selected.isNotEmpty()) {
            val groups=selected.map { it.optString("_app_audio_group") }.filter(String::isNotBlank).toSet()
            val kept=entries.filter { f ->
                if(f.optString("vcodec")=="none") f in selected
                else f.optString("acodec")!="none" || f.optString("_app_audio_group").let { it.isBlank() || it in groups }
            }
            check(kept.any { it.optString("vcodec")!="none" }) { "No matching video rendition for English audio" }
            info.put("formats",JSONArray(kept)).put("_app_english_rendition_verified",true)
        }
        log("h123 audio selection; requested=English; verifiedEnglishRenditions=${selected.size}; declaredAudioRenditions=${audio.size}; embedded audio checked after transfer; URLs omitted")
    }
    data class Track(val index: Int,val language: String)
    fun tracks(report: JSONObject): List<Track> {
        val metadata=report.optJSONObject("summary")?.optJSONArray("metadata") ?: return emptyList()
        return (0 until metadata.length()).map { metadata.getJSONObject(it) }
            .filter { it.optString("section")=="stream" && it.optString("codec_type")=="audio" && it.optInt("index",-1)>=0 }
            .map { Track(it.getInt("index"),it.optString("language")) }.groupBy { it.index }.values.map { copies -> copies.firstOrNull { it.language.isNotBlank() } ?: copies.first() }
    }
    fun chosen(tracks: List<Track>,verifiedRendition: Boolean): Int {
        tracks.firstOrNull { english(it.language) }?.let { return it.index }
        if(tracks.size==1 && verifiedRendition && tracks[0].language.isBlank()) return tracks[0].index
        error("English audio could not be verified in the saved media; no other language substituted. Original media and fragments kept.")
    }
    const val LANGUAGE_WARNING = "Saved with audio. English could not be verified; the track may still be English."
    private fun unknown(tag: String) = tag.trim().lowercase(java.util.Locale.ROOT) in setOf("", "und", "undetermined", "unknown", "mul", "zxx")

    fun finalize(c: Context,folder: File,metadata: String,canceled: ()->Boolean,log: (String)->Unit): Boolean {
        val selection=runCatching { JSONObject(metadata) }.getOrDefault(JSONObject())
        var languageUnverified=false
        val runtime=runCatching { PartialMediaRecovery.diagnosticProbeRuntime(c) }.getOrNull()
        val files=PartialDownloadCache.mediaFiles(folder).filter { !it.name.endsWith(".part") }
        check(files.isNotEmpty()) { "No saved media is available for English audio verification" }
        for(source in files) {
            if(canceled()) throw InterruptedException("English audio selection canceled")
            val report=runtime?.let {
                runCatching { FragmentDiagnosticCore.probe(it.first,it.second,source,canceled,20_000,true) }.getOrNull()
            }
            if(canceled()) throw InterruptedException("English audio selection canceled")
            val probeOk=report!=null && !report.optBoolean("timedOut") && report.optInt("exitCode",-1)==0
            val audio=if(probeOk) tracks(checkNotNull(report)) else emptyList()
            if(audio.isEmpty()) {
                val extractor=android.media.MediaExtractor()
                val embedded=try {
                    extractor.setDataSource(source.absolutePath)
                    (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                        .filter { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
                        .map { it.getString(android.media.MediaFormat.KEY_LANGUAGE).orEmpty() }
                } finally { extractor.release() }
                check(embedded.isNotEmpty()) { "Saved media has no audio; original media and fragments kept" }
                check(embedded.any { english(it) || unknown(it) }) { "Saved audio is tagged as a different language; original media and fragments kept" }
                languageUnverified=true
                log("h123 saved audio verification; source=Android track inspection; audioTracks=${embedded.size}; EnglishVerified=false; stream selection unavailable; preserving original tracks")
                continue
            }
            val selected=audio.firstOrNull { english(it.language) }?.index
            if(selected==null) {
                check(audio.any { unknown(it.language) }) { "Saved audio is tagged as a different language; original media and fragments kept" }
                languageUnverified=true
                log("h123 saved audio verification; audioTracks=${audio.size}; EnglishVerified=false; playlistEnglishEvidence=${selection.optBoolean("_app_english_rendition_verified")}; language metadata inconclusive; preserving original tracks")
                continue
            }
            log("h123 saved audio verification; audioTracks=${audio.size}; EnglishVerified=true; selectedStreamIndex=$selected; embeddedMetadataValuesOmitted=true")
            if(audio.size==1) continue
            val previous=runCatching { PartialMediaRecovery.validate(source).seconds }.getOrDefault(0.0)
            val staging=File(folder,"${PartialDownloadCache.DIRECTORY}/exports/English-${UUID.randomUUID()}.${source.extension}").apply { parentFile!!.mkdirs() }
            try {
                StorageGuard.working(c,source.length())
                PartialMediaRecovery.selectAudio(c,source,staging,selected,canceled,log)
                val result=PartialMediaRecovery.validate(staging)
                check(result.video>0 && result.audio==1 && (previous<=0 || MediaCompletion.acceptable(previous,result.seconds))) { "English audio remux failed playable-track/duration validation; original retained" }
                val probe=checkNotNull(runtime)
                val verified=FragmentDiagnosticCore.probe(probe.first,probe.second,staging,canceled,20_000,true)
                val outputLanguage=tracks(verified).singleOrNull()?.language.orEmpty()
                check(unknown(outputLanguage) || english(outputLanguage)) { "Remuxed audio is tagged as a different language; original retained" }
                if(verified.optBoolean("timedOut") || verified.optInt("exitCode",-1)!=0 || !english(outputLanguage)) languageUnverified=true
                if(canceled()) throw InterruptedException("English audio selection canceled")
                val backup=File(folder,"${PartialDownloadCache.DIRECTORY}/recovery/audio-original-${UUID.randomUUID()}.${source.extension}").apply { parentFile!!.mkdirs() }
                check(source.renameTo(backup)) { "Could not retain original before English audio selection" }
                if(!staging.renameTo(source)) { check(backup.renameTo(source)) { "Original remains in recovery cache; output replacement failed" };error("English output replacement failed; original restored") }
                log("h123 English audio selected; video copied without re-encoding; one English audio track; original multilingual assembly retained until normal successful-export cleanup")
            } finally { staging.delete() }
        }
        return languageUnverified
    }
}
