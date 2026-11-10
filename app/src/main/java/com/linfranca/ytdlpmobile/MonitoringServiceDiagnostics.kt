package com.linfranca.ytdlpmobile

import android.content.Context
import android.os.SystemClock

internal class MonitoringServiceDiagnostics(private val label: String) {
    private var start=0L
    fun started(c: Context) {
        start=SystemClock.elapsedRealtime()
        MonitoringStore.log(c,"service foreground accepted; service=$label; ${DeviceDiagnostics.snapshot(c)}")
    }
    fun stopped(c: Context) {
        if(start==0L) return
        MonitoringStore.log(c,"service destroyed; service=$label; elapsedMs=${SystemClock.elapsedRealtime()-start}; ${DeviceDiagnostics.snapshot(c)}")
        start=0L
    }
}
