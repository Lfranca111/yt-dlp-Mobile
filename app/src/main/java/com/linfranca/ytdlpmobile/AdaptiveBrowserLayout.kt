package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/** Three permanent children: scrollable header controls, WebView, scrollable footer controls. */
class AdaptiveBrowserLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ViewGroup(context,attrs) {
    private var landscape=false
    private var controlsWidth=0
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(context,attrs)
    override fun generateLayoutParams(p: LayoutParams?) = LayoutParams(p)
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec)
        val h=MeasureSpec.getSize(heightMeasureSpec)
        val width=max(0,w-paddingLeft-paddingRight)
        val height=max(0,h-paddingTop-paddingBottom)
        landscape=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE && width>height
        controlsWidth=if(landscape) min((320*resources.displayMetrics.density).toInt(),(width*.40f).toInt()) else width
        val header=getChildAt(0);val web=getChildAt(1);val footer=getChildAt(2)
        header.measure(MeasureSpec.makeMeasureSpec(controlsWidth,MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((height*(if(landscape) .42f else .30f)).toInt(),MeasureSpec.AT_MOST))
        val footerSpace=if(landscape) height-header.measuredHeight else (height*.40f).toInt()
        footer.measure(MeasureSpec.makeMeasureSpec(controlsWidth,MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(0,footerSpace),MeasureSpec.AT_MOST))
        web.measure(MeasureSpec.makeMeasureSpec(if(landscape) width-controlsWidth else width,MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(if(landscape) height else max(0,height-header.measuredHeight-footer.measuredHeight),MeasureSpec.EXACTLY))
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        val header=getChildAt(0);val web=getChildAt(1);val footer=getChildAt(2)
        val x=paddingLeft;val y=paddingTop
        header.layout(x,y,x+header.measuredWidth,y+header.measuredHeight)
        if(landscape) {
            footer.layout(x,y+header.measuredHeight,x+footer.measuredWidth,y+header.measuredHeight+footer.measuredHeight)
            web.layout(x+controlsWidth,y,x+controlsWidth+web.measuredWidth,y+web.measuredHeight)
        } else {
            web.layout(x,y+header.measuredHeight,x+web.measuredWidth,y+header.measuredHeight+web.measuredHeight)
            footer.layout(x,y+header.measuredHeight+web.measuredHeight,x+footer.measuredWidth,y+header.measuredHeight+web.measuredHeight+footer.measuredHeight)
        }
    }
}
