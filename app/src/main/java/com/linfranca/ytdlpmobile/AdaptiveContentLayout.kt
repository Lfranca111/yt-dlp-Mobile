package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.max

/** Repositions the same child views; never rebinds forms, listeners or task state. */
class AdaptiveContentLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    var splitAtId: Int = View.NO_ID
    private var split = -1
    private var columnWidth = 0
    private val gap get() = (20*resources.displayMetrics.density).toInt()
    init {
        if(attrs!=null) context.obtainStyledAttributes(attrs,R.styleable.AdaptiveContentLayout).let {
            splitAtId=it.getResourceId(R.styleable.AdaptiveContentLayout_landscapeSplitAt,View.NO_ID)
            it.recycle()
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        split=(0 until childCount).firstOrNull { getChildAt(it).id==splitAtId && splitAtId!=View.NO_ID } ?: -1
        val wide=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE &&
            width/resources.displayMetrics.density>=600 && split>0
        if(!wide) { split=-1; super.onMeasure(widthMeasureSpec,heightMeasureSpec);return }
        columnWidth=max(0,(width-paddingLeft-paddingRight-gap)/2)
        val heights=intArrayOf(paddingTop,paddingTop)
        for(i in 0 until childCount) {
            val child=getChildAt(i)
            if(child.visibility==View.GONE) continue
            val lp=child.layoutParams as LinearLayout.LayoutParams
            val cw=max(0,columnWidth-lp.leftMargin-lp.rightMargin)
            val ws=getChildMeasureSpec(MeasureSpec.makeMeasureSpec(cw,MeasureSpec.EXACTLY),0,lp.width)
            val hs=if(lp.height>=0) MeasureSpec.makeMeasureSpec(lp.height,MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
            child.measure(ws,hs)
            heights[if(i<split) 0 else 1]+=lp.topMargin+child.measuredHeight+lp.bottomMargin
        }
        setMeasuredDimension(resolveSize(width,widthMeasureSpec),
            resolveSize(max(heights[0],heights[1])+paddingBottom,heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if(split<0) {super.onLayout(changed,l,t,r,b);return}
        val y=intArrayOf(paddingTop,paddingTop)
        for(i in 0 until childCount) {
            val child=getChildAt(i);if(child.visibility==View.GONE) continue
            val lp=child.layoutParams as LinearLayout.LayoutParams
            val col=if(i<split) 0 else 1
            val x=paddingLeft+col*(columnWidth+gap)+lp.leftMargin
            y[col]+=lp.topMargin
            child.layout(x,y[col],x+child.measuredWidth,y[col]+child.measuredHeight)
            y[col]+=child.measuredHeight+lp.bottomMargin
        }
    }
}
