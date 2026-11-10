package com.linfranca.ytdlpmobile

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog

internal object DownloadDuplicates {
    fun submit(a: Activity,request: Intent,title: String,live: Boolean=false,monitor: String="",declined: ()->Unit = {}, accepted: (String)->Unit = {}) {
        val existing=if(live) null else TransferQueue.duplicate(a,request)
        if(existing==null) { accepted(TransferQueue.submit(a,request,title,live,monitor));return }
        AlertDialog.Builder(a).setTitle("Download already exists")
            .setMessage("This item is already ${existing.optString("state")} in Queue & history. Open the existing task to review or retry it. Download again deliberately creates another task using these settings.")
            .setPositiveButton("Download again") { _,_ -> accepted(TransferQueue.submit(a,request,title,live,monitor,allowAgain=true)) }
            .setNeutralButton("Open existing task") { _,_ -> a.startActivity(Intent(a,TransferQueueActivity::class.java).putExtra("highlight_job",existing.getString("id"))) }
            .setNegativeButton("Keep existing") { _,_ -> declined() }.setOnCancelListener { declined() }.show()
    }
}
