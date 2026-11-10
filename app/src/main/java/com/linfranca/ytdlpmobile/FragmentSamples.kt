package com.linfranca.ytdlpmobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

internal object FragmentSamples {
    fun export(c: Context,folder: File,canceled: ()->Boolean,progress: (String)->Unit): Uri {
        val plan=FragmentSampleCore.plan(folder)
        check(plan.isNotEmpty()) { "No retained samples found; originals kept" }
        StorageGuard.destination(c,plan.sumOf { it.length }+131072)
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,"fragment-samples-${System.currentTimeMillis()}.zip")
            put(MediaStore.Downloads.MIME_TYPE,"application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/yt-dlp Mobile/Diagnostics")
            put(MediaStore.Downloads.IS_PENDING,1)
        }
        val uri=c.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values) ?: error("Android could not create the sample ZIP")
        try {
            val watch=StorageGuard.Watch(c)
            c.contentResolver.openOutputStream(uri,"w")!!.use { out -> FragmentSampleCore.write(folder,out,{ watch.poll();canceled() },progress) }
            if(canceled()) throw InterruptedException("Sample export canceled; originals kept")
            check(c.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)>0) { "Android could not publish the sample ZIP" }
            return uri
        } catch(e: Exception) { runCatching { c.contentResolver.delete(uri,null,null) };throw e }
    }
}
