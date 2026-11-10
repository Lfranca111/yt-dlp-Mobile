package com.linfranca.ytdlpmobile

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, read-only media sampling. Never includes raw receipts, logs, URLs or original names. */
internal object FragmentSampleCore {
    const val MAX_BYTES=12L*1024*1024
    private const val WHOLE_LIMIT=2L*1024*1024
    data class Sample(val file: File,val offset: Long,val length: Long,val reason: String,val role: String)
    fun failure(folder: File): JSONObject? = runCatching {
        val f=File(folder,".download-cache/recovery-failure.json")
        check(f.length() in 1..8192)
        val source=JSONObject(f.readText());val safe=JSONObject()
        for(k in listOf("inputByteOffset","transportPackets","imageWrappers","bufferedBytes","assemblyBytes","assemblyModified")) {
            val v=source.optLong(k,-1);if(v>=0) safe.put(k,v)
        }
        val reason=source.optString("reason")
        if(reason in setOf("Unrecognized bytes at a transport-packet boundary","Image wrapper has no verified transport-stream payload","No usable transport stream was recovered","Truncated PNG chunk or transport payload","Expected the next PNG container after IEND","Invalid PNG chunk length","Invalid PNG chunk type","PNG IHDR is missing or invalid","Invalid PNG IEND length","PNG transport payload is too short to verify","PNG tsDa payload lacks verified transport sync","PNG chunk checksum mismatch")) safe.put("reason",reason)
        safe.put("stage","transport_normalization")
    }.getOrNull()
    fun plan(folder: File): List<Sample> {
        val result=mutableListOf<Sample>();var bytes=0L
        val root=folder.canonicalFile
        fun add(file: File,offset: Long,length: Long,reason: String,role: String) {
            if(!file.isFile || !file.canonicalFile.toPath().startsWith(root.toPath())) return
            val count=minOf(length,file.length()-offset,MAX_BYTES-bytes-(if(role=="cached_fragment") 262144 else 0))
            if(offset<0 || count<=0 || result.any { it.file==file && it.offset==offset && it.length==count }) return
            result+=Sample(file,offset,count,reason,role);bytes+=count
        }
        val fragments=File(folder,".download-cache/objects").listFiles().orEmpty()
            .filter { it.isFile && it.extension=="bin" }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        val retained=(folder.listFiles().orEmpty().asSequence()+File(folder,".download-cache/recovery").walkTopDown())
            .filter { it.isFile && it.length()>0 && (it.extension.lowercase() in setOf("mp4","ts","mkv","webm") || it.name.endsWith(".part")) }
        val info=failure(folder)
        val assemblies=retained.filter { it.canonicalFile.toPath().startsWith(root.toPath()) }.toList()
        val matched=if(info!=null) assemblies.firstOrNull { it.length()==info.optLong("assemblyBytes",-1) && it.lastModified()==info.optLong("assemblyModified",-1) } else null
        // Ordinary TS needs its beginning too; it must not be excluded merely because it has no PNG prefix.
        val assembly=matched ?: assemblies.maxByOrNull { it.length() }
        if(assembly!=null) {
            if(matched!=null && info!=null) {
                val offset=info.optLong("inputByteOffset",-1)
                if(offset in 0 until assembly.length()) add(assembly,maxOf(0,offset-32768),65536,"around_normalization_failure","ordered_assembly_excerpt")
            }
            add(assembly,0,4L*1024*1024,"assembly_beginning","ordered_assembly_excerpt")
            add(assembly,maxOf(0,assembly.length()/2-32768),65536,"assembly_middle","ordered_assembly_excerpt")
            add(assembly,maxOf(0,assembly.length()-65536),65536,"assembly_end","ordered_assembly_excerpt")
        }
        data class Ordinal(val file: File,val track: String,val index: Int)
        val ordinals=fragments.mapNotNull { file -> runCatching {
            val receipt=File(file.parentFile,file.nameWithoutExtension+".json");check(receipt.length() in 1..8192)
            val json=JSONObject(receipt.readText());val index=json.optInt("fragment_index",-1);check(index>=0)
            val track=json.optString("track_key").takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: "unverified"
            Ordinal(file,track,index)
        }.getOrNull() }
        val selected=linkedMapOf<File,String>()
        // Receipts claim playlist ordinals; file completion times do not. Cover the first pieces of each track.
        ordinals.groupBy { it.track }.entries.sortedBy { it.key }.take(3).forEach { (_,track) ->
            track.sortedWith(compareBy<Ordinal> { it.index }.thenBy { it.file.name }).take(3).forEach { selected[it.file]="earliest_receipt_ordinal" }
        }
        if(fragments.isNotEmpty()) {
            fragments.filter { it.length()<=188 }.take(2).forEach { selected.putIfAbsent(it,"tiny_or_image_only_candidate") }
            for((i,label) in listOf(0 to "earliest_file_time",fragments.size/2 to "middle_file_time",fragments.lastIndex to "latest_file_time")) selected.putIfAbsent(fragments[i],label)
            fragments.filter { it.length()>188 && it.length()%188!=80L }.take(2).forEach { selected.putIfAbsent(it,"atypical_fragment_size") }
        }
        selected.entries.take(12).forEach { (file,reason) ->
            if(file.length()<=WHOLE_LIMIT) add(file,0,file.length(),reason,"cached_fragment")
            else {
                add(file,0,262144,"${reason}_head_excerpt","cached_fragment")
                add(file,maxOf(0,file.length()-262144),262144,"${reason}_tail_excerpt","cached_fragment")
            }
        }
        return result
    }
    fun write(folder: File,output: OutputStream,canceled: ()->Boolean={false},progress: (String)->Unit={}) {
        val samples=plan(folder);check(samples.isNotEmpty()) { "No retained fragment samples found" }
        val rows=JSONArray();val sources=samples.map { it.file }.distinct();val report=JSONObject().put("schema",2).put("readOnly",true).put("rawMediaIncluded",true)
            .put("mediaByteLimit",MAX_BYTES).put("plannedMediaBytes",samples.sumOf { it.length }).put("failureWindowIncluded",samples.any { it.reason=="around_normalization_failure" })
            .put("assemblyBeginningIncluded",samples.any { it.reason=="assembly_beginning" })
            .put("earliestReceiptOrdinalsIncluded",samples.count { it.reason.startsWith("earliest_receipt_ordinal") })
            .put("orderCaution","Filesystem times are NOT playlist order. Ordinals, when present, are receipt claims. Assembly excerpts retain byte offsets; they may not be independently playable.")
        failure(folder)?.let { report.put("lastNormalizationFailure",it) }
        ZipOutputStream(output).use { zip ->
            samples.forEachIndexed { i,s ->
                if(canceled()) throw InterruptedException("Sample export canceled; originals kept")
                progress("Copying sample ${i+1}/${samples.size}; originals kept")
                val alias="samples/sample-${(i+1).toString().padStart(3,'0')}.bin"
                val hash=MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(alias))
                RandomAccessFile(s.file,"r").use { input ->
                    check(input.length()>=s.offset+s.length) { "Retained file changed; sample export stopped" }
                    input.seek(s.offset);var left=s.length;val buffer=ByteArray(65536)
                    while(left>0) {
                        if(canceled()) throw InterruptedException("Sample export canceled; originals kept")
                        val n=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());check(n>0) { "Unexpected end of retained sample" }
                        zip.write(buffer,0,n);hash.update(buffer,0,n);left-=n
                    }
                }
                zip.closeEntry()
                val row=JSONObject().put("file",alias).put("sourceAlias","source-${sources.indexOf(s.file)+1}").put("role",s.role).put("reason",if(s.offset>0 || s.length<s.file.length()) s.reason+"_byte_excerpt" else s.reason)
                    .put("sourceBytes",s.file.length()).put("offset",s.offset).put("bytes",s.length)
                    .put("wholeFragment",s.role=="cached_fragment" && s.offset==0L && s.length==s.file.length())
                    .put("sha256",hash.digest().joinToString("") { "%02x".format(it) })
                if(s.role=="cached_fragment") runCatching {
                    val f=File(s.file.parentFile,s.file.nameWithoutExtension+".json");check(f.length() in 1..8192)
                    val receipt=JSONObject(f.readText());val index=receipt.optInt("fragment_index",-1)
                    if(index>=0) row.put("receiptFragmentIndex",index)
                    val sha=receipt.optString("sha256");if(sha.matches(Regex("[0-9a-f]{64}"))) row.put("receiptWholeFragmentSha256",sha)
                    val track=receipt.optString("track_key");if(track.matches(Regex("[0-9a-f]{64}"))) row.put("receiptTrackKey",track)
                }
                rows.put(row)
            }
            report.put("samples",rows)
            zip.putNextEntry(ZipEntry("report.json"));zip.write(report.toString(2).toByteArray());zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"));zip.write("Actual retained media samples and ordered assembly byte excerpts. Original files unchanged. No app cookie files, request headers, URLs, raw logs, private metadata or original filenames are copied. Media bytes themselves may contain embedded metadata or visible content: review before sharing. Large fragments are labeled head/tail excerpts, not complete fragments. Filesystem time does not prove playback order. Upload this ZIP and the latest recovery log for investigation. This ZIP is not a full media backup.".toByteArray());zip.closeEntry()
        }
    }
}
