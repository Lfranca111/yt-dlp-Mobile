package com.linfranca.ytdlpmobile

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Presentation only. Existing view IDs, text, listeners and task state are owned by the screens. */
internal object VisualPolish {
    private var installed = false
    private val sessions = WeakHashMap<Activity, ScreenStyle>()

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity.javaClass.name.startsWith("com.linfranca.ytdlpmobile.")) {
                    sessions[activity]?.stop()
                    val root = activity.findViewById<View>(android.R.id.content) ?: return
                    sessions[activity] = ScreenStyle(root, activity.javaClass.simpleName).also { it.start() }
                }
            }
            override fun onActivityStopped(activity: Activity) { sessions.remove(activity)?.stop() }
            override fun onActivityDestroyed(activity: Activity) { sessions.remove(activity)?.stop() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    // Prepare new monitoring views synchronously: no unstyled frame before global layout.
    fun prepareMonitoring(root: View) = ScreenStyle(root,"MainActivity").prepare()

    fun markMonitoring(root: View) = root.setTag(R.id.polish_monitor_root, true)

    private fun View.color(id: Int) = ContextCompat.getColor(context, id)
    private fun View.dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()
    private fun View.name() = if (id == View.NO_ID) "" else
        runCatching { resources.getResourceEntryName(id) }.getOrDefault("")
    private fun View.shape(fill: Int, outline: Int? = null, radius: Int = 14) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (outline != null) setStroke(dp(1), outline)
        }

    fun tab(button: MaterialButton, selected: Boolean) = with(button) {
        cornerRadius = dp(14)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(color(if (selected) R.color.polish_violet else R.color.polish_edge))
        backgroundTintList = ColorStateList.valueOf(color(if (selected) R.color.polish_tonal else R.color.polish_panel))
        setTextColor(color(if (selected) R.color.polish_violet else R.color.polish_text))
    }

    fun guideHeading(button: MaterialButton) = with(button) {
        cornerRadius = dp(14)
        backgroundTintList = ColorStateList.valueOf(color(R.color.polish_tonal))
        setTextColor(color(R.color.polish_violet))
    }

    fun guideBody(view: TextView) = with(view) {
        background = shape(color(R.color.polish_panel), color(R.color.polish_edge))
        setTextColor(color(R.color.polish_text))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun primary(button: MaterialButton) = with(button) {
        // Keep MaterialButton's own drawable for shape, ripple and accessibility states.
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        cornerRadius = dp(22)
        strokeWidth = dp(1)
        strokeColor = ColorStateList(states,
            intArrayOf(color(R.color.polish_edge), 0xff9b70e2.toInt()))
        backgroundTintList = ColorStateList(states,
            intArrayOf(color(R.color.polish_panel), 0xff713aca.toInt()))
        setTextColor(ColorStateList(states, intArrayOf(color(R.color.polish_muted), Color.WHITE)))
    }

    fun progress(view: ProgressBar, value: Int, active: Boolean, unknown: Boolean, waiting: Boolean = false) {
        val target = value.coerceIn(0, view.max)
        if (view.isIndeterminate != unknown) view.isIndeterminate = unknown
        if (!unknown && view.progress != target)
            view.setProgress(target, active && !waiting && view.isShown && ValueAnimator.areAnimatorsEnabled())
        val accent = view.color(if (waiting) R.color.polish_amber else R.color.polish_violet)
        val tint = ColorStateList.valueOf(accent)
        val fillTint = if (view.getTag(R.id.polish_gradient_progress) == true && !waiting) null else tint
        if (view.progressTintList != fillTint) view.progressTintList = fillTint
        if (view.indeterminateTintList != tint) view.indeterminateTintList = tint
    }

    private fun state(text: CharSequence): String {
        val s = text.toString().trim().lowercase(java.util.Locale.ROOT)
        val prefix = s.substringBefore(' ').substringBefore(':')
        return when {
            s.startsWith("saved with") || s.contains("could not be verified") -> "warning"
            prefix in setOf("failed", "error", "attention", "interrupted") || s.startsWith("download failed") -> "failed"
            prefix in setOf("queued", "waiting", "retrying", "held", "stalled") ||
                s.startsWith("http 429 recovery") || s.startsWith("recording stalled") -> "waiting"
            s.startsWith("recording saved") || s.startsWith("partial recording saved") -> "saved"
            prefix == "recording" -> "recording"
            prefix in setOf("saved", "completed", "finished") -> "saved"
            prefix in setOf("running", "reserved", "stopping", "downloading", "checking", "saving", "finalizing", "preparing", "starting") -> "active"
            else -> "idle"
        }
    }

    fun preparing(status: String) = listOf("Starting", "Checking", "Preparing", "Saving", "Finalizing")
        .any { status.startsWith(it, ignoreCase = true) }

    fun waiting(status: String) = state(status) == "waiting"

    private fun stateColor(view: View, kind: String) = view.color(when (kind) {
        "failed" -> R.color.polish_error
        "waiting", "warning" -> R.color.polish_amber
        "saved" -> R.color.polish_saved
        "recording", "active" -> R.color.polish_violet
        else -> R.color.polish_text
    })

    fun status(view: TextView, text: String, recording: Boolean = false) {
        val kind = state(text)
        val previous = view.getTag(R.id.polish_motion_state) as? String
        val display = if (recording && kind !in setOf("failed", "saved", "warning", "waiting") &&
            !preparing(text)) "recording" else kind
        if (previous != display) {
            view.setTag(R.id.polish_motion_state, display)
            view.animate().cancel()
            view.alpha = 1f
            view.setTextColor(stateColor(view, display))
            if (display == "saved" && previous in setOf("active", "recording", "waiting") &&
                view.isShown && ValueAnimator.areAnimatorsEnabled()) {
                view.alpha = 0.68f
                view.animate().alpha(1f).setDuration(420).start()
            }
        }
        if (display == "recording") sessions.values.firstOrNull { it.contains(view) }?.pulse(view)
        else sessions.values.firstOrNull { it.contains(view) }?.unpulse(view)
    }

    // The queue owns this presentation state for the lifetime of its activity.
    // Job titles and display labels can change or repeat.
    class QueueRows {
        private val recentById = LinkedHashMap<String, String>(32, 0.75f, true)

        fun style(view: TextView, jobId: String, jobState: String) {
            val kind = when (jobState) {
                "queued", "waiting", "held" -> "waiting"
                "running", "reserved", "finalizing", "stopping" -> "active"
                "saved", "completed" -> "saved"
                "failed", "attention", "interrupted" -> "failed"
                "canceled" -> "idle"
                else -> return
            }
            val previous = recentById.put(jobId, kind)
            if (recentById.size > 160) recentById.remove(recentById.keys.first())
            if (view.getTag(R.id.polish_motion_state) == kind) return
            view.setTag(R.id.polish_motion_state, kind)
            view.background = view.shape(view.color(R.color.polish_panel), stateColor(view, kind), 16)
            view.setPaddingRelative(view.dp(12), view.dp(10), view.dp(12), view.dp(10))
            view.setTextColor(view.color(R.color.polish_text))
            if (kind == "saved" && (previous == "active" || previous == "waiting") &&
                view.isShown && ValueAnimator.areAnimatorsEnabled()) {
                view.alpha = 0.68f
                view.animate().alpha(1f).setDuration(420).start()
            }
        }
    }

    private class ScreenStyle(private val root: View, private val screen: String) {
        private val pulses = WeakHashMap<TextView, ValueAnimator>()
        private val listener = ViewTreeObserver.OnGlobalLayoutListener { walk(root) }

        fun prepare() {
            walk(root,true)
            if(root is LinearLayout) monitorCard(root)
        }

        fun start() {
            walk(root)
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }

        fun stop() {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            pulses.keys.toList().forEach(::unpulse)
        }

        fun contains(view: View) = view.rootView === root.rootView

        fun pulse(view: TextView) {
            if (!view.isShown || !view.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
                unpulse(view)
                return
            }
            if (pulses.containsKey(view)) return
            pulses[view] = ValueAnimator.ofFloat(1f, 0.72f).apply {
                duration = 1050
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    if (!view.isShown || !view.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) unpulse(view)
                    else view.alpha = it.animatedValue as Float
                }
                start()
            }
        }

        fun unpulse(view: TextView) {
            pulses.remove(view)?.let {
                it.cancel()
                view.alpha = 1f
            }
        }

        private fun walk(view: View, inMonitoring: Boolean = false) {
            val monitoring = inMonitoring || view.getTag(R.id.polish_monitor_root) == true
            if (view is WebView) return
            if (view.getTag(R.id.polish_view_initialized) != true) {
                view.setTag(R.id.polish_view_initialized, true)
                style(view)
                if (monitoring && view is LinearLayout) monitorCard(view)
            }
            if (view is TextView && view !is Button && view !is EditText) {
                if (monitoring && view.text.startsWith("Active monitoring task\n"))
                    status(view, view.text.toString().substringAfter('\n'))
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), monitoring)
        }

        private fun monitorCard(view: LinearLayout) = with(view) {
            val title = getChildAt(0) as? TextView ?: return@with
            if (orientation != LinearLayout.VERTICAL || title is Button || title is EditText ||
                title.textSize < resources.displayMetrics.scaledDensity * 20 ||
                childCount < 2 || (1 until childCount).none { getChildAt(it) is Button }) return@with
            val pad = intArrayOf(paddingStart, paddingTop, paddingEnd, paddingBottom)
            background = shape(color(R.color.polish_panel), color(R.color.polish_edge), 18)
            setPaddingRelative(maxOf(dp(12), pad[0]), maxOf(dp(10), pad[1]),
                maxOf(dp(12), pad[2]), maxOf(dp(10), pad[3]))
            title.setTextColor(color(R.color.polish_violet))
        }

        private fun style(view: View): Unit = with(view) {
            val name = name()
            when (this) {
                is TextInputLayout -> {
                    val radius = dp(14).toFloat()
                    if (boxBackgroundMode != TextInputLayout.BOX_BACKGROUND_NONE) {
                        setBoxCornerRadii(radius, radius, radius, radius)
                        boxBackgroundColor = color(R.color.polish_panel)
                        setBoxStrokeColorStateList(ColorStateList(arrayOf(
                            intArrayOf(-android.R.attr.state_enabled),
                            intArrayOf(android.R.attr.state_focused), intArrayOf()),
                            intArrayOf(color(R.color.polish_muted), color(R.color.polish_violet), color(R.color.polish_edge))))
                    }
                }
                is AutoCompleteTextView -> setDropDownBackgroundDrawable(shape(color(R.color.polish_panel), color(R.color.polish_edge)))
                is EditText -> if (parent !is TextInputLayout && (parent as? View)?.parent !is TextInputLayout) {
                    val pad = intArrayOf(paddingStart, paddingTop, paddingEnd, paddingBottom)
                    setBackgroundResource(R.drawable.polish_field)
                    backgroundTintList = null
                    setPaddingRelative(maxOf(dp(10), pad[0]), maxOf(dp(6), pad[1]),
                        maxOf(dp(10), pad[2]), maxOf(dp(6), pad[3]))
                }
                is MaterialButton -> when (name) {
                    "downloadButton", "collectionDownloadButton" -> primary(this)
                    "useMediaButton" -> {
                        primary(this)
                        (parent as? LinearLayout)?.let { row ->
                            row.background = row.shape(row.color(R.color.polish_panel), row.color(R.color.polish_edge))
                        }
                    }
                    "browserAssistButton", "recordStreamButton" -> {
                        cornerRadius = dp(20)
                        backgroundTintList = ColorStateList.valueOf(color(R.color.polish_tonal))
                        strokeColor = ColorStateList.valueOf(color(R.color.polish_edge))
                        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                            intArrayOf(color(R.color.polish_muted), color(R.color.polish_violet))))
                    }
                    else -> {
                        if (screen == "CollectionConvertActivity" && text.toString() == "Make PDF") primary(this)
                        else if (background != null && contentDescription?.contains("tap to open") != true)
                            cornerRadius = dp(14)
                    }
                }
                is Button -> if (javaClass == Button::class.java || javaClass.name == "androidx.appcompat.widget.AppCompatButton") {
                    val pad = intArrayOf(paddingStart, paddingTop, paddingEnd, paddingBottom)
                    val fill = StateListDrawable().apply {
                        addState(intArrayOf(-android.R.attr.state_enabled),
                            shape(color(R.color.polish_panel), color(R.color.polish_edge)))
                        addState(intArrayOf(), shape(color(R.color.polish_tonal), color(R.color.polish_edge)))
                    }
                    background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(color(R.color.polish_edge)),
                        fill, shape(Color.WHITE)), dp(2), dp(4), dp(2), dp(4))
                    backgroundTintList = null
                    setPaddingRelative(pad[0], pad[1], pad[2], pad[3])
                    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                        intArrayOf(color(R.color.polish_muted), color(R.color.polish_text))))
                }
                is ProgressBar -> {
                    val tint = ColorStateList.valueOf(color(R.color.polish_violet))
                    val horizontal = (progressDrawable as? LayerDrawable)?.findDrawableByLayerId(android.R.id.progress) != null
                    if (horizontal) {
                        progressDrawable = ContextCompat.getDrawable(context, R.drawable.polish_progress)
                        setTag(R.id.polish_gradient_progress, true)
                    }
                    progressTintList = if (horizontal) null else tint
                    indeterminateTintList = tint
                    progressBackgroundTintList = ColorStateList.valueOf(color(R.color.polish_tonal))
                }
                is ScrollView, is NestedScrollView -> {
                    isVerticalScrollBarEnabled = true
                    scrollBarSize = dp(3)
                    setVerticalScrollbarThumbDrawable(shape(color(R.color.polish_violet), radius = 3).apply {
                        setSize(dp(3), dp(16))
                    })
                    isScrollbarFadingEnabled = true
                    scrollBarDefaultDelayBeforeFade = 600
                    scrollBarFadeDuration = 250
                    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                }
                is ImageView -> if (name.contains("logo", true) || name.contains("launcher", true)) halo(this)
                is TextView -> if (this !is Button) when {
                    name in setOf("logText","collectionLogText") -> {
                        background = shape(color(R.color.polish_panel), color(R.color.polish_edge))
                        typeface = Typeface.MONOSPACE
                        setTextColor(color(R.color.polish_text))
                    }
                    name == "browserStatus" -> {
                        background = shape(color(R.color.polish_panel), color(R.color.polish_edge), 12)
                        setTextColor(color(R.color.polish_text))
                    }
                    text.toString() in setOf("yt-dlp Mobile", "Collections", "Monitoring download",
                        "Queue & history", "Instructions and troubleshooting") -> {
                        halo(this)
                        setTextColor(color(R.color.polish_violet))
                    }
                }
            }
            Unit
        }

        private fun halo(view: View) {
            val glow = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setColors(intArrayOf(0x397d42d1, 0x007d42d1))
                gradientRadius = view.dp(100).toFloat()
            }
            // Draw behind content only; no new view or touch target.
            if (view.background == null) view.background = glow
        }
    }
}
