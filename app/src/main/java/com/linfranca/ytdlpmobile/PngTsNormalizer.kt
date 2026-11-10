package com.linfranca.ytdlpmobile

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Extracts checked PNG tsDa chunks, preserving their payload bytes for the TS demuxer. Inputs stay read-only. */
internal object PngTsNormalizer {
    class Failure(val offset: Long,val packetsSeen: Long,val wrappersSeen: Int,val bufferedBytes: Int,val reason: String): IllegalStateException("$reason; inputByteOffset=$offset; transportPackets=$packetsSeen; imageWrappers=$wrappersSeen; bufferedBytes=$bufferedBytes")
    private val PNG=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    private const val WINDOW=65536
    data class Stats(val inputBytes: Long,val outputBytes: Long,val packets: Long,val wrappers: Int,val emptyWrappers: Int,val removedBytes: Long,val chunkPayloads: Int=0,val payloadAlignmentBytes: Long=0)
    private fun png(bytes: ByteArray,start: Int,end: Int)=start+8<=end && PNG.indices.all { bytes[start+it]==PNG[it] }
    private fun tsOffset(bytes: ByteArray,start: Int,end: Int): Int {
        for(i in start until end-376) {
            if((0..2).all { n -> val p=i+188*n;p+3<end && bytes[p]==71.toByte() && (bytes[p+3].toInt() and 48)!=0 }) return i
        }
        return -1
    }
    fun looksWrapped(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header=ByteArray(WINDOW);var size=0
            while(size<header.size) { val n=input.read(header,size,header.size-size);if(n<0) break;size+=n }
            png(header,0,size) && tsOffset(header,8,size)>=8
        }
    }.getOrDefault(false)
    private fun onlyImages(bytes: ByteArray,start: Int,end: Int): Int {
        var p=start;var count=0
        while(p<end) {
            if(!png(bytes,p,end)) return -1
            p+=8;var ended=false
            while(p+12<=end) {
                val length=(0..3).fold(0L) { acc,n -> (acc shl 8)+(bytes[p+n].toLong() and 255) }
                if(length>WINDOW || p+12L+length>end) return -1
                val type=String(bytes,p+4,4,Charsets.US_ASCII)
                p+=(12+length).toInt()
                if(type=="IEND") { if(length!=0L) return -1;ended=true;break }
            }
            if(!ended) return -1
            count++
            while(p<end && bytes[p]==0.toByte()) p++
        }
        return count
    }
    fun copy(input: InputStream,output: OutputStream,canceled: ()->Boolean={false},progress: (Long)->Unit={}): Stats {
        val buffered=java.io.BufferedInputStream(input,WINDOW)
        buffered.mark(WINDOW)
        val header=ByteArray(WINDOW);var size=0
        while(size<header.size) {
            if(canceled()) throw InterruptedException("Local recovery canceled; original media kept")
            val n=buffered.read(header,size,header.size-size);if(n<0) break;size+=n
        }
        buffered.reset()
        return if(chunkFramed(header,size)) copyChunks(buffered,output,canceled,progress) else copyLegacy(buffered,output,canceled,progress)
    }
    private fun chunkFramed(bytes: ByteArray,size: Int): Boolean {
        if(!png(bytes,0,size)) return false
        var p=8
        while(p+8<=size) {
            val length=(0..3).fold(0L) { acc,n -> (acc shl 8)+(bytes[p+n].toLong() and 255) }
            val type=String(bytes,p+4,4,Charsets.US_ASCII)
            if(type=="tsDa") return true
            if(type=="IEND" || length>size || p+12L+length>size) return false
            p+=(12+length).toInt()
        }
        return false
    }
    private fun copyChunks(input: InputStream,output: OutputStream,canceled: ()->Boolean,progress: (Long)->Unit): Stats {
        var consumed=0L;var written=0L;var packets=0L;var wrappers=0;var empty=0;var payloads=0;var alignment=0L;var reported=0L
        fun reject(reason: String): Nothing=throw Failure(consumed,packets,wrappers,0,reason)
        fun read(bytes: ByteArray,offset: Int=0,length: Int=bytes.size-offset) {
            var done=0
            while(done<length) {
                if(canceled()) throw InterruptedException("Local recovery canceled; original media kept")
                val n=input.read(bytes,offset+done,length-done)
                if(n<0) reject("Truncated PNG chunk or transport payload")
                done+=n;consumed+=n
                if(consumed-reported>=16L*1024*1024) { reported=consumed;progress(consumed) }
            }
        }
        fun number(bytes: ByteArray)=bytes.fold(0L) { acc,b -> (acc shl 8)+(b.toLong() and 255) }
        val buffer=ByteArray(WINDOW)
        while(true) {
            if(canceled()) throw InterruptedException("Local recovery canceled; original media kept")
            val first=input.read();if(first<0) break;consumed++
            val signature=ByteArray(8);signature[0]=first.toByte();read(signature,1,7)
            if(!signature.contentEquals(PNG)) reject("Expected the next PNG container after IEND")
            wrappers++;var hasPayload=false;var chunkIndex=0
            while(true) {
                val lengthBytes=ByteArray(4);read(lengthBytes);val length=number(lengthBytes)
                if(length>0x7fffffffL) reject("Invalid PNG chunk length")
                val typeBytes=ByteArray(4);read(typeBytes);val type=String(typeBytes,Charsets.US_ASCII)
                if(!type.matches(Regex("[A-Za-z]{4}"))) reject("Invalid PNG chunk type")
                if(chunkIndex++==0 && (type!="IHDR" || length!=13L)) reject("PNG IHDR is missing or invalid")
                if(type=="IEND" && length!=0L) reject("Invalid PNG IEND length")
                val crc=java.util.zip.CRC32().apply { update(typeBytes) }
                var remaining=length
                if(type=="tsDa" && length>0) {
                    if(length<564) reject("PNG transport payload is too short to verify")
                    read(buffer,0,564);crc.update(buffer,0,564)
                    if((0..2).any { k -> buffer[k*188]!=71.toByte() || (buffer[k*188+3].toInt() and 48)==0 }) reject("PNG tsDa payload lacks verified transport sync")
                    output.write(buffer,0,564);written+=564;remaining-=564;hasPayload=true
                    payloads++;alignment+=length%188;packets+=length/188
                }
                while(remaining>0) {
                    val n=minOf(remaining,buffer.size.toLong()).toInt();read(buffer,0,n);crc.update(buffer,0,n)
                    if(type=="tsDa") { output.write(buffer,0,n);written+=n }
                    remaining-=n
                }
                val expected=ByteArray(4);read(expected)
                if(crc.value!=number(expected)) reject("PNG chunk checksum mismatch")
                if(type=="IEND") break
            }
            if(!hasPayload) empty++
        }
        if(packets<3) reject("No usable transport stream was recovered")
        return Stats(consumed,written,packets,wrappers,empty,consumed-written,payloads,alignment)
    }
    private fun copyLegacy(input: InputStream,output: OutputStream,canceled: ()->Boolean,progress: (Long)->Unit): Stats {
        val bytes=ByteArray(WINDOW);var position=0;var end=0;var eof=false
        var read=0L;var written=0L;var packets=0L;var wrappers=0;var empty=0;var removed=0L;var reported=0L
        fun ensure(count: Int) {
            if(end-position>=count || eof) return
            if(position>0) { bytes.copyInto(bytes,0,position,end);end-=position;position=0 }
            while(end-position<count && !eof) {
                if(canceled()) throw InterruptedException("Local recovery canceled; original media kept")
                val n=input.read(bytes,end,bytes.size-end)
                if(n<0) eof=true else { end+=n;read+=n;if(read-reported>=16L*1024*1024) { reported=read;progress(read) } }
            }
        }
        fun reject(reason: String): Nothing = throw Failure(read-(end-position),packets,wrappers,end-position,reason)
        while(true) {
            if(canceled()) throw InterruptedException("Local recovery canceled; original media kept")
            ensure(188)
            if(position==end) break
            if(bytes[position]==71.toByte() && end-position>=188) {
                output.write(bytes,position,188);position+=188;written+=188;packets++;continue
            }
            ensure(WINDOW)
            if(!png(bytes,position,end)) reject("Unrecognized bytes at a transport-packet boundary")
            val offset=tsOffset(bytes,position+8,end)
            if(offset>=0) {
                var images=0
                for(i in position until offset) if(png(bytes,i,offset)) images++
                wrappers+=images;empty+=maxOf(0,images-1);removed+=offset-position;position=offset
            } else {
                val images=if(eof) onlyImages(bytes,position,end) else -1
                if(images<=0) reject("Image wrapper has no verified transport-stream payload")
                wrappers+=images;empty+=images;removed+=end-position;position=end
            }
        }
        if(packets<3) reject("No usable transport stream was recovered")
        return Stats(read,written,packets,wrappers,empty,removed)
    }
}
