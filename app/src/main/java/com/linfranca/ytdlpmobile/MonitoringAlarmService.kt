package com.linfranca.ytdlpmobile

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded foreground owner created directly by a user-requested exact alarm. */
class MonitoringAlarmService : Service() {
    companion object {
        private val lock=Any()
        private var owner: MonitoringAlarmService?=null
        /** Hold the existing foreground owner across a short retry, without extending its five-minute limit. */
        fun shortRetry(transfer: String,due: Long): Boolean = synchronized(lock) {
            val service=owner ?: return@synchronized false
            val wait=MonitoringStartPolicy.shortWait(due,System.currentTimeMillis())
            if(service.ended || wait==0L || service.expiresAt-SystemClock.elapsedRealtime()<wait+2000 || service.retries.containsKey(transfer)) return@synchronized false
            service.pending++
            val callback=Runnable {
                synchronized(lock) { service.retries.remove(transfer) }
                try { if(!service.ended) TransferQueue.dispatch(service) }
                finally { synchronized(lock) { service.pending--;if(service.pending==0) service.main.post { if(synchronized(lock) { service.pending==0 }) service.finishRun("Short retry owner drained") } } }
            }
            service.retries[transfer]=callback
            service.main.postDelayed(callback,wait)
            MonitoringStore.log(service,"short live startup retry retained in exact foreground lifetime; transfer=$transfer; due=$due")
            true
        }
        fun handoff(transfer: String,lease: String): Boolean = synchronized(lock) {
            val service=owner ?: return@synchronized false
            service.leases[transfer]=lease
            service.submit { try { service.prepare(transfer,lease) } finally { synchronized(lock) { service.leases.remove(transfer) } } }
        }
    }
    private val executor=Executors.newFixedThreadPool(2)
    private val main=Handler(Looper.getMainLooper())
    private val leases=mutableMapOf<String,String>()
    private var pending=0
    private val retries=mutableMapOf<String,Runnable>()
    private var expiresAt=0L
    @Volatile private var ended=false
    private var wake: PowerManager.WakeLock?=null
    private val deadline=Runnable { finishRun("Monitoring foreground session reached its five-minute limit") }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("monitoring_checks","Monitoring checks",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,19020,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification=NotificationCompat.Builder(this,"monitoring_checks").setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Checking monitored sources").setContentText("Running your scheduled checks")
                .setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).build()
            startForeground(21020,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ytdlpmobile:monitoring-check").apply { acquire(310_000) }
            synchronized(lock) { owner=this }
            expiresAt=SystemClock.elapsedRealtime()+300_000
            main.postDelayed(deadline,300_000)
            MonitoringPowerDiagnostics.record(this,"exact alarm foreground execution started")
        } catch(e: Exception) {
            if(MonitoringFailure.kind(MonitoringStore.error(e))=="quota") MonitoringExecutionBudget.exhausted(this,MonitoringStore.error(e))
            MonitoringStore.log(this,"exact foreground promotion failed; ${MonitoringStore.error(e)}")
            MonitoringWakeup.enqueueDue(this,rearmExact=true)
            finishRun("Foreground promotion denied")
        }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        submit {
            val now=System.currentTimeMillis()
            val rules=MonitoringStore.rules(this).filter { it.enabled && it.next>0 && it.next<=now && MonitoringWakeup.exactRule(it) }
            for(rule in rules) {
                if(ended) break
                MonitoringStore.log(this,"exact check execution; rule=${rule.id}; scheduled=${rule.next}; actual=${System.currentTimeMillis()}")
                val result=MonitoringCheckRun(applicationContext,workDataOf("rule" to rule.id,"generation" to rule.generation,"due" to rule.next),
                    UUID.randomUUID(),0,{ ended },{ /* Already owns a foreground lifetime. */ }).run()
                MonitoringStore.log(this,"exact check returned; rule=${rule.id}; result=${result.javaClass.simpleName}")
            }
            // Dispatch on the main thread before retiring the owner so a newly discovered item can
            // join this same managed lifetime instead of waiting for another WorkManager slot.
            val drained=java.util.concurrent.CountDownLatch(1)
            main.post { try { TransferQueue.dispatch(this) } finally { drained.countDown() } }
            drained.await(5,TimeUnit.SECONDS)
        }
        return START_NOT_STICKY
    }
    private fun submit(block: () -> Unit): Boolean = synchronized(lock) {
        if(ended) return@synchronized false
        pending++
        executor.execute {
            try { if(!ended) block() }
            catch(e: Exception) { MonitoringStore.log(this,"exact execution failed; ${MonitoringStore.error(e)}") }
            finally {
                synchronized(lock) {
                    pending--
                    if(pending==0) main.post {
                        if(synchronized(lock) { pending==0 }) finishRun("Scheduled foreground work finished")
                    }
                }
            }
        }
        true
    }
    private fun prepare(transfer: String,lease: String) {
        if(!TransferQueue.owns(this,transfer,lease)) return
        val job=TransferQueue.jobs(this).find { it.optString("id")==transfer } ?: return
        val monitor=job.optString("monitor")
        val discovery=MonitoringStore.jobs(this).find { it.optString("id")==monitor }
        val rule=discovery?.let { MonitoringStore.get(this,it.optString("rule")) }
        if(discovery==null || discovery.optString("state")=="dismissed" || rule?.enabled!=true) {
            TransferQueue.complete(this,transfer,"Monitoring source unavailable or paused","",false,lease)
            return
        }
        MonitoringStore.log(this,"exact foreground media handoff; job=$monitor")
        try {
            MonitoringCoordinator.launchQueued(this,monitor,transfer,lease)
            val until=SystemClock.elapsedRealtime()+120_000
            while(!ended && TransferQueue.owns(this,transfer,lease)) {
                if(TransferQueue.jobs(this).find { it.optString("id")==transfer }?.optBoolean("engine_started")==true) {
                    MonitoringStore.log(this,"exact media service acknowledged; job=$monitor")
                    return
                }
                if(SystemClock.elapsedRealtime()>=until) break
                Thread.sleep(200)
            }
            TransferQueue.deferMonitoring(this,transfer,lease,"Exact alarm media preparation timed out or was interrupted")
        } catch(e: Exception) {
            TransferQueue.deferMonitoring(this,transfer,lease,"Managed background handoff unavailable: "+MonitoringStore.error(e))
        }
    }
    private fun finishRun(reason: String) {
        synchronized(lock) {
            if(ended) return
            ended=true
            if(owner===this) owner=null
        }
        val abandoned=synchronized(lock) { leases.toMap().also { leases.clear() } }
        abandoned.forEach { (transfer,lease) -> TransferQueue.deferMonitoring(this,transfer,lease,reason) }
        synchronized(lock) { retries.values.forEach { main.removeCallbacks(it) };retries.clear() }
        main.removeCallbacks(deadline)
        MonitoringStore.log(this,"exact execution ended; $reason; saved schedules and queued items retained")
        // Re-arm overdue sources too: interrupted checks must not wait for an app screen.
        MonitoringWakeup.refresh(this,notBefore=System.currentTimeMillis()+60_000)
        if(wake?.isHeld==true) wake?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTimeout(startId: Int,fgsType: Int) {
        MonitoringExecutionBudget.exhausted(this,"Android delivered onTimeout; type=$fgsType")
        finishRun("Android foreground-service time budget exhausted")
        MonitoringWakeup.enqueueDue(this,rearmExact=true)
    }
    override fun onDestroy() {
        finishRun("Foreground owner destroyed")
        executor.shutdownNow()
        super.onDestroy()
    }
}
