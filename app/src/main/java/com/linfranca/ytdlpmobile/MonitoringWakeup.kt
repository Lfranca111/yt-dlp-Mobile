package com.linfranca.ytdlpmobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

/** Best-effort additional wakeup for monitoring rules; WorkManager remains the fallback. */
internal object MonitoringWakeup {
    private fun pending(c: Context, exact: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        c, if(exact) 19020 else 19018, Intent(c, MonitoringWakeupReceiver::class.java).putExtra("exact",exact),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun refresh(c: Context, exclude: String = "", futureOnly: Boolean = false, notBefore: Long = 0, approximateOnly: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            val rules=MonitoringStore.rules(c).filter { it.id!=exclude && it.enabled && it.next>0 && (!futureOnly || it.next>now) }
            val precise=rules.filter { exactRule(it) }.map { it.next }.minOrNull()
            val ordinary=(rules.filterNot { exactRule(it) }.map { it.next } + ComicStore.rules(c).filter {
                it.optBoolean("enabled",true) && it.optLong("next")>0 && (!futureOnly || it.optLong("next")>now)
            }.map { it.optLong("next") }).minOrNull()
            synchronized(this) {
            val alarm=c.getSystemService(AlarmManager::class.java)
            val access=exactAccess(c)
            if(!approximateOnly) alarm.cancel(pending(c,true))
            if(!approximateOnly && precise!=null && access) {
                try {
                    val at=maxOf(precise,now+1000,notBefore,MonitoringExecutionBudget.notBefore(c))
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,true))
                    MonitoringStore.log(c,"monitoring exact wakeup scheduled; due=$precise; alarmAt=$at; foregroundCooldownUntil=${MonitoringExecutionBudget.notBefore(c)}; user-requested automatic/time-sensitive sources")
                } catch(e: SecurityException) {
                    // Access may be revoked between the capability check and registration.
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,maxOf(precise,now+1000,notBefore),pending(c))
                    MonitoringStore.log(c,"exact access changed during scheduling; approximate fallback; due=$precise")
                    return
                }
            }
            val approximate=listOfNotNull(ordinary,precise?.takeUnless { access }).minOrNull()
            if(approximate==null) alarm.cancel(pending(c)) else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,maxOf(approximate,now+1000,notBefore),pending(c))
                MonitoringStore.log(c,"monitoring approximate wakeup scheduled; due=$approximate; exactAccess=$access; WorkManager fallback active")
            }
            }
        } catch (e: Exception) {
            MonitoringStore.log(c, "monitoring wakeup scheduling failed; WorkManager remains active; ${MonitoringStore.error(e)}")
        }
    }

    /** Separate durable alarm identity per queued preparation; no queue locks during rule planning. */
    fun mediaRetry(c: Context,transfer: String,due: Long) {
        try {
            val exact=exactAccess(c)
            val intent=Intent(c,MonitoringWakeupReceiver::class.java)
                .setData(android.net.Uri.parse("ytdlp-monitor-retry:$transfer")).putExtra("exact",exact)
            val token=PendingIntent.getBroadcast(c,19021,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val alarm=c.getSystemService(AlarmManager::class.java)
            val at=maxOf(due,System.currentTimeMillis()+1000)
            if(exact) {
                try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,token) }
                catch(e: SecurityException) {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,PendingIntent.getBroadcast(c,19021,
                        intent.putExtra("exact",false),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,token)
            MonitoringStore.log(c,"media retry wakeup scheduled; transfer=$transfer; due=$due; exactAccess=$exact")
        } catch(e: Exception) { MonitoringStore.log(c,"media retry alarm unavailable; WorkManager fallback retained; ${MonitoringStore.error(e)}") }
    }

    fun exactAccess(c: Context): Boolean = android.os.Build.VERSION.SDK_INT<31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    // The saved automatic action is the user's request; ordinary notification-only polling stays approximate.
    fun exactRule(r: MonitoringRule): Boolean = r.automatic || r.mode in setOf("live","both")

    fun enqueueDue(c: Context,rearmExact: Boolean = false) {
        val now = System.currentTimeMillis()
        MonitoringStore.rules(c).filter {
            it.enabled &&  it.next > 0 && it.next <= now
        }.forEach { rule ->
            try {
                val builder = OneTimeWorkRequestBuilder<MonitoringWorker>()
                    .setInputData(workDataOf("rule" to rule.id, "generation" to rule.generation, "due" to rule.next))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                if(android.os.Build.VERSION.SDK_INT>=31 && rule.mode in setOf("live","both")) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                val request=builder.build()
                WorkManager.getInstance(c).enqueueUniqueWork("monitor-wakeup-${rule.id}", ExistingWorkPolicy.KEEP, request)
                MonitoringStore.log(c, "monitoring wakeup enqueued; rule=${rule.id}; due=${rule.next}; actual=$now; work=${request.id}")
            } catch (e: Exception) {
                MonitoringStore.log(c, "monitoring wakeup enqueue failed; rule=${rule.id}; ${MonitoringStore.error(e)}")
            }
        }
        ComicStore.rules(c).filter { it.optBoolean("enabled",true) && it.optLong("next")>0 && it.optLong("next")<=now }.forEach { rule ->
            try {
                val request=OneTimeWorkRequestBuilder<ComicMonitorWorker>()
                    .setInputData(workDataOf("rule" to rule.getString("id"),"generation" to rule.optLong("generation"),"due" to rule.optLong("next")))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
                WorkManager.getInstance(c).enqueueUniqueWork("comic-wakeup-${rule.getString("id")}",ExistingWorkPolicy.KEEP,request)
                MonitoringStore.log(c,"comic wakeup enqueued; rule=${rule.getString("id")}; due=${rule.optLong("next")}; actual=$now")
            } catch(e: Exception) { MonitoringStore.log(c,"comic wakeup enqueue failed; ${MonitoringStore.error(e)}") }
        }
        // Avoid repeatedly waking for overdue work already handed to WorkManager.
        refresh(c, futureOnly = true, approximateOnly = !rearmExact)
    }
}

class MonitoringWakeupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app=context.applicationContext
        // Start synchronously inside the exact alarm's temporary foreground-start exemption.
        if(intent.getBooleanExtra("exact",false) && MonitoringExecutionBudget.notBefore(app)==0L) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(app,Intent(app,MonitoringAlarmService::class.java))
                return
            } catch(e: Exception) {
                MonitoringStore.log(app,"exact wakeup service blocked; baseline and discoveries retained; ${MonitoringStore.error(e)}")
                MonitoringPowerDiagnostics.record(app,"exact foreground start blocked")
            }
        }
        val pending=goAsync()
        Thread {
            try {
                MonitoringStore.log(app,"monitoring wakeup received; exact=${intent.getBooleanExtra("exact",false)}; actual=${System.currentTimeMillis()}")
                MonitoringWakeup.enqueueDue(app,rearmExact=intent.getBooleanExtra("exact",false))
                TransferQueue.dispatch(app)
            } catch(e: Exception) { MonitoringStore.log(app,"monitoring wakeup fallback failed; ${MonitoringStore.error(e)}") }
            finally { pending.finish() }
        }.start()
    }
}

class MonitoringAlarmAccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT < 31 ||
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        // Re-register alarms after access is granted; never start media from this permission broadcast.
        MonitoringWakeup.refresh(context.applicationContext)
    }
}
