package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.res.Configuration
import android.widget.LinearLayout

/** Two permanent panes: form and preview. Changes measurement, never parents. */
internal class AdaptivePairLayout(context: Context) : LinearLayout(context) {
    var browserFirst=false
    // Weighted landscape panes must not receive an unbounded baseline probe.
    init { isBaselineAligned = false }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val wide=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        val next=if(wide) HORIZONTAL else VERTICAL
        if(orientation!=next) orientation=next
        for(i in 0 until childCount) {
            val p=getChildAt(i).layoutParams as LayoutParams
            p.width=if(wide) 0 else LayoutParams.MATCH_PARENT
            p.height=if(wide) LayoutParams.MATCH_PARENT else 0
            p.weight=1f
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        super.onLayout(changed,l,t,r,b)
        if(browserFirst && orientation==HORIZONTAL && childCount==2 && getChildAt(1).visibility!=android.view.View.GONE) {
            val form=getChildAt(0);val browser=getChildAt(1)
            browser.layout(paddingLeft,browser.top,paddingLeft+browser.measuredWidth,browser.bottom)
            val x=paddingLeft+browser.measuredWidth
            form.layout(x,form.top,x+form.measuredWidth,form.bottom)
        }
    }
}
