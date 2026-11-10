package com.linfranca.ytdlpmobile

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import android.widget.TextView
import android.widget.EditText
import android.widget.CompoundButton
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.util.WeakHashMap

/** Recolors native views in place. Never visits website DOM or resets control values. */
internal object DisplayPalette {
    private val styled=WeakHashMap<View,Int>()
    private val names=intArrayOf(R.color.polish_canvas,R.color.polish_panel,R.color.polish_tonal,
        R.color.polish_text,R.color.polish_muted,R.color.polish_edge,R.color.polish_violet,
        R.color.polish_amber,R.color.polish_error,R.color.polish_saved)
    private val light=intArrayOf(0xfffaf7ff.toInt(),0xfff0eaf8.toInt(),0xffede2ff.toInt(),0xff20172b.toInt(),
        0xff5f506c.toInt(),0xffa490bd.toInt(),0xff6331b4.toInt(),0xff805100.toInt(),0xffa62345.toInt(),0xff4c208f.toInt())
    private val dark=intArrayOf(0xff100c16.toInt(),0xff1b1426.toInt(),0xff2e1d48.toInt(),0xfff5ecff.toInt(),
        0xffc0b0d1.toInt(),0xff74608f.toInt(),0xffc7a5ff.toInt(),0xffffd082.toInt(),0xffff9bb4.toInt(),0xffdbc4ff.toInt())
    fun refresh(activity: Activity) {
        val roots=WindowInspector.getGlobalWindowViews().toMutableList()
        if(activity.window.decorView !in roots) roots+=activity.window.decorView
        for(root in roots) {
            // Each live window retains its own context/theme; this includes open dialogs.
            val canvas=ContextCompat.getColor(root.context,R.color.polish_canvas)
            walk(root,canvas)
        }
        activity.window.decorView.setBackgroundColor(ContextCompat.getColor(activity,R.color.polish_canvas))
    }
    private fun walk(view: View,signature: Int) {
        if(view is WebView) return
        if(styled[view]!=signature) {
            styled[view]=signature
            recolor(view)
        }
        if(view is ViewGroup) {
            for(i in 0 until view.childCount) walk(view.getChildAt(i),signature)
        }
    }
    private fun recolor(v: View) {
        fun color(id: Int)=ContextCompat.getColor(v.context,id)
        fun map(c: Int,neutral: Boolean=false): Int {
            val rgb=c or 0xff000000.toInt()
            if(rgb==0xff7c4dff.toInt() || rgb==0xff6331b4.toInt())
                return (color(R.color.primary) and 0xffffff) or (c and 0xff000000.toInt())
            val index=light.indices.firstOrNull { light[it]==rgb || dark[it]==rgb }
            if(index!=null) return (color(names[index]) and 0xffffff) or (c and 0xff000000.toInt())
            if(neutral && Color.red(c)==Color.green(c) && Color.green(c)==Color.blue(c))
                return (color(R.color.polish_text) and 0xffffff) or (c and 0xff000000.toInt())
            return c
        }
        fun tint(old: ColorStateList?,neutral: Boolean=false): ColorStateList? = old?.let {
            PaletteStateLists.recolor(it) { c -> map(c,neutral) }
        }
        val done=java.util.IdentityHashMap<Drawable,Boolean>()
        fun drawable(d: Drawable?) {
            if(d==null || done.put(d,true)!=null) return
            when(d) {
                is ColorDrawable -> { val next=map(d.color);if(next!=d.color)d.color=next }
                is GradientDrawable -> {
                    d.color?.let { d.color=tint(it) }
                    d.colors?.let { old-> d.colors=old.map { map(it) }.toIntArray() }
                }
                is LayerDrawable -> for(i in 0 until d.numberOfLayers) drawable(d.getDrawable(i))
                is InsetDrawable -> drawable(d.drawable)
                is StateListDrawable -> for(i in 0 until d.stateCount) drawable(d.getStateDrawable(i))
            }
        }
        drawable(v.background)
        if(v is MaterialButton) {
            v.backgroundTintList=tint(v.backgroundTintList)
            v.strokeColor=tint(v.strokeColor)
            v.iconTint=tint(v.iconTint)
            v.setTextColor(tint(v.textColors))
        } else if(v is TextView) {
            v.setTextColor(tint(v.textColors,true))
            v.setHintTextColor(tint(v.hintTextColors,true))
            v.setLinkTextColor(tint(v.linkTextColors))
            v.compoundDrawables.forEach(::drawable)
        }
        if(v is CompoundButton) {
            val accent=color(R.color.polish_violet)
            v.buttonTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
                intArrayOf(accent,color(R.color.polish_muted)))
        }
        if(v is EditText) v.backgroundTintList=tint(v.backgroundTintList)
        if(v is android.widget.AutoCompleteTextView)
            v.setDropDownBackgroundDrawable(ContextCompat.getDrawable(v.context,R.drawable.polish_panel))
        if(v is android.widget.Spinner)
            v.setPopupBackgroundDrawable(ContextCompat.getDrawable(v.context,R.drawable.polish_panel))
        if(v is android.widget.ScrollView || v is androidx.core.widget.NestedScrollView) {
            v.setVerticalScrollbarThumbDrawable(GradientDrawable().apply {
                setColor(color(R.color.polish_violet))
                cornerRadius=3*v.resources.displayMetrics.density
                setSize((3*v.resources.displayMetrics.density).toInt(),(16*v.resources.displayMetrics.density).toInt())
            })
        }
        if(v is TextInputLayout) {
            v.defaultHintTextColor=ColorStateList.valueOf(color(R.color.polish_muted))
            v.boxStrokeColor=color(R.color.polish_violet)
            if(v.boxBackgroundMode!=TextInputLayout.BOX_BACKGROUND_NONE) v.boxBackgroundColor=color(R.color.polish_panel)
        }
        if(v is ProgressBar) {
            v.progressTintList=tint(v.progressTintList)
            v.indeterminateTintList=tint(v.indeterminateTintList)
            v.progressBackgroundTintList=tint(v.progressBackgroundTintList)
            drawable(v.progressDrawable)
        }
        if(v.contentDescription?.contains("tap to open")==true && v is MaterialButton ||
            v.contentDescription?.contains("current page")==true && v is MaterialButton) {
            if(v is MaterialButton) VisualPolish.tab(v,v.isSelected)
        }
    }
}
