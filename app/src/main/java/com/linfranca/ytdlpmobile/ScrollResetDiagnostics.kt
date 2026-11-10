package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded geometry/caller observations only. No URLs, text, cookies or control values. */
internal object ScrollResetDiagnostics {
    private val installed = AtomicBoolean()
    private val resets = AtomicInteger()
    private val reveals = AtomicInteger()
    private val configurations = AtomicInteger()
    private val lifecycleEvents = AtomicInteger()
    private data class Sample(var peak: Int, var since: Long, var logged: Long = -1000L)
    private val samples = WeakHashMap<View, Sample>()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ui-scroll-diagnostic").apply { isDaemon = true }
    }
    private fun write(c: Context, line: String) {
        val app = c.applicationContext
        writer.execute { runCatching { MonitoringStore.log(app, line) } }
    }
    fun installed(c: Context) {
        if (installed.compareAndSet(false, true)) write(c, "UI scroll trace installed; patch=1.0.61-color-scroll-trace; geometry and caller names only")
    }
    fun configuration(c: Context, old: Int, next: Configuration) {
        if (configurations.incrementAndGet() <= 12) write(c,
            "UI configuration; oldOrientation=$old; newOrientation=${next.orientation}; widthDp=${next.screenWidthDp}; heightDp=${next.screenHeightDp}; uiMode=${next.uiMode}")
    }
    fun activity(c: Context, event: String) {
        if (lifecycleEvents.incrementAndGet() <= 24) write(c,
            "UI activity $event; screen=${c.javaClass.simpleName}; instance=${System.identityHashCode(c)}; orientation=${c.resources.configuration.orientation}")
    }
    fun attached(v: View, event: String) {
        if (lifecycleEvents.incrementAndGet() <= 24) write(v.context,
            "UI scroll view $event; scrollY=${v.scrollY}; ${geometry(v)}")
    }
    private fun geometry(v: View): String {
        val content = (v as? ViewGroup)?.getChildAt(0)
        val focused = v.findFocus()
        return "view=${v.javaClass.simpleName}; instance=${System.identityHashCode(v)}; viewport=${v.width}x${v.height}; contentHeight=${content?.height}; measuredContentHeight=${content?.measuredHeight}; layoutRequested=${v.isLayoutRequested}; focusClass=${focused?.javaClass?.simpleName}; orientation=${v.resources.configuration.orientation}"
    }
    private fun caller() = Throwable().stackTrace.drop(2).take(20)
        .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    fun changed(v: View, y: Int, oldY: Int, touching: Boolean) {
        if (v.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE || resets.get() >= 12) return
        val now = SystemClock.uptimeMillis()
        val sample = samples.getOrPut(v) { Sample(oldY, now) }
        if (now-sample.since > 650L || y > sample.peak) { sample.peak = oldY; sample.since = now }
        sample.peak = maxOf(sample.peak, oldY)
        val threshold = maxOf((48*v.resources.displayMetrics.density).toInt(), v.height/3)
        val atTop = y <= (24*v.resources.displayMetrics.density).toInt()
        if (now-sample.logged >= 650L &&
            (oldY-y >= threshold || atTop && sample.peak-y >= threshold)) {
            sample.logged = now
            sample.peak = y
            if (resets.incrementAndGet() <= 12) write(v.context,
                "UI upward scroll observation; oldY=$oldY; newY=$y; touching=$touching; ${geometry(v)}; caller=${caller()}")
        }
    }
    fun reveal(v: View, rectangle: Rect, allowed: Boolean) {
        if (reveals.get() >= 8 || v.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE ||
            v.scrollY < v.height/2 || rectangle.top >= v.scrollY) return
        if (reveals.incrementAndGet() <= 8) write(v.context,
            "UI child reveal request; allowed=$allowed; scrollY=${v.scrollY}; rectangleTop=${rectangle.top}; ${geometry(v)}; caller=${caller()}")
    }
}
