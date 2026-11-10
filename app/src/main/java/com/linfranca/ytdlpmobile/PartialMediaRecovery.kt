package com.linfranca.ytdlpmobile

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.util.concurrent.TimeUnit

/** Reconstructs a COPY from local assembled bytes. No network input or source deletion. */
internal object PartialMediaRecovery {
    data class Media(val seconds: Double,val video: Int,val audio: Int)
    data class Output(val file: File,val media: Media,val missing: Int)
    fun validate(file: File,audioOnly: Boolean=false): Media {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video=formats.count { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            val audio=formats.count { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val trackDuration=formats.mapNotNull { if(it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION).toDouble()/1_000_000 else null }.maxOrNull() ?: 0.0
            val duration=if(trackDuration>0) trackDuration else runCatching {
                val retriever=MediaMetadataRetriever()
                try { retriever.setDataSource(file.absolutePath);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0 }
                finally { retriever.release() }
            }.getOrDefault(0.0)
            check(file.length()>0 && duration>0 && if(audioOnly) audio>0 else video>0) { "Retained media has no verifiable playable ${if(audioOnly) "audio" else "video"}; original files kept" }
            return Media(duration,video,audio)
        } finally { extractor.release() }
    }
    fun build(c: Context,folder: File,audioOnly: Boolean,canceled: ()->Boolean): Output = buildFinalized(c,folder,audioOnly,canceled)
    fun buildFinalized(c: Context,folder: File,audioOnly: Boolean,canceled: ()->Boolean,outputFormat: String="mkv",expectedSeconds: Double=0.0,log: (String)->Unit = {}): Output {
        require(outputFormat in setOf("mkv","mp4"))
        FFmpeg.init(c)
        val sources=PartialDownloadCache.recoverable(folder).sortedByDescending { it.lastModified() }
        check(sources.isNotEmpty()) { "No assembled media bytes are available yet; original files kept" }
        val outputFolder=File(folder,PartialDownloadCache.DIRECTORY+"/exports").apply { mkdirs() }
        val binary=findBinary(c)
        val environment=environment(c,binary,log=log)
        val probeRuntime=runCatching { diagnosticProbeRuntime(c) }.getOrNull()
        var best: Output?=null
        val watch=StorageGuard.Watch(c)
        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(8)
        try {
            for((index,source) in sources.withIndex()) {
                if(canceled()) throw InterruptedException("Partial save canceled")
                if(System.nanoTime()>=deadline) {
                    if(best!=null) break
                    error("Partial recovery time limit reached; original files kept")
                }
                StorageGuard.working(c,source.length())
                log("Local input inspection; assembly=${index+1}; bytes=${source.length()}; probeBytes=${MediaProbePolicy.BYTES}; analyzeMicroseconds=${MediaProbePolicy.ANALYZE_US}; path omitted")
                if(probeRuntime!=null) runCatching {
                    val info=FragmentDiagnosticCore.probe(probeRuntime.first,probeRuntime.second,source,canceled,15_000,true)
                    log("Local input stream parameters; assembly=${index+1}; report=$info")
                }.onFailure { if(it is InterruptedException) throw it;log("Local input stream inspection unavailable; category=${it.javaClass.simpleName}") }
                else log("Local input stream inspection unavailable; bundled FFprobe unavailable; remux still attempted")
                val target=File(outputFolder,"Partial-${System.currentTimeMillis()}-$index.$outputFormat")
                val command=mutableListOf(binary.absolutePath,"-nostdin","-hide_banner","-loglevel","error","-y","-protocol_whitelist","file,pipe","-fflags",if(expectedSeconds>0) "+genpts" else "+genpts+discardcorrupt",*MediaProbePolicy.inputArguments.toTypedArray(),"-i",source.absolutePath)
                if(expectedSeconds>0) command.add(1,"-xerror")
                // Pair available track-only pieces from the SAME assembly/attempt directory.
                val sourceTracks=runCatching { validate(source,audioOnly) }.getOrNull()
                val audio=if(!audioOnly && sourceTracks?.audio==0) sources.firstOrNull { it!=source && it.parentFile==source.parentFile && runCatching { val m=validate(it,true);m.video==0 }.getOrDefault(false) } else null
                if(audio!=null) command.addAll(listOf("-protocol_whitelist","file,pipe")+MediaProbePolicy.inputArguments+listOf("-i",audio.absolutePath,"-map","0:v:0","-map","1:a:0"))
                else command.addAll(if(audioOnly) listOf("-map","0:a:0") else listOf("-map","0:v:0","-map","0:a?"))
                command.addAll(listOf("-c","copy","-avoid_negative_ts","make_zero",target.absolutePath))
                try {
                    run(command,environment,canceled,deadline,log) { watch.poll() }
                    val media=validate(target,audioOnly)
                    if(media.seconds>(best?.media?.seconds ?: 0.0)) {
                        best?.file?.delete();best=Output(target,media,PartialDownloadCache.missing(folder))
                    } else target.delete()
                    if(expectedSeconds>0 && PostprocessRecoveryPolicy.complete(expectedSeconds,media.seconds,media.video,media.audio,PartialDownloadCache.missing(folder))) break
                } catch(e: Exception) {
                    target.delete();log("Local candidate rejected; assembly=${index+1}; stage=remux_or_validation; category=${e.javaClass.simpleName}; originals kept")
                    if(canceled() || e is InsufficientStorage) throw e
                }
            }
            return best ?: error("Local remuxing or playable-track validation failed; see the FFmpeg result and input-inspection diagnostics. Original media and fragments kept.")
        } catch(e: Exception) { best?.file?.delete();throw e }
    }
    fun localFfmpegRuntime(c: Context,log: (String)->Unit = {}): Pair<File,Map<String,String>> {
        FFmpeg.init(c)
        val binary=findBinary(c)
        return binary to environment(c,binary,"wrapped-ffmpeg-${binary.absolutePath.hashCode()}",log)
    }
    /** Probe setup writes only native-library aliases in codeCache, never the media workspace. */
    fun diagnosticProbeRuntime(c: Context): Pair<File,Map<String,String>> {
        FFmpeg.init(c)
        val ffmpeg=findBinary(c)
        val probe=listOf(File(ffmpeg.parentFile,"ffprobe"),File(c.applicationInfo.nativeLibraryDir,"libffprobe.so"))
            .firstOrNull { it.isFile && it.canExecute() } ?: error("Bundled FFprobe is unavailable")
        return probe to environment(c,probe)
    }
    fun selectAudio(c: Context,source: File,target: File,index: Int,canceled: ()->Boolean,log: (String)->Unit) {
        require(index>=0)
        val (binary,env)=localFfmpegRuntime(c,log)
        val command=listOf(binary.absolutePath,"-nostdin","-hide_banner","-loglevel","error","-y","-protocol_whitelist","file,pipe")+
            MediaProbePolicy.inputArguments+listOf("-i",source.absolutePath,"-map","0:v:0?","-map","0:$index","-c","copy","-metadata:s:a:0","language=eng","-disposition:a:0","default",target.absolutePath)
        val watch=StorageGuard.Watch(c)
        run(command,env,canceled,System.nanoTime()+TimeUnit.MINUTES.toNanos(8),log) { watch.poll() }
    }
    private fun findBinary(c: Context): File {
        val packages=sequenceOf(c.filesDir,c.noBackupFilesDir).flatMap { root -> sequenceOf("arm64","arm","x86_64","x86","arm64-v8a","armeabi-v7a","ffmpeg").map { File(root,"youtubedl-android/packages/$it/usr/bin/ffmpeg") } }
        return (packages+sequenceOf(File(c.noBackupFilesDir,"youtubedl-android/ffmpeg/ffmpeg"),File(c.applicationInfo.nativeLibraryDir,"libffmpeg.so"))).firstOrNull { it.isFile && it.canExecute() } ?: error("Bundled FFmpeg executable is unavailable; original files kept")
    }
    @Synchronized
    private fun environment(c: Context,binary: File,aliasName: String="partial-ffmpeg-aliases",log: (String)->Unit = {}): Map<String,String> {
        val native=File(c.applicationInfo.nativeLibraryDir)
        val layout=NativeLibraryLayout.discover(binary,native,listOf(File(c.filesDir,"youtubedl-android/packages"),File(c.noBackupFilesDir,"youtubedl-android/packages")))
        val dirs=layout.directories.toMutableList().apply { add(native.absolutePath);if(binary.parentFile?.name=="bin") add(File(binary.parentFile!!.parentFile!!,"lib").absolutePath) }
        val aliases=File(c.codeCacheDir,aliasName).apply { mkdirs() }
        fun env()=mapOf("LD_LIBRARY_PATH" to (dirs+System.getenv("LD_LIBRARY_PATH").orEmpty()).filter(String::isNotBlank).distinct().joinToString(":"),"PATH" to binary.parentFile!!.absolutePath+":"+System.getenv("PATH").orEmpty())
        repeat(17) {
            val probe=ProcessBuilder(binary.absolutePath,"-version").redirectErrorStream(true).apply { environment().putAll(env()) }.start()
            val text=probe.inputStream.bufferedReader().use { it.readText().takeLast(8192) }
            if(probe.waitFor()==0) { log("Local FFmpeg runtime; version=${MediaProbePolicy.version(text)}; abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}; native paths omitted");return env() }
            val missing=Regex("library \"([^\"]+\\.so(?:\\.[0-9]+)*)\" not found").find(text)?.groupValues?.get(1)
            val candidate=missing?.let(layout::versionedCandidate)
            check(missing!=null && candidate!=null && !missing.contains('/') && missing!=candidate.name) { "FFmpeg native dependencies unavailable; original files kept" }
            candidate.copyTo(File(aliases,missing),overwrite=true);dirs.add(0,aliases.absolutePath)
        }
        error("FFmpeg native startup failed; original files kept")
    }
    private fun run(command: List<String>,env: Map<String,String>,canceled: ()->Boolean,deadline: Long,log: (String)->Unit,poll: ()->Unit) {
        val process=ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        // Retain fixed error markers only, never raw native paths, URLs or output.
        val markers=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val drain=Thread { runCatching { process.inputStream.reader().use { input ->
            val buffer=CharArray(2048);var tail=""
            while(true) { val n=input.read(buffer);if(n<0) break;val text=tail+String(buffer,0,n);markers.addAll(PostprocessRecoveryPolicy.markers(text));tail=text.takeLast(128) }
        } } }.apply { isDaemon=true;start() }
        try {
            log("Local FFmpeg process started; bounded deeper input inspection; raw command omitted")
            while(!process.waitFor(200,TimeUnit.MILLISECONDS)) {
                poll()
                if(canceled()) throw InterruptedException("Partial save canceled")
                check(System.nanoTime()<deadline) { "Partial recovery time limit reached; original files kept" }
            }
            drain.join(1000)
            log("Local finalization FFmpeg result; exitCode=${process.exitValue()}; markers=${markers.sorted().joinToString(",").ifBlank { "none recognized" }}; raw output omitted; originals retained")
            check(process.exitValue()==0) { "FFmpeg could not finalize this retained media; original files kept; exit=${process.exitValue()}; markers=${markers.sorted().joinToString(",")}" }
        } finally { if(process.isAlive) process.destroyForcibly();drain.join(1000) }
    }
}
