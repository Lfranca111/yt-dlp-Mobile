package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local-only finalization of an otherwise finished finite VOD transfer. Originals survive every failure. */
internal object PostprocessRecovery {
    fun attempt(c: Context,folder: File,metadata: String,mp4: Boolean,stopped: ()->Boolean,log: (String)->Unit): Boolean {
        val info=runCatching { JSONObject(metadata) }.getOrNull() ?: return false
        val expected=info.optDouble("_app_expected_duration",info.optDouble("duration",0.0))
        if(!expected.isFinite() || expected<=0 || info.optBoolean("is_live")) return false
        if(stopped()) throw InterruptedException("Finalization canceled; originals retained")
        log("Local finalization recovery started; expectedSeconds=${expected.toLong()}; networkRequests=0; originals retained; requestedContainer=${if(mp4) "mp4" else "mkv"}")
        try {
            // The normal safeguard previously ran only AFTER yt-dlp's automatic fixup.
            // A fixup failure must also be allowed to reach that normalization path.
            val normalizedMissing=WrappedTsRecovery.prepareCompleted(c,folder,stopped,log)
            check(normalizedMissing==0) { "Normalization found missing media placeholders; partial media retained, not complete" }
            val output=PartialMediaRecovery.buildFinalized(c,folder,false,stopped,if(mp4) "mp4" else "mkv",expected,log)
            if(stopped()) throw InterruptedException("Finalization canceled; originals retained")
            val complete=PostprocessRecoveryPolicy.complete(expected,output.media.seconds,output.media.video,output.media.audio,output.missing)
            log("Local finalization completion gate; expectedSeconds=${expected.toLong()}; savedSeconds=${output.media.seconds.toLong()}; videoTracks=${output.media.video}; audioTracks=${output.media.audio}; missing=${output.missing}; accepted=$complete")
            check(complete) { "Local finalization output did not pass full duration/audio/video checks; originals retained" }
            val sources=PartialDownloadCache.mediaFiles(folder)
            val history=File(folder,PartialDownloadCache.DIRECTORY+"/recovery/finalization-"+UUID.randomUUID()).apply { check(mkdirs()) }
            val target=File(folder,"Recovered-video-${UUID.randomUUID()}.${if(mp4) "mp4" else "mkv"}")
            // A failed stage keeps every original and the validated new copy private.
            sources.forEachIndexed { i,file -> check(file.renameTo(File(history,"$i-${file.name}"))) { "Could not preserve original assembly; recovery output kept privately" } }
            check(output.file.renameTo(target)) { "Could not stage recovered output; source and new copy retained" }
            log("Local finalization recovered complete media; no fragments downloaded again; original assembly archived until final export succeeds")
            return true
        } catch(e: Exception) {
            if(stopped() || e is InsufficientStorage) throw e
            log("Local finalization recovery failed; category=${WebsiteDownloadFailure.describe(e).kind}; exception=${e.javaClass.simpleName}; original media/cache retained; no output reported complete")
            return false
        }
    }
}
