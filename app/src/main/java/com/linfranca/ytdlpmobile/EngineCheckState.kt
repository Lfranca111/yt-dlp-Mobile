package com.linfranca.ytdlpmobile

/** Process-owned state outlives Activity recreation; late completions require the matching token. */
internal object EngineCheckState {
    enum class Phase { IDLE, CHECKING, READY, FAILED, TIMED_OUT, CANCELED }
    data class Snapshot(val token: Long,val phase: Phase,val version: String,val detail: String) {
        val checking get()=phase==Phase.CHECKING
    }
    private var serial=0L
    private var value=Snapshot(0,Phase.IDLE,"","")
    fun shouldPresentStatus(checking: Boolean,current: String,mediaActive: Boolean)=!mediaActive &&
        (checking || current.startsWith("Checking yt-dlp") || current.startsWith("Checking for a yt-dlp") || current.startsWith("Update check timed out"))
    @Synchronized fun snapshot()=value
    @Synchronized fun begin(): Long? {
        if(value.checking) return null
        val token=++serial;value=Snapshot(token,Phase.CHECKING,value.version,"");return token
    }
    @Synchronized fun complete(token: Long,phase: Phase,version: String,detail: String): Boolean {
        if(value.token!=token || !value.checking || phase==Phase.CHECKING) return false
        value=Snapshot(token,phase,version.ifBlank { value.version },detail);return true
    }
}
