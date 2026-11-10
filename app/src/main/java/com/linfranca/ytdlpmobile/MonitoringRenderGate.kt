package com.linfranca.ytdlpmobile

/** UI snapshot only: no changes to checks, scheduling or stored monitoring state. */
internal class MonitoringRenderGate {
    private var rendered: String? = null
    fun changed(snapshot: String): Boolean {
        if (snapshot == rendered) return false
        rendered = snapshot
        return true
    }
}
