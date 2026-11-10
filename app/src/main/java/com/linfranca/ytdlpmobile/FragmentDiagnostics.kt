package com.linfranca.ytdlpmobile

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes only a new diagnostic ZIP to Downloads; NEVER edits, renames, remuxes or deletes retained media. */
internal object FragmentDiagnostics {
    fun export(c: Context,folder: File,canceled: ()->Boolean,progress: (String)->Unit): Uri {
        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(8)
        val report=JSONObject().put("schema",1).put("appVersion",BuildConfig.VERSION_NAME).put("androidApi",android.os.Build.VERSION.SDK_INT)
            .put("createdUtc",java.time.Instant.now().toString()).put("readOnly",true)
            .put("probeBytes",MediaProbePolicy.BYTES).put("analyzeMicroseconds",MediaProbePolicy.ANALYZE_US)
            .put("rawMediaIncluded",false).put("privateValuesIncluded",false)
            .put("fragmentOrderEvidence","Filesystem modification times only; v1.0.34 receipts do not store playlist ordinals. Do not assume this proves playlist order.")
            .put("timingCaution","Backward PTS may include B-frame reordering; counts use a one-second threshold. Per-fragment ISO-BMFF media can require a separate initialization fragment. Probe failure alone does not establish corruption.")
        progress("Reading retained file inventory and checksums…")
        val (items,hashesComplete)=FragmentDiagnosticCore.inventory(folder,canceled,deadline)
        report.put("hashesComplete",hashesComplete).put("inventory",JSONArray(items.map { it.row }))
        report.put("countsByRole",JSONObject().apply { items.groupingBy { it.role }.eachCount().forEach { (name,count) -> put(name,count) } })
        report.put("bytesByRole",JSONObject().apply { items.groupBy { it.role }.forEach { (name,group) -> put(name,group.sumOf { it.row.optLong("bytes") }) } })
        report.put("identicalContentGroups",JSONArray(items.filter { it.row.has("sha256") }.groupBy { it.row.optString("sha256") }.filterValues { it.size>1 }.values.take(100).map { group ->
            JSONObject().put("count",group.size).put("files",JSONArray(group.take(20).map { it.alias })).put("bytesEach",group.first().row.optLong("bytes"))
        }))
        report.put("cacheTracks",JSONArray(File(folder,".download-cache").listFiles().orEmpty().filter { it.name.startsWith("track-") && it.extension=="json" }.map { file ->
            val row=JSONObject()
            runCatching {
                check(file.length() in 1..1_000_000)
                val j=JSONObject(file.readText())
                for(key in listOf("total","reused")) row.put(key,j.optInt(key,-1))
                row.put("complete",j.optBoolean("complete"))
                val missing=j.optJSONArray("missing") ?: JSONArray()
                row.put("missing",JSONArray((0 until minOf(10000,missing.length())).mapNotNull { n -> missing.optInt(n,-1).takeIf { it>=0 } }))
            }.onFailure { row.put("error",it.javaClass.simpleName) };row
        }))
        // Only explicit numeric fields are copied from private info-json. No generic JSON dumps.
        val selection=File(folder,"browser-selection.json")
        if(selection.isFile) runCatching {
            check(selection.length() in 1..8_000_000)
            val j=JSONObject(selection.readText())
            val row=JSONObject()
            for(key in listOf("duration","_app_expected_duration","_app_expected_segments")) {
                val number=j.optDouble(key,Double.NaN);if(number.isFinite()) row.put(key,number)
            }
            row.put("audioOnly",j.optBoolean("_app_audio_only")).put("unavailableRenditions",j.optInt("_app_unavailable_renditions",0))
            row.put("formatsCount",j.optJSONArray("formats")?.length() ?: 0).put("requestedFormatsCount",j.optJSONArray("requested_formats")?.length() ?: 0)
            val formats=j.optJSONArray("formats") ?: JSONArray()
            row.put("formats",JSONArray((0 until minOf(64,formats.length())).mapNotNull { n ->
                val f=formats.optJSONObject(n) ?: return@mapNotNull null
                JSONObject().apply {
                    for(key in listOf("protocol","ext","vcodec","acodec")) {
                        val value=f.optString(key);if(value.matches(Regex("[a-zA-Z0-9_.+-]{1,80}"))) put(key,value)
                    }
                    for(key in listOf("width","height","fps","duration","_app_duration","_app_segment_count")) {
                        val value=f.optDouble(key,Double.NaN);if(value.isFinite()) put(key,value)
                    }
                    put("explicitFragments",f.optJSONArray("fragments")?.length() ?: 0)
                    val manifest=f.optString("hls_media_playlist_data")
                    if(manifest.isNotBlank()) {
                        put("manifestHasEncryption",manifest.contains("#EXT-X-KEY:") && !manifest.lines().filter { it.startsWith("#EXT-X-KEY:") }.all { it.contains("METHOD=NONE") })
                        put("manifestHasInitialization",manifest.contains("#EXT-X-MAP:"))
                        put("manifestHasEndList",manifest.contains("#EXT-X-ENDLIST"))
                    }
                }
            }))
            report.put("savedSelectionSummary",row)
        }.onFailure { report.put("selectionInspectionError",it.javaClass.simpleName) }
        val assemblies=items.filter { it.role in setOf("current_assembly_or_partial","previous_assembly","reconstruction_copy") && it.kind !in nonMediaKinds }
            .sortedWith(compareBy<FragmentDiagnosticCore.Item> { if(it.role=="current_assembly_or_partial") 0 else 1 }.thenByDescending { it.file.length() }).take(4)
        val probes=JSONArray()
        val runtime=runCatching { PartialMediaRecovery.diagnosticProbeRuntime(c) }.getOrElse { report.put("nativeProbeUnavailable",it.javaClass.simpleName);null }
        val targets=assemblies+FragmentDiagnosticCore.samples(items).filter { it.kind !in nonMediaKinds }
        targets.forEachIndexed { index,item ->
            if(canceled()) throw InterruptedException("Diagnostic export canceled")
            val row=JSONObject().put("file",item.alias).put("role",item.role).put("kind",item.kind)
            progress("Inspecting local media ${index+1}/${targets.size}…")
            if(item in assemblies) row.put("android",androidProbe(item.file))
            val remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
            if(runtime!=null && remaining>500) {
                val limit=minOf(remaining,if(item in assemblies) 75_000L else 8_000L)
                runCatching { FragmentDiagnosticCore.probe(runtime.first,runtime.second,item.file,canceled,limit) }
                    .onSuccess { row.put("ffprobe",it) }.onFailure { if(it is InterruptedException) throw it;row.put("probeError",it.javaClass.simpleName) }
            } else row.put("probeStatus",if(runtime==null) "runtime_unavailable" else "time_limit")
            probes.put(row)
        }
        report.put("probes",probes).put("probeSelection","Up to four assemblies, plus up to 24 fragments sampled evenly across filesystem modification-time order; not every fragment is decoded.")
            .put("timeLimitReached",System.nanoTime()>=deadline)
        if(canceled()) throw InterruptedException("Diagnostic export canceled")
        val payload=report.toString(2).toByteArray(Charsets.UTF_8)
        StorageGuard.destination(c,payload.size.toLong()+65536)
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,"fragment-diagnostics-${System.currentTimeMillis()}.zip")
            put(MediaStore.Downloads.MIME_TYPE,"application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/yt-dlp Mobile/Diagnostics")
            put(MediaStore.Downloads.IS_PENDING,1)
        }
        val uri=c.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values) ?: error("Android could not create the diagnostic ZIP")
        try {
            c.contentResolver.openOutputStream(uri,"w")!!.use { output -> ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("README.txt"));zip.write(README.toByteArray());zip.closeEntry()
                zip.putNextEntry(ZipEntry("report.json"));zip.write(payload);zip.closeEntry()
            } }
            if(canceled()) throw InterruptedException("Diagnostic export canceled")
            check(c.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)>0) { "Android could not publish the diagnostic ZIP" }
            return uri
        } catch(e: Exception) { runCatching { c.contentResolver.delete(uri,null,null) };throw e }
    }
    private val nonMediaKinds=setOf("html_response","playlist_not_media","json_or_text","empty","not_inspected_private_metadata")
    private fun androidProbe(file: File): JSONObject {
        val row=JSONObject()
        val r=MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs=duration?.toLongOrNull()
            if(durationMs!=null) row.put("durationMs",durationMs)
            else row.put("durationStatus",if(duration==null) "not_reported" else "not_numeric")
            row.put("hasVideo",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)?.takeIf { it in setOf("yes","no") } ?: "not_reported")
            row.put("hasAudio",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.takeIf { it in setOf("yes","no") } ?: "not_reported")
        } catch(e: Exception) { row.put("metadataReaderError",e.javaClass.simpleName) } finally { runCatching { r.release() } }
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            row.put("tracks",JSONArray((0 until extractor.trackCount).map { n ->
                val format=extractor.getTrackFormat(n);val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
                JSONObject().put("mime",if(mime.matches(Regex("[a-zA-Z0-9.+/-]{1,100}"))) mime else "unrecognized")
                    .apply { if(format.containsKey(MediaFormat.KEY_DURATION)) put("durationUs",format.getLong(MediaFormat.KEY_DURATION)) }
            }))
        } catch(e: Exception) { row.put("trackReaderError",e.javaClass.simpleName) } finally { runCatching { extractor.release() } }
        return row
    }
    private const val README="""Fragment diagnostics: report.json contains a file inventory, SHA-256 checks, cache receipt comparisons, Android metadata results and selected local FFprobe packet/track summaries.
No video data, cookies, original filenames, webpage/stream URLs, raw logs or raw private metadata are included. Retained media was not edited, renamed, remuxed, retried, or deleted.
A time limit may leave checksums or probes incomplete; the JSON explicitly marks coverage. Samples represent filesystem modification-time order, not proven playlist order. Fragment timestamps may reset, and some MP4 fragments need a separate initialization file. Native stderr is counted but omitted because it can contain sensitive values.
Upload this ZIP with the task's relevant log excerpt for investigation. Keep the app installed and retain its recovery files; this report is not a backup of the media itself.
"""
}
