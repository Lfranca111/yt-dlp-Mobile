package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Read-only media inspection. Reports use opaque aliases; raw names/metadata/URLs never leave this class. */
internal object FragmentDiagnosticCore {
    data class Item(val file: File,val alias: String,val role: String,val kind: String,val row: JSONObject)
    private fun hex(bytes: ByteArray)=bytes.joinToString("") { "%02x".format(it) }
    fun kind(header: ByteArray): String {
        if(header.size>=564 && (0 until 188).any { offset -> offset+376<header.size && listOf(0,188,376).all { (header[offset+it].toInt() and 255)==71 } }) return "mpegts"
        if(header.size>=8) {
            val box=String(header,4,4,Charsets.US_ASCII)
            if(box in setOf("ftyp","moov")) return "mp4_initialization_or_file"
            if(box in setOf("styp","moof","mdat","sidx")) return "mp4_fragment_requires_initialization"
            if(header.take(4).map { it.toInt() and 255 }==listOf(26,69,223,163)) return "matroska_or_webm"
        }
        if(header.size>=3 && String(header,0,3,Charsets.US_ASCII)=="ID3") return "mpeg_audio_or_aac"
        if(header.size>=2 && (header[0].toInt() and 255)==255 && (header[1].toInt() and 240)==240) return "mpeg_audio_or_aac"
        val text=header.toString(Charsets.UTF_8).trimStart().lowercase()
        return when {
            text.startsWith("<!doctype") || text.startsWith("<html") -> "html_response"
            text.startsWith("#extm3u") -> "playlist_not_media"
            text.startsWith("{") || text.startsWith("[") -> "json_or_text"
            header.isEmpty() -> "empty"
            else -> "unknown"
        }
    }
    fun inventory(folder: File,canceled: ()->Boolean,deadline: Long,clock: ()->Long=System::nanoTime): Pair<List<Item>,Boolean> {
        var hashesComplete=true
        val rows=mutableListOf<Item>()
        folder.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(folder).invariantSeparatorsPath }.forEachIndexed { index,file ->
            if(canceled()) throw InterruptedException("Diagnostic export canceled")
            val relative=file.relativeTo(folder).invariantSeparatorsPath
            val role=when {
                relative.startsWith(".download-cache/objects/") && file.extension=="bin" -> "cached_fragment"
                relative.startsWith(".download-cache/objects/") && file.extension=="json" -> "fragment_receipt"
                relative.startsWith(".download-cache/recovery/") -> "previous_assembly"
                relative.startsWith(".download-cache/exports/") -> "reconstruction_copy"
                file.name.endsWith(".ytdl") -> "downloader_checkpoint"
                file.extension.lowercase() in setOf("mp4","mkv","webm","ts","m4a","mp3","part","ogg","opus") -> "current_assembly_or_partial"
                else -> "private_metadata_not_exported"
            }
            val media=role in setOf("cached_fragment","current_assembly_or_partial","previous_assembly","reconstruction_copy") && !file.name.endsWith(".ytdl") && !file.name.endsWith(".txt") && !file.name.endsWith(".json")
            val alias="file-%05d".format(java.util.Locale.US,index+1)
            val row=JSONObject().put("file",alias).put("role",role).put("bytes",file.length()).put("modifiedEpochMs",file.lastModified())
            var type="not_inspected_private_metadata"
            if(media) {
                try {
                    val header=file.inputStream().use { input -> val buffer=ByteArray(4096);val n=input.read(buffer);if(n>0) buffer.copyOf(n) else byteArrayOf() }
                    type=kind(header)
                    if(clock()<deadline) {
                        val hash=MessageDigest.getInstance("SHA-256")
                        var completed=true
                        file.inputStream().use { input ->
                            val buffer=ByteArray(262144)
                            while(true) {
                                if(canceled()) throw InterruptedException("Diagnostic export canceled")
                                if(clock()>=deadline) { completed=false;break }
                                val n=input.read(buffer);if(n<0) break;hash.update(buffer,0,n)
                            }
                        }
                        if(completed) row.put("sha256",hex(hash.digest())) else { hashesComplete=false;row.put("hashStatus","time_limit") }
                    } else { hashesComplete=false;row.put("hashStatus","time_limit") }
                    if(role=="cached_fragment" && file.nameWithoutExtension.matches(Regex("[a-f0-9]{64}"))) {
                        row.put("cacheKey",file.nameWithoutExtension)
                        val receipt=File(file.parentFile,file.nameWithoutExtension+".json")
                        try {
                            check(receipt.length() in 1..16384)
                            val j=JSONObject(receipt.readText())
                            val expected=j.optString("sha256")
                            row.put("receiptBytes",j.optLong("bytes",-1)).put("receiptHashPresent",expected.matches(Regex("[a-f0-9]{64}")))
                            row.put("receiptSizeMatches",j.optLong("bytes",-1)==file.length())
                            if(row.has("sha256")) row.put("receiptHashMatches",row.optString("sha256")==expected)
                        } catch(e: Exception) { row.put("receiptStatus",e.javaClass.simpleName) }
                    }
                } catch(e: InterruptedException) { throw e }
                catch(e: Exception) { row.put("inspectionError",e.javaClass.simpleName) }
            }
            row.put("kind",type)
            rows.add(Item(file,alias,role,type,row))
        }
        return rows to hashesComplete
    }
    fun samples(items: List<Item>,maximum: Int=24): List<Item> {
        val ordered=items.filter { it.role=="cached_fragment" }.sortedWith(compareBy<Item> { it.file.lastModified() }.thenBy { it.alias })
        if(maximum<=0 || ordered.isEmpty()) return emptyList()
        if(maximum==1) return listOf(ordered.first())
        if(ordered.size<=maximum) return ordered
        return (0 until maximum).map { ordered[it*(ordered.size-1)/(maximum-1)] }.distinctBy { it.alias }
    }
    class ProbeSummary {
        private data class Timing(var packets: Long=0,var first: Double?=null,var last: Double?=null,var minimum: Double?=null,var maximum: Double?=null,var backwards: Long=0,var largeForward: Long=0,var bytes: Long=0)
        private val streams=linkedMapOf<Int,Timing>()
        private val metadata=JSONArray()
        fun line(line: String) {
            val parts=line.split('|')
            val fields=parts.drop(1).mapNotNull { val p=it.indexOf('=');if(p>0) it.substring(0,p) to it.substring(p+1) else null }.toMap()
            when(parts.firstOrNull()) {
                "packet" -> {
                    val index=fields["stream_index"]?.toIntOrNull() ?: return
                    val s=streams.getOrPut(index) { Timing() }
                    s.packets++;s.bytes+=fields["size"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                    val time=fields["pts_time"]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fields["dts_time"]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return
                    if(s.first==null) s.first=time
                    s.last?.let { previous -> if(time<previous-1.0) s.backwards++;if(time>previous+5.0) s.largeForward++ }
                    s.last=time;s.minimum=minOf(s.minimum ?: time,time);s.maximum=maxOf(s.maximum ?: time,time)
                }
                "stream","format" -> {
                    val row=JSONObject().put("section",parts[0])
                    for((key,value) in fields) {
                        if(key in setOf("index","start_time","duration","time_base","nb_frames","sample_rate","channels","width","height","size","bit_rate") && value.matches(Regex("[0-9.eE+:/-]{1,80}"))) row.put(key,value)
                        if(key=="tag:language" && value.matches(Regex("[a-zA-Z_-]{1,32}"))) row.put("language",value)
                        if(key in setOf("codec_name","codec_type","format_name") && value.matches(Regex("[a-zA-Z0-9_,.-]{1,80}"))) row.put(key,value)
                    }
                    metadata.put(row)
                }
            }
        }
        fun json()=JSONObject().put("metadata",metadata).put("packetTiming",JSONArray(streams.map { (index,s) ->
            JSONObject().put("stream",index).put("packets",s.packets).put("packetBytes",s.bytes).put("firstTimestamp",s.first).put("lastTimestamp",s.last).put("minTimestamp",s.minimum).put("maxTimestamp",s.maximum).put("backwardJumpsOverOneSecond",s.backwards).put("forwardJumpsOverFiveSeconds",s.largeForward)
        }))
    }
    fun probe(binary: File,environment: Map<String,String>,file: File,canceled: ()->Boolean,timeoutMs: Long,metadataOnly: Boolean=false): JSONObject {
        val summary=ProbeSummary()
        val result=JSONObject()
        val command=listOf(binary.absolutePath,"-v","error","-protocol_whitelist","file,pipe",*MediaProbePolicy.inputArguments.toTypedArray(),*(if(metadataOnly) emptyList<String>() else listOf("-show_packets")).toTypedArray(),"-show_streams","-show_format","-show_entries","packet=stream_index,pts_time,dts_time,size:stream=index,codec_name,codec_type,start_time,duration,time_base,width,height,sample_rate,channels:stream_tags=language:format=format_name,start_time,duration,size,bit_rate","-of","compact=p=1:nk=0",file.absolutePath)
        val process=ProcessBuilder(command).apply { environment().putAll(environment) }.start()
        // Do not export native stderr: it can contain filenames, URLs or metadata values.
        var errors=0L
        var readError=""
        val out=Thread { try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach(summary::line) } } catch(e: Exception) { readError=e.javaClass.simpleName } }.apply { isDaemon=true;start() }
        val err=Thread { runCatching { process.errorStream.use { input -> val bytes=ByteArray(8192);while(true) { val n=input.read(bytes);if(n<0) break;errors+=n } } } }.apply { isDaemon=true;start() }
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while(!process.waitFor(100,TimeUnit.MILLISECONDS)) {
                if(canceled()) throw InterruptedException("Diagnostic export canceled")
                if(System.nanoTime()>=deadline) { result.put("timedOut",true);break }
            }
            if(process.isAlive) { process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS) }
            out.join(2000);err.join(1000)
            if(out.isAlive) { result.put("readerIncomplete",true);return result }
            result.put("exitCode",if(process.isAlive) -1 else process.exitValue()).put("stderrBytesOmitted",errors)
                .put("summary",summary.json())
            if(readError.isNotBlank()) result.put("readError",readError)
            return result
        } finally { if(process.isAlive) process.destroyForcibly();process.inputStream.close();process.errorStream.close() }
    }
}
