package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Makes a NEW playable copy from the retained assembly's original fragment order. No website requests. */
internal object WrappedTsRecovery {
    data class Result(val file: File,val media: PartialMediaRecovery.Media,val stats: PngTsNormalizer.Stats,val expected: Double,val partial: Boolean)
    fun build(c: Context,folder: File,canceled: ()->Boolean,log: (String)->Unit): Result {
        val source=PartialDownloadCache.recoverable(folder).filter(PngTsNormalizer::looksWrapped).maxByOrNull { it.length() }
            ?: error("No image-prefixed transport stream was found in the retained assemblies. Files kept; export fragment diagnostics for investigation.")
        return convert(c,folder,source,canceled,log)
    }
    private fun convert(c: Context,folder: File,source: File,canceled: ()->Boolean,log: (String)->Unit): Result {
        StorageGuard.working(c,source.length())
        val destination=File(folder,PartialDownloadCache.DIRECTORY+"/wrapped-recovery/"+UUID.randomUUID()).apply { check(mkdirs()) }
        val output=File(destination,"recovered.mp4")
        val runtime=PartialMediaRecovery.localFfmpegRuntime(c,log)
        log("Local TS recovery started; verified transport packets beneath an image header; originals retained; no network request")
        val command=listOf(runtime.first.absolutePath,"-nostdin","-hide_banner","-loglevel","error","-y","-protocol_whitelist","pipe,file","-fflags","+genpts+discardcorrupt","-f","mpegts",*MediaProbePolicy.inputArguments.toTypedArray(),"-i","pipe:0","-map","0:v:0","-map","0:a?","-c","copy","-avoid_negative_ts","make_zero","-movflags","+faststart",output.absolutePath)
        val stats=AtomicReference<PngTsNormalizer.Stats?>()
        val producerError=AtomicReference<Throwable?>()
        var process: Process?=null
        try {
            process=ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(runtime.second) }.start()
            val native=process
            val producer=Thread {
                try {
                    source.inputStream().buffered(262144).use { input -> native.outputStream.buffered(262144).use { target ->
                        stats.set(PngTsNormalizer.copy(input,target,canceled) { bytes -> log("Normalizing retained transport data: ${(bytes*100/maxOf(1L,source.length())).coerceIn(0,100)}%; originals kept") })
                    } }
                } catch(e: Throwable) { producerError.set(e);runCatching { native.outputStream.close() } }
            }.apply { isDaemon=true;start() }
            val drain=Thread { runCatching { native.inputStream.use { input -> val bytes=ByteArray(8192);while(input.read(bytes)>=0) {} } } }.apply { isDaemon=true;start() }
            val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(8)
            val watch=StorageGuard.Watch(c)
            try {
                while(!native.waitFor(200,TimeUnit.MILLISECONDS)) {
                    if(canceled()) throw InterruptedException("Local recovery canceled; original media kept")
                    check(System.nanoTime()<deadline) { "Local recovery timed out; original media kept" }
                    watch.poll()
                }
                producer.join(2000);drain.join(1000)
                producerError.get()?.let {
                    val failure=it as? PngTsNormalizer.Failure
                    if(failure!=null) {
                        File(folder,PartialDownloadCache.DIRECTORY+"/recovery-failure.json").writeText(JSONObject()
                            .put("schema",1).put("stage","transport_normalization").put("reason",failure.reason)
                            .put("inputByteOffset",failure.offset).put("transportPackets",failure.packetsSeen)
                            .put("imageWrappers",failure.wrappersSeen).put("bufferedBytes",failure.bufferedBytes)
                            .put("assemblyBytes",source.length()).put("assemblyModified",source.lastModified()).toString(2))
                        throw IllegalStateException("Transport normalization failed: ${failure.message}; original media kept",it)
                    }
                    throw IllegalStateException("Transport normalization failed; cause=${it.javaClass.simpleName}; original media kept",it)
                }
                check(!producer.isAlive && native.exitValue()==0) { "FFmpeg could not finalize the transport stream; original media kept" }
            } finally {
                if(native.isAlive) native.destroyForcibly()
                runCatching { native.outputStream.close() };producer.join(2000);drain.join(1000)
            }
            val normalized=stats.get() ?: error("Transport normalization did not finish; originals kept")
            val media=PartialMediaRecovery.validate(output)
            val expected=runCatching {
                val info=File(folder,"browser-selection.json");if(info.length()>8_000_000) 0.0 else JSONObject(info.readText()).optDouble("_app_expected_duration",0.0)
            }.getOrDefault(0.0).takeIf { it.isFinite() && it>0 } ?: 0.0
            val partial=normalized.emptyWrappers>0 || media.audio==0 || !MediaCompletion.acceptable(expected,media.seconds)
            log("Local TS recovery completed; wrappers=${normalized.wrappers}; image-only placeholders=${normalized.emptyWrappers}; transportPackets=${normalized.packets}; checkedPngPayloads=${normalized.chunkPayloads}; payloadAlignmentBytes=${normalized.payloadAlignmentBytes} (preserved for FFmpeg demuxing); removedWrapperBytes=${normalized.removedBytes}; savedSeconds=${media.seconds.toInt()}; videoTracks=${media.video}; audioTracks=${media.audio}; expectedSeconds=${expected.toInt()}; partial=$partial")
            File(destination,"recovery-report.json").writeText(JSONObject().put("imageWrappers",normalized.wrappers).put("imageOnlyPlaceholders",normalized.emptyWrappers).put("transportPackets",normalized.packets).put("inputBytes",normalized.inputBytes).put("outputTransportBytes",normalized.outputBytes).put("removedWrapperBytes",normalized.removedBytes).put("checkedPngPayloads",normalized.chunkPayloads).put("payloadAlignmentBytes",normalized.payloadAlignmentBytes).put("savedSeconds",media.seconds).put("videoTracks",media.video).put("audioTracks",media.audio).put("expectedSeconds",expected).put("partial",partial).put("originalsKept",true).toString(2))
            return Result(output,media,normalized,expected,partial)
        } catch(e: Exception) {
            if(process?.isAlive==true) process.destroyForcibly()
            destination.deleteRecursively() // Only the NEW staging directory is removed.
            throw e
        }
    }
    /** Final safeguard for normal non-browser downloads which bypassed the native HLS fragment decoder. */
    fun prepareCompleted(c: Context,folder: File,canceled: ()->Boolean,log: (String)->Unit): Int {
        var missing=0
        val sources=PartialDownloadCache.mediaFiles(folder).filter(PngTsNormalizer::looksWrapped)
        for(source in sources) {
            val result=convert(c,folder,source,canceled,log)
            check(result.media.audio>0) { "Recovered transport video has no audio track; files retained for local review, not reported complete" }
            val history=File(folder,PartialDownloadCache.DIRECTORY+"/recovery/"+UUID.randomUUID()).apply { check(mkdirs()) }
            val target=File(folder,source.nameWithoutExtension.take(160)+"-recovered-"+UUID.randomUUID().toString().take(8)+".mp4")
            check(source.renameTo(File(history,source.name))) { "Could not preserve the original assembly; new copy retained privately" }
            check(result.file.renameTo(target)) { "Could not stage the recovered output; original assembly retained" }
            missing+=result.stats.emptyWrappers
            // Audio/length validation remains with the existing completion gate.
        }
        return missing
    }
}
