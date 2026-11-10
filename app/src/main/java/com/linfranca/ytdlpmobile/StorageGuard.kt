package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.io.IOException

internal class InsufficientStorage(message: String): IOException(message)

internal object StorageGuard {
    const val MESSAGE="Not enough storage space. Free space, then retry from Queue & history. Recoverable files are kept."
    fun failure(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(6).any {
        it is InsufficientStorage || StoragePolicy.noSpace(it.message)
    }
    fun failure(detail: String)=StoragePolicy.noSpace(detail)
    fun working(c: Context,expected: Long=0,phase: String="download") {
        check(c.filesDir,StoragePolicy.required(expected,2),phase)
        check(Environment.getExternalStorageDirectory(),StoragePolicy.required(expected,1),"saving to Downloads")
    }
    fun destination(c: Context,bytes: Long,uri: Uri?=null) {
        val path=if(uri==null) Environment.getExternalStorageDirectory() else {
            if(uri.authority!="com.android.externalstorage.documents") return // Provider free space is not observable.
            val document=runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
            if(document.substringBefore(':')=="primary") Environment.getExternalStorageDirectory()
            else if(android.os.Build.VERSION.SDK_INT>=30) c.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes
                .find { it.uuid.equals(document.substringBefore(':'),true) }?.directory else null
        } ?: return
        check(path,StoragePolicy.required(bytes,1),"saving output")
    }
    private fun check(path: File,required: Long,phase: String) {
        val available=runCatching { StatFs(path.absolutePath).availableBytes }.getOrNull() ?: return
        if(StoragePolicy.insufficient(available,required)) throw InsufficientStorage(
            "$MESSAGE ($phase: ${available/1048576} MiB available; at least ${required/1048576} MiB needed.)")
    }
    fun expected(intent: Intent): Long = if(intent.getStringExtra(DownloadService.EXTRA_PRESET)==DownloadService.PRESET_SUBTITLES) 0 else runCatching {
        val info=org.json.JSONObject(intent.getStringExtra(DownloadService.EXTRA_DOWNLOAD_INFO_JSON).orEmpty())
        val formats=info.optJSONArray("requested_formats")
        if(formats!=null) (0 until formats.length()).fold(0L) { total,n ->
            val f=formats.getJSONObject(n);Math.addExact(total,f.optLong("filesize",f.optLong("filesize_approx",0)).coerceAtLeast(0))
        } else info.optLong("filesize",info.optLong("filesize_approx",0)).coerceAtLeast(0)
    }.getOrDefault(0)
    class Watch(private val c: Context,private val folder: File?=null) {
        private var last=0L
        fun poll(line: String="") {
            val now=android.os.SystemClock.elapsedRealtime()
            val merge=line.contains("[Merger]") || line.contains("[ExtractAudio]") || line.contains("[VideoConvertor]")
            if(!merge && now-last<2000) return
            last=now
            val extra=if(merge) folder?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0 else 0
            check(c.filesDir,if(merge) StoragePolicy.required(extra,1) else StoragePolicy.RESERVE,"capture / temporary processing")
        }
    }
}
