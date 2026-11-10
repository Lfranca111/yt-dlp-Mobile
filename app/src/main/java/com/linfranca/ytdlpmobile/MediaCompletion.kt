package com.linfranca.ytdlpmobile

import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File

/** A playable header alone is not proof that an on-demand download is complete. */
internal object MediaCompletion {
    fun acceptable(expected: Double,actual: Double): Boolean = expected>0 && actual>0 && kotlin.math.abs(actual-expected)<=3.0
    fun verify(folder: File,metadata: String,audioOnly: Boolean=false,log: (String)->Unit) {
        val j=runCatching { JSONObject(metadata) }.getOrNull() ?: return
        val expected=j.optDouble("_app_expected_duration",j.optDouble("duration",0.0))
        if(!expected.isFinite() || expected<=0 || j.optBoolean("is_live")) return
        val files=folder.walkTopDown().filter { it.isFile && !PartialDownloadCache.privateFile(it) && it.extension.lowercase() in setOf("mp4","m4v","mkv","webm","m4a","mp3","ts","ogg","opus") && !it.name.endsWith(".part") }.toList()
        val durations=files.mapNotNull { file ->
            runCatching { val r=MediaMetadataRetriever();try { r.setDataSource(file.absolutePath);if(!audioOnly && r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)!="yes") null else r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0 } finally { r.release() } }.getOrDefault(0.0)
        }
        val actual=durations.minOrNull() ?: 0.0
        log("Completion check; expectedSeconds=${"%.3f".format(java.util.Locale.US,expected)}; savedSeconds=${"%.3f".format(java.util.Locale.US,actual)}; expectedSegments=${j.optInt("_app_expected_segments",0)}; URLs omitted")
        check(acceptable(expected,actual)) { if(actual<=0) "Saved duration could not be verified; workspace retained, not reported complete" else "Incomplete video: saved ${actual.toInt()} seconds of approximately ${expected.toInt()} seconds; workspace retained for recovery" }
    }
}
