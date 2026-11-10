package com.linfranca.ytdlpmobile

/** Per-task presentation only: downloader callbacks and segment/error accounting are never throttled. */
internal class ProgressPresentation(private val intervalMs: Long=300) {
    private var last: Long?=null
    @Synchronized fun allow(now: Long,force: Boolean=false): Boolean {
        val old=last
        if(!force && old!=null && now>=old && now-old<intervalMs) return false
        last=now;return true
    }
}
internal class ProgressLogPresentation {
    private val gate=ProgressPresentation(1000)
    fun allow(line: String,now: Long): Boolean {
        val progress=line.startsWith("[download]") && line.substringAfter("[download]").trimStart().firstOrNull()?.isDigit()==true && line.contains('%')
        return !progress || gate.allow(now,line.contains(" in ") && !line.contains("ETA"))
    }
}
