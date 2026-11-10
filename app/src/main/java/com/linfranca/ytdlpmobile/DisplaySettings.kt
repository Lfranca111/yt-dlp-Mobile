package com.linfranca.ytdlpmobile

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.WeakHashMap

internal object DisplaySettings {
    private val activities = WeakHashMap<Activity, Boolean>()
    private fun preferences(c: Context) = c.getSharedPreferences("appearance_display", Context.MODE_PRIVATE)
    fun theme(c: Context) = preferences(c).getString("theme", "dark").orEmpty()
    fun orientation(c: Context) = preferences(c).getString("orientation", "android").orEmpty()
    fun initialize(app: Application) {
        AppCompatDelegate.setDefaultNightMode(nightMode(theme(app)))
    }
    private fun nightMode(value: String) = when (value) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "device" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }
    fun setTheme(c: Context, value: String) {
        require(value in setOf("dark", "light", "device"))
        preferences(c).edit().putString("theme", value).apply()
        AppCompatDelegate.setDefaultNightMode(nightMode(value))
        activities.keys.toList().forEach { a ->
            a.window.decorView.post { if (!a.isDestroyed) refresh(a) }
        }
    }
    fun setOrientation(c: Context, value: String) {
        require(value in setOf("android", "portrait", "landscape"))
        preferences(c).edit().putString("orientation", value).apply()
        activities.keys.toList().forEach(::applyOrientation)
    }
    fun attach(activity: Activity) {
        activities[activity] = true
        applyOrientation(activity)
    }
    fun detach(activity: Activity) { activities.remove(activity) }
    private fun applyOrientation(activity: Activity) {
        val requested = when (orientation(activity)) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_USER
        }
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
    }
    fun refresh(activity: Activity) {
        if (activity.isDestroyed) return
        ScreenInsets.apply(activity)
        DisplayPalette.refresh(activity)
        activity.findViewById<android.view.View>(android.R.id.content)?.requestLayout()
    }
    fun dark(c: Context) = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
