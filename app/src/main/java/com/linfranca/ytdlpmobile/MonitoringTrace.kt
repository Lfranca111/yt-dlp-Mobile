package com.linfranca.ytdlpmobile

import android.content.Context
import android.os.SystemClock
import java.util.UUID

/** Per-attempt bounded diagnostics. Callers pass stage names, counts and booleans, never secrets. */
internal class MonitoringTrace(private val context: Context, private val area: String) {
    private val id=UUID.randomUUID().toString().take(8)
    private val started=SystemClock.elapsedRealtime()
    private var count=0
    private var rule="none"
    fun rule(value: String) { rule=value }
    @Synchronized fun event(stage: String, detail: String) {
        if(count++>=80) return
        runCatching { MonitoringStore.log(context,"monitoring trace=$id; area=$area; rule=$rule; stage=$stage; elapsedMs=${SystemClock.elapsedRealtime()-started}; $detail") }
    }
    fun failure(stage: String, error: Throwable) {
        event(stage,"outcome=failed; category=${MonitoringFailure.kind(MonitoringStore.error(error))}; type=${error.javaClass.simpleName}")
        RecorderErrorDiagnostic.describe(error).take(10).forEach { event(stage,it) }
    }
    fun <T> step(stage: String,detail: String="", action: ()->T): T {
        val at=SystemClock.elapsedRealtime()
        event(stage,"event=start; $detail")
        try { return action().also { event(stage,"event=complete; stageElapsedMs=${SystemClock.elapsedRealtime()-at}") } }
        catch(e: Exception) { failure(stage,e);throw e }
    }
}
