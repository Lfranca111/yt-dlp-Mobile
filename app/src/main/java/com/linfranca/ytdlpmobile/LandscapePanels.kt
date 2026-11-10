package com.linfranca.ytdlpmobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.core.view.ViewCompat
import java.util.WeakHashMap

internal object LandscapePanelPolicy {
    fun panelWidth(width: Int,height: Int,density: Float,landscape: Boolean): Int =
        if(!landscape || width<=height || width/density<600) 0 else (width*.45f).toInt().coerceAtLeast((280*density).toInt())
}

/** Keeps existing windows, controls and callbacks. Only bounds/inset reservation change. */
internal object LandscapePanels {
    private val visible=linkedSetOf<Activity>()
    private val reserves=WeakHashMap<Activity,Int>()
    private val original=WeakHashMap<View,WindowManager.LayoutParams>()
    private val activityOriginal=WeakHashMap<Activity,WindowManager.LayoutParams>()
    private val main=Handler(Looper.getMainLooper())
    private val panelActivities=setOf("AppSettingsActivity","DisplaySettingsActivity","InstructionsActivity","TransferQueueActivity","CrashReportsActivity","CollectionConvertActivity")
    private fun excluded(a: Activity)=a.javaClass.simpleName in setOf("BrowserSessionActivity","MonitoringBrowserSessionActivity")
    private val poll=object: Runnable { override fun run() { runCatching { refresh() };if(visible.isNotEmpty()) main.postDelayed(this,200) } }
    fun started(a: Activity) { visible.add(a);main.removeCallbacks(poll);main.post(poll) }
    fun stopped(a: Activity) { visible.remove(a);if(visible.isEmpty()) main.removeCallbacks(poll);refresh() }
    fun destroyed(a: Activity) { visible.remove(a);reserves.remove(a);activityOriginal.remove(a);if(visible.isEmpty()) main.removeCallbacks(poll) }
    fun reserved(a: Activity)=reserves[a] ?: 0
    private fun owner(c: Context): Activity? {
        var next=c;val seen=hashSetOf<Context>()
        while(seen.add(next)) { if(next is Activity) return next;next=(next as? ContextWrapper)?.baseContext ?: return null }
        return null
    }
    private fun browserOpen(v: View): Boolean {
        if(v.visibility!=View.VISIBLE) return false
        if(v is WebView) return true
        if(v is ViewGroup) for(i in 0 until v.childCount) if(browserOpen(v.getChildAt(i))) return true
        return false
    }
    fun choiceWidth(context: Context): Int {
        val a=owner(context) ?: return 0
        if(excluded(a)) return 0
        val maximum=if(Build.VERSION.SDK_INT>=30) a.windowManager.maximumWindowMetrics.bounds.takeUnless { it.isEmpty } else null
        val bounds=maximum ?: run {
            val m=android.util.DisplayMetrics();@Suppress("DEPRECATION") a.windowManager.defaultDisplay.getRealMetrics(m);android.graphics.Rect(0,0,m.widthPixels,m.heightPixels)
        }
        return LandscapePanelPolicy.panelWidth(bounds.width(),bounds.height(),a.resources.displayMetrics.density,a.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)
    }
    fun refresh() {
        val roots=WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.visibility==View.VISIBLE }
        val panelHosts=visible.filter { it.javaClass.simpleName in panelActivities }
        for(a in visible.toList()) {
            if(a.isDestroyed || excluded(a)) continue
            val panelWidth=choiceWidth(a)
            val panelActivity=a.javaClass.simpleName in panelActivities
            if(panelActivity) {
                val current=a.window.attributes
                val saved=activityOriginal.getOrPut(a) { WindowManager.LayoutParams().apply { copyFrom(current) } }
                val next=WindowManager.LayoutParams().apply {
                    copyFrom(current)
                    if(panelWidth==0) { this.width=saved.width;height=saved.height;gravity=saved.gravity;x=saved.x;y=saved.y }
                }
                if(panelWidth>0) { next.width=panelWidth;next.height=WindowManager.LayoutParams.MATCH_PARENT;next.gravity=Gravity.TOP or Gravity.RIGHT;next.x=0;next.y=0;next.flags=next.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND;next.dimAmount=.18f }
                else { next.flags=(next.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()) or (saved.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND);next.dimAmount=saved.dimAmount }
                if(current.width!=next.width || current.height!=next.height || current.gravity!=next.gravity || current.x!=next.x || current.y!=next.y || current.flags!=next.flags || current.dimAmount!=next.dimAmount) a.window.attributes=next
            }
            // Anchored PopupWindows retain their own geometry. App selectors use unanchored choice dialogs.
            // Android DecorContext can wrap the Application rather than its Activity.
            // Dialogs carry the activity token; attached menus carry the host window token.
            val tokens=listOfNotNull((a.window.decorView.layoutParams as? WindowManager.LayoutParams)?.token,a.window.attributes.token,a.window.decorView.windowToken,a.window.decorView.applicationWindowToken).toSet()
            val types=setOf(WindowManager.LayoutParams.TYPE_APPLICATION,WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
            val dialogs=roots.filter { root ->
                val params=root.layoutParams as? WindowManager.LayoutParams
                root!==a.window.decorView && params!=null && params.type in types &&
                    (owner(root.context)===a || (params.token!=null && params.token in tokens))
            }
            for(root in dialogs) {
                val current=root.layoutParams as WindowManager.LayoutParams
                if(current.type !in setOf(WindowManager.LayoutParams.TYPE_APPLICATION,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)) continue
                val saved=original.getOrPut(root) { WindowManager.LayoutParams().apply { copyFrom(current) } }
                val next=WindowManager.LayoutParams().apply {
                    copyFrom(current)
                    if(panelWidth==0) { this.width=saved.width;height=saved.height;gravity=saved.gravity;x=saved.x;y=saved.y }
                }
                if(panelWidth>0) { next.width=panelWidth;next.height=WindowManager.LayoutParams.MATCH_PARENT;next.gravity=Gravity.TOP or Gravity.RIGHT;next.x=0;next.y=0 }
                if(current.width!=next.width || current.height!=next.height || current.gravity!=next.gravity || current.x!=next.x || current.y!=next.y)
                    runCatching { a.windowManager.updateViewLayout(root,next) }
            }
            val splitSetup=a.javaClass.simpleName=="CustomSourceActivity" && browserOpen(a.window.decorView)
            val reserve=if(panelWidth>0 && !panelActivity && !splitSetup && (dialogs.isNotEmpty() || panelHosts.any { it!==a })) panelWidth else 0
            if(reserved(a)!=reserve) { reserves[a]=reserve;ViewCompat.requestApplyInsets(a.findViewById<View>(android.R.id.content)) }
        }
    }
}
