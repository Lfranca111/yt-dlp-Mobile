package com.linfranca.ytdlpmobile

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager

/** Logging only: deliberately separate from DeviceDiagnostics.snapshot, which appears on a page. */
internal object MonitoringPowerDiagnostics {
    fun networkUsable(c: Context): Boolean = runCatching {
        val cm=c.getSystemService(ConnectivityManager::class.java)
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    fun record(c: Context,event: String,stopReason: Int = -256) {
        runCatching {
            val pm=c.getSystemService(PowerManager::class.java)
            val am=c.getSystemService(ActivityManager::class.java)
            val cm=c.getSystemService(ConnectivityManager::class.java)
            val caps=cm.getNetworkCapabilities(cm.activeNetwork)
            val bucket=runCatching { c.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrDefault(-1)
            val signals=mutableListOf<String>()
            if(pm.isPowerSaveMode) signals.add("power saving enabled")
            if(pm.isDeviceIdleMode) signals.add("device idle/Doze")
            if(!pm.isIgnoringBatteryOptimizations(c.packageName)) signals.add("battery optimization enabled")
            if(am.isBackgroundRestricted) signals.add("app background restricted")
            if(bucket>=40) signals.add("infrequent/restricted standby bucket")
            val confirmed=when(stopReason) { 4->"system reports device-state restriction (specific cause unknown)"; 10->"system reports job quota exhausted"; 11->"system reports background restriction"; 12->"system reports standby restriction"; else->"none established" }
            val state="powerSave=${pm.isPowerSaveMode}; idle=${pm.isDeviceIdleMode}; interactive=${pm.isInteractive}; batteryExempt=${pm.isIgnoringBatteryOptimizations(c.packageName)}; backgroundRestricted=${am.isBackgroundRestricted}; standbyBucket=$bucket; restrictBackgroundStatus=${cm.restrictBackgroundStatus}; networkValidated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}; vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}; privateDnsActive=${cm.getLinkProperties(cm.activeNetwork)?.isPrivateDnsActive}"
            MonitoringStore.log(c,"power/network context; event=$event; $state; confirmed=$confirmed; possibleContributors=${signals.joinToString().ifBlank { "none observed" }}; Samsung sleeping/deep-sleep settings not directly observable; snapshot alone does not establish causation")
            val prefs=c.getSharedPreferences("monitoring_power_diagnostics",Context.MODE_PRIVATE)
            val previous=prefs.getString("last",null)
            if(previous!=state) {
                MonitoringStore.log(c,"power/network conditions changed; previous=${previous ?: "not recorded"}; current=$state")
                val exempt=pm.isIgnoringBatteryOptimizations(c.packageName)
                val becameExempt=prefs.contains("exempt") && !prefs.getBoolean("exempt",false) && exempt
                prefs.edit().putString("last",state).putBoolean("exempt",exempt).apply()
                val restrictionsEased=becameExempt ||
                    (previous?.contains("powerSave=true")==true && !pm.isPowerSaveMode) ||
                    (previous?.contains("backgroundRestricted=true")==true && !am.isBackgroundRestricted)
                if(restrictionsEased) android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { TransferQueue.retryBlockedMonitoring(c.applicationContext) } }
            }
        }.onFailure { runCatching { MonitoringStore.log(c,"extended power diagnostics unavailable; ${it.javaClass.simpleName}") } }
    }
}
