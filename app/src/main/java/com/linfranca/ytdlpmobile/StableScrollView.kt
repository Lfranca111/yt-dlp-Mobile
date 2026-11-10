package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Keep native scrolling/flinging; ignore stale focus reveals after a manual landscape drag. */
class StableScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    android.widget.ScrollView(context, attrs) {
    private val scrollIntent = ScrollIntent(ViewConfiguration.get(context).scaledTouchSlop)
    private var touching = false
    init { ScrollResetDiagnostics.installed(context) }
    private var laidOutWidth = -1
    private var laidOutHeight = -1
    private fun userOwnsScroll() = scrollIntent.manualScroll() &&
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ScrollResetDiagnostics.attached(this, "attached")
    }
    override fun onDetachedFromWindow() {
        ScrollResetDiagnostics.attached(this, "detached")
        super.onDetachedFromWindow()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> scrollIntent.begin(event.x, event.y)
            MotionEvent.ACTION_MOVE -> scrollIntent.move(event.x, event.y)
        }
        // Keep ownership after ACTION_UP, so a following text/layout refresh cannot pull back.
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        scrollIntent.explicitAction()
        return super.dispatchKeyEvent(event)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        scrollIntent.explicitAction()
        return super.performAccessibilityAction(action, arguments)
    }
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        ScrollResetDiagnostics.reveal(this, rectangle, !userOwnsScroll())
        if (userOwnsScroll()) return false
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }
    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        ScrollResetDiagnostics.changed(this, top, oldTop, touching)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val keepPosition = userOwnsScroll() && laidOutWidth == right-left && laidOutHeight == bottom-top
        val x = scrollX
        val y = scrollY
        super.onLayout(changed, left, top, right, bottom)
        // Native scrollTo still clamps normally if content was shortened. Size changes retain
        // native keyboard/rotation behavior and the activity's one-time rotation restoration.
        if (keepPosition && (scrollX != x || scrollY != y)) scrollTo(x, y)
        laidOutWidth = right-left
        laidOutHeight = bottom-top
    }
}
