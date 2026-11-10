package com.linfranca.ytdlpmobile

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import java.util.WeakHashMap

/** Startup dialogs only; the existing screens and their controls remain unchanged. */
internal object MonitoringReadiness {
    private class Session {
        val visited=mutableSetOf<String>()
        var showing=false
        var stopped=false
    }
    private val sessions=WeakHashMap<Activity,Session>()
    private val main=Handler(Looper.getMainLooper())
    fun resumed(a: Activity) {
        MonitoringPowerDiagnostics.record(a,"startup readiness rechecked")
        MonitoringWakeup.refresh(a)
        MonitoringStore.log(a,"startup access checked; exactAlarmAccess=${MonitoringWakeup.exactAccess(a)}; notificationsEnabled=${NotificationManagerCompat.from(a).areNotificationsEnabled()}; Samsung settings require manual verification")
        val session=sessions.getOrPut(a) { Session() }
        main.post { advance(a,session) }
    }
    private fun advance(a: Activity,s: Session) {
        if(a.isFinishing || a.isDestroyed || s.stopped || s.showing) return
        val power=a.getSystemService(PowerManager::class.java)
        val network=a.getSystemService(ConnectivityManager::class.java)
        val prefs=a.getSharedPreferences("monitoring_readiness_120",Context.MODE_PRIVATE)
        data class Step(val id: String,val needed: Boolean,val title: String,val text: String,val intent: Intent?)
        val app=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${a.packageName}"))
        val steps=listOf(
            Step("exact",!MonitoringWakeup.exactAccess(a),"Allow scheduled wake-ups",
                "Allow Alarms & reminders so your automatic downloads and time-sensitive live checks can wake the app at their saved times. Android may still limit execution. Ordinary monitoring keeps its WorkManager fallback.",
                if (Build.VERSION.SDK_INT >= 31)
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${a.packageName}")) else null),
            Step("battery",!power.isIgnoringBatteryOptimizations(a.packageName),"Allow background monitoring",
                "Allow this app to run without battery optimization for checks while the screen is off. Android will ask you to confirm. This does not remove every Android or Samsung restriction.",
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:${a.packageName}"))),
            Step("restricted",a.getSystemService(ActivityManager::class.java).isBackgroundRestricted,"Background use is restricted",
                "In the app's Battery settings, allow background use and choose Unrestricted if your phone offers it. Return here to check again.",app),
            Step("power",power.isPowerSaveMode,"Power saving is enabled",
                "Power saving may delay checks or network access. Review Battery settings and turn it off when reliable timed monitoring is needed. This is a possible contributor, not proof of the cause of an earlier delay.",Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
            Step("data",network.restrictBackgroundStatus==ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,"Data Saver restricts background data",
                "Allow unrestricted data for this app if you want monitoring over mobile data while Data Saver is on. Wi-Fi and other restrictions are separate.",Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,Uri.parse("package:${a.packageName}"))),
            Step("notifications",!NotificationManagerCompat.from(a).areNotificationsEnabled(),"Enable monitoring notifications",
                "Notifications show discoveries, live priority alerts, ongoing work, and blocked actions. Disabling notifications does not by itself prove Android blocked a service.",Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,a.packageName)),
            Step("samsung",Build.MANUFACTURER.equals("samsung",true) && !prefs.getBoolean("samsungConfirmed",false),"Check Samsung sleeping-app settings",
                "In Settings → Battery → Background usage limits, remove this app from Sleeping apps and Deep sleeping apps, then add it to Never sleeping apps. Names vary by phone. Android does not expose these lists for this app to verify; confirm only after checking them yourself.",Intent(Settings.ACTION_SETTINGS))
        )
        val step=steps.firstOrNull { it.needed && it.id !in s.visited } ?: return
        s.visited.add(step.id)
        s.showing=true
        val dialog=AlertDialog.Builder(a).setTitle(step.title).setMessage(step.text)
            .setPositiveButton("Open settings") { _,_ ->
                try { a.startActivity(step.intent) }
                catch(e: Exception) {
                    MonitoringStore.log(a,"readiness settings page unavailable; item=${step.id}; ${e.javaClass.simpleName}")
                    runCatching { a.startActivity(app) }
                }
            }
            .setNegativeButton("Later") { _,_ -> s.stopped=true }
        if(step.id=="samsung") dialog.setNeutralButton("I checked these settings") { _,_ ->
            prefs.edit().putBoolean("samsungConfirmed",true).apply()
            MonitoringStore.log(a,"Samsung sleeping-app settings manually confirmed by user; not verified by Android API")
            main.post { advance(a,s) }
        }
        dialog.setOnCancelListener { s.stopped=true }
        dialog.create().apply { setOnDismissListener { s.showing=false };show() }
    }
}
