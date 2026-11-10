package com.linfranca.ytdlpmobile

import android.content.Context

internal object MonitoringSettings {
    private fun prefs(c: Context)=c.getSharedPreferences("monitoring_options",Context.MODE_PRIVATE)
    fun migrate(c: Context) {
        if(prefs(c).getBoolean("interval_default_migrated",false)) return
        if(!backoff(c)) MonitoringStore.rules(c).filter { it.enabled }.forEach { r ->
            val due=MonitoringPolicy.next(System.currentTimeMillis(),r.interval,0,r.zone,r.start,r.end,r.days)
            if(r.next>due) {
                MonitoringStore.mutate(c,r.id) { if(it.generation==r.generation) { it.next=due;it.customState.remove("regularCheckDue");it.customState.put("scheduleReason","selected interval / allowed timeframe") } }
                MonitoringScheduler.ensure(c,r.copy(next=due),true)
            }
        }
        prefs(c).edit().putBoolean("interval_default_migrated",true).apply()
    }
    fun backoff(c: Context)=prefs(c).getBoolean("failure_backoff",false)
    fun setBackoff(c: Context,enabled: Boolean) {
        prefs(c).edit().putBoolean("failure_backoff",enabled).apply()
        MonitoringStore.log(c,"Failure interval backoff changed; enabled=$enabled")
        // Replace old extended schedules when the user disables backoff.
        if(!enabled) MonitoringStore.rules(c).filter { it.enabled }.forEach { r ->
            val due=MonitoringPolicy.next(System.currentTimeMillis(),r.interval,0,r.zone,r.start,r.end,r.days)
            MonitoringStore.mutate(c,r.id) { if(it.generation==r.generation) { it.next=due;it.customState.remove("regularCheckDue");it.customState.put("scheduleReason","selected interval / allowed timeframe") } }
            MonitoringScheduler.ensure(c,r.copy(next=due),true)
        }
    }
}
