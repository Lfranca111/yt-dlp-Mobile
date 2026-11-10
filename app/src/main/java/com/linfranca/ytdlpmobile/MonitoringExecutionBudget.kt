package com.linfranca.ytdlpmobile

import android.content.Context

/** A cooldown is not an Android quota reset. WorkManager remains available for ordinary checks. */
internal object MonitoringExecutionBudget {
    private fun prefs(c: Context)=c.getSharedPreferences("monitoring_execution_budget",Context.MODE_PRIVATE)
    fun notBefore(c: Context): Long = prefs(c).getLong("retry_after",0).takeIf { it>System.currentTimeMillis() } ?: 0
    fun exhausted(c: Context, reason: String) {
        prefs(c).edit().putLong("retry_after",System.currentTimeMillis()+15*60_000).apply()
        MonitoringStore.log(c,"Android foreground-service time budget unavailable; exact foreground attempts cooled down for 15 minutes; WorkManager fallback retained; battery exemption does not remove this limit; reason=$reason")
    }
    fun visible(c: Context) {
        if(prefs(c).contains("retry_after")) {
            prefs(c).edit().remove("retry_after").apply()
            MonitoringStore.log(c,"visible app session; foreground eligibility will be rechecked; no quota reset inferred from saved settings")
            MonitoringWakeup.refresh(c)
        }
    }
}
