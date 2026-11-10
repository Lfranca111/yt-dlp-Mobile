package com.linfranca.ytdlpmobile

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import java.util.WeakHashMap
import androidx.appcompat.app.AppCompatActivity

/** Keep the existing view tree, pending activity results and WebView sessions alive. */
open class DisplayActivity : AppCompatActivity() {
    private val positions = WeakHashMap<View, MutableMap<Int, Pair<Int, Int>>>()
    private var lastOrientation = 0
    private val restoreGate = ScrollRestoreGate()
    private var pendingRestore: ViewTreeObserver.OnPreDrawListener? = null
    private var restoreObserver: ViewTreeObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastOrientation = resources.configuration.orientation
        DisplaySettings.attach(this)
        ScrollResetDiagnostics.activity(this, "created")
    }
    override fun onStart() { super.onStart();LandscapePanels.started(this) }
    override fun onStop() { LandscapePanels.stopped(this);super.onStop() }
    override fun onResume() {
        super.onResume()
        DisplaySettings.refresh(this)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        cancelPendingRestore()
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        cancelPendingRestore()
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        cancelPendingRestore()
        return super.dispatchGenericMotionEvent(event)
    }
    private fun cancelPendingRestore() {
        restoreGate.cancel()
        pendingRestore?.let { listener ->
            restoreObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        pendingRestore = null
        restoreObserver = null
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        ScrollResetDiagnostics.configuration(this, lastOrientation, newConfig)
        cancelPendingRestore()
        val changingOrientation = lastOrientation != newConfig.orientation
        val targets = mutableMapOf<View, Pair<Int, Int>>()
        if (changingOrientation) {
            fun collect(v: View) {
                if (v is android.webkit.WebView) return
                if (v is ScrollView || v is NestedScrollView) {
                    val saved = positions.getOrPut(v) { mutableMapOf() }
                    saved[lastOrientation] = Pair(v.scrollX, v.scrollY)
                    targets[v] = saved[newConfig.orientation] ?: Pair(v.scrollX, v.scrollY)
                }
                if (v is ViewGroup) { for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
            }
            collect(window.decorView)
        }
        lastOrientation = newConfig.orientation
        super.onConfigurationChanged(newConfig)
        DisplaySettings.refresh(this)
        LandscapePanels.refresh()
        if (targets.isEmpty()) return
        val token = restoreGate.schedule()
        val observer = window.decorView.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (pendingRestore === this) {
                    pendingRestore = null
                    restoreObserver = null
                }
                if (!isDestroyed && restoreGate.allows(token)) {
                    targets.forEach { (v, point) ->
                        if (v.isAttachedToWindow) v.scrollTo(point.first, point.second)
                    }
                }
                return true
            }
        }
        pendingRestore = listener
        restoreObserver = observer
        observer.addOnPreDrawListener(listener)
    }
    override fun onDestroy() {
        ScrollResetDiagnostics.activity(this, "destroyed")
        cancelPendingRestore()
        LandscapePanels.destroyed(this)
        DisplaySettings.detach(this)
        super.onDestroy()
    }
}
