package com.linfranca.ytdlpmobile

internal object MonitoringBrowserRecoveryPolicy {
    fun reuse(live: Boolean,running: Boolean,available: Boolean,requestedPage: String,currentPage: String)=
        live && running && available && LiveRecordingPolicy.samePage(requestedPage,currentPage)
}
