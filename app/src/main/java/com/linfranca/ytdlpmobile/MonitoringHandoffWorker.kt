package com.linfranca.ytdlpmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

/** WorkManager owns the foreground preparation lifetime until the existing media service acknowledges. */
class MonitoringHandoffWorker(c: Context,p: WorkerParameters): Worker(c,p) {
    companion object {
        fun enqueue(c: Context,transfer: String,lease: String) {
            WorkManager.getInstance(c).enqueueUniqueWork("monitor-handoff-$transfer-$lease",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MonitoringHandoffWorker>()
                    .setInputData(workDataOf("transfer" to transfer,"lease" to lease))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build())
        }
    }
    override fun doWork(): Result {
        val c=applicationContext
        val transfer=inputData.getString("transfer") ?: return Result.failure()
        val lease=inputData.getString("lease") ?: return Result.failure()
        if(!TransferQueue.owns(c,transfer,lease)) return Result.success()
        val job=TransferQueue.jobs(c).find { it.optString("id")==transfer } ?: return Result.success()
        val monitor=job.optString("monitor")
        val discovery=MonitoringStore.jobs(c).find { it.optString("id")==monitor }
        val rule=discovery?.let { MonitoringStore.get(c,it.optString("rule")) }
        if(discovery==null || discovery.optString("state")=="dismissed" || rule?.enabled!=true) {
            TransferQueue.complete(c,transfer,"Monitoring source unavailable or paused","",false,lease)
            return Result.success()
        }
        MonitoringPowerDiagnostics.record(c,"media handoff; job=$monitor")
        // A saved cooldown concerns earlier work, not this attempt. Ask Android for a real decision.
        var foregroundReady=false
        try {
            val nm=c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("monitoring_checks","Monitoring checks",NotificationManager.IMPORTANCE_LOW))
            val notification=NotificationCompat.Builder(c,"monitoring_checks").setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Preparing monitored media").setContentText(rule.name)
                .setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            TransferQueue.preparationEvent(c,transfer,lease,"Actual foreground startup requested; saved cooldown does not suppress this attempt")
            setForegroundAsync(ForegroundInfo(1_000_000+(transfer.hashCode() and 0x3fffffff),notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)).get(15,TimeUnit.SECONDS)
            foregroundReady=true
            TransferQueue.preparationEvent(c,transfer,lease,"Android accepted foreground preparation",foregroundReady=true)
            if(isStopped || !TransferQueue.owns(c,transfer,lease)) return Result.success()
            MonitoringStore.log(c,"managed foreground handoff ready; job=$monitor; work=$id")
            MonitoringCoordinator.launchQueued(c,monitor,transfer,lease)
            val deadline=SystemClock.elapsedRealtime()+120_000
            while(!isStopped && TransferQueue.owns(c,transfer,lease)) {
                val current=TransferQueue.jobs(c).find { it.optString("id")==transfer }
                if(current?.optBoolean("engine_started")==true) {
                    MonitoringStore.log(c,"media service acknowledged; job=$monitor; work=$id")
                    return Result.success()
                }
                if(SystemClock.elapsedRealtime()>=deadline) {
                    TransferQueue.deferMonitoring(c,transfer,lease,"Media preparation timed out")
                    break
                }
                Thread.sleep(200)
            }
        } catch(e: Exception) {
            if(!isStopped && TransferQueue.owns(c,transfer,lease)) {
                TransferQueue.preparationEvent(c,transfer,lease,"Actual preparation failed; category=${MonitoringFailure.kind(MonitoringStore.error(e))}; "+MonitoringStore.error(e))
                TransferQueue.deferMonitoring(c,transfer,lease,"Managed background handoff unavailable: "+MonitoringStore.error(e))
            }
        }
        if(foregroundReady) retainShortRetry(c,transfer)
        return Result.success()
    }
    /** Keep this foreground lifetime until the short retry obtains its own lifetime, within a bound. */
    private fun retainShortRetry(c: Context,transfer: String) {
        val job=TransferQueue.jobs(c).find { it.optString("id")==transfer } ?: return
        if(!job.optBoolean("live") || job.optString("state")!="held") return
        val wait=MonitoringStartPolicy.shortWait(job.optLong("retry_at"),System.currentTimeMillis())
        if(wait==0L) return
        val wake=c.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"ytdlpmobile:startup-retry")
        try {
            wake.acquire(wait+20_000)
            val readyAt=SystemClock.elapsedRealtime()+wait
            val end=readyAt+15_000
            var dispatched=false
            MonitoringStore.log(c,"short live startup retry retained in existing WorkManager foreground lifetime; transfer=$transfer; waitMs=$wait")
            while(!isStopped && SystemClock.elapsedRealtime()<end) {
                val current=TransferQueue.jobs(c).find { it.optString("id")==transfer } ?: break
                if(current.optString("state") !in setOf("held","queued","running")) break
                if(!dispatched && SystemClock.elapsedRealtime()>=readyAt) { TransferQueue.dispatch(c);dispatched=true }
                if(current.optBoolean("engine_started") || (current.optString("lease").isNotBlank() && current.optString("lease")==current.optString("preparation_foreground_lease"))) break
                Thread.sleep(200)
            }
        } catch(e: InterruptedException) { Thread.currentThread().interrupt() }
        catch(e: Exception) { MonitoringStore.log(c,"short startup lifetime unavailable; durable retry retained; ${MonitoringStore.error(e)}") }
        finally { if(wake.isHeld) wake.release() }
    }
    override fun onStopped() {
        val reason = if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else -512
        val transfer=inputData.getString("transfer").orEmpty()
        val lease=inputData.getString("lease").orEmpty()
        runCatching {
            MonitoringPowerDiagnostics.record(applicationContext,"media handoff stopped; transfer=$transfer",reason)
            TransferQueue.deferMonitoring(applicationContext,transfer,lease,"Android interrupted media preparation: "+MonitoringFailure.stop(reason))
        }
        super.onStopped()
    }
}

/** Delays are persisted in the queue as well as WorkManager; watchdog/reboot can restore them. */
class MonitoringMediaRetryWorker(c: Context,p: WorkerParameters): Worker(c,p) {
    companion object {
        fun schedule(c: Context,transfer: String,due: Long) {
            WorkManager.getInstance(c).enqueueUniqueWork("monitor-media-retry-$transfer",ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MonitoringMediaRetryWorker>()
                    .setInitialDelay((due-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            MonitoringWakeup.mediaRetry(c,transfer,due)
        }
    }
    override fun doWork(): Result {
        TransferQueue.initialize(applicationContext)
        TransferQueue.dispatch(applicationContext)
        return Result.success()
    }
}
