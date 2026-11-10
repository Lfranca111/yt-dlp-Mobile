package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView

/** Settings, page rows and output controls share one scroll surface in both orientations. */
internal class CollectionEditorLayout(c: Context,header: View,private val pages: RecyclerView,
    footer: View,pageAdapter: RecyclerView.Adapter<*>): FrameLayout(c) {
    private class Section(private val view: View): RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount()=1
        override fun onCreateViewHolder(parent: ViewGroup,type: Int): RecyclerView.ViewHolder =
            object: RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
                layoutParams=RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
            val container=holder.itemView as FrameLayout
            if(view.parent!==container) {
                // A change animation may briefly keep an old holder. Move the actual controls
                // between holder containers, never remove a RecyclerView-managed child directly.
                (view.parent as? ViewGroup)?.removeView(view)
                container.removeAllViews()
                container.addView(view,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
            }
        }
    }
    init {
        pages.adapter=ConcatAdapter(Section(header),pageAdapter,Section(footer))
        scrollbar()
        addView(pages,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
    }

    private fun scrollbar() {
        val density=resources.displayMetrics.density
        pages.isVerticalScrollBarEnabled=true
        pages.scrollBarSize=(3*density).toInt().coerceAtLeast(1)
        pages.setVerticalScrollbarThumbDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(androidx.core.content.ContextCompat.getColor(context,R.color.polish_violet))
            cornerRadius=3*density;setSize((3*density).toInt(),(16*density).toInt())
        })
        pages.isScrollbarFadingEnabled=true;pages.scrollBarDefaultDelayBeforeFade=600;pages.scrollBarFadeDuration=250
        pages.scrollBarStyle=View.SCROLLBARS_INSIDE_OVERLAY
    }
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        scrollbar()
    }
}
