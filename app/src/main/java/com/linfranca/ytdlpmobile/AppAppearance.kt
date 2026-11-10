package com.linfranca.ytdlpmobile

import android.app.Activity
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import com.google.android.material.button.MaterialButton
import java.util.WeakHashMap

/** Presentation only; never touches embedded website contents or media requests. */
internal object AppAppearance {
    fun install(activity: Activity) {
        val decor=activity.window.decorView
        val seen=WeakHashMap<View,Boolean>()
        fun visit(v: View) {
            if(seen.put(v,true)==null) {
                val radius=18f*v.resources.displayMetrics.density
                if(v is MaterialButton) {
                    // Use the widget setter; a custom background has no Material shape model.
                    // If the newer styling already ran, keep its chosen radius.
                    if(v.getTag(R.id.polish_view_initialized)!=true) v.cornerRadius=radius.toInt()
                }
                else if(v is Button) {
                    v.outlineProvider=object: ViewOutlineProvider() { override fun getOutline(view: View,o: Outline) { o.setRoundRect(0,0,view.width,view.height,minOf(radius,view.height/2f)) } }
                    v.clipToOutline=true
                }
            }
            if(v is ViewGroup && v !is android.webkit.WebView) for(i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener { visit(decor) }
        decor.post { visit(decor) }
    }
}
