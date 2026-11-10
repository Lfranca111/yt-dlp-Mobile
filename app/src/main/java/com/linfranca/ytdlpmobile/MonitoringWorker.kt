package com.linfranca.ytdlpmobile

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object MonitoringScheduler {
    private const val WATCHDOG="monitoring-watchdog"
    private val diagnosticExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun observeSchedule(c: Context,rule: String) {
        diagnosticExecutor.execute {
            runCatching {
                val work=WorkManager.getInstance(c).getWorkInfosForUniqueWork("monitor-rule-$rule").get(20,TimeUnit.SECONDS)
                work.takeLast(4).forEach { info ->
                    MonitoringStore.log(c,"scheduler observation; rule=$rule; work=${info.id}; state=${info.state}; attempt=${info.runAttemptCount}; stopReason=${info.stopReason} (${MonitoringFailure.stop(info.stopReason)}); nextEligible=${info.nextScheduleTimeMillis}")
                }
            }.onFailure { MonitoringStore.log(c,"scheduler observation unavailable; rule=$rule; ${MonitoringStore.error(it)}") }
        }
    }
    fun initialize(c: Context) {
        TransferQueue.initialize(c)
        ComicStore.initialize(c)
        MonitoringStore.recover(c)
        MonitoringSettings.migrate(c)
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WATCHDOG,ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonitoringWatchdog>(15,TimeUnit.MINUTES).build())
        MonitoringStore.rules(c).filter { it.enabled }.forEach { ensure(c,it) }
        MonitoringCoordinator.dispatch(c)
    }
    fun ensure(c: Context,r: MonitoringRule,replace: Boolean=false) {
        if(!r.enabled) return
        val now=System.currentTimeMillis()
        val due=if(r.next>0) r.next else MonitoringPolicy.next(now,r.interval,if(MonitoringSettings.backoff(c)) r.failures else 0,r.zone,r.start,r.end,r.days)
        MonitoringStore.mutate(c,r.id) { if(it.generation==r.generation) it.next=due }
        val request=OneTimeWorkRequestBuilder<MonitoringWorker>()
            .setInputData(workDataOf("rule" to r.id,"generation" to r.generation,"due" to due))
            .setInitialDelay((due-now).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("monitor-rule-${r.id}").build()
        WorkManager.getInstance(c).enqueueUniqueWork("monitor-rule-${r.id}",
            if(replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)
        observeSchedule(c,r.id)
        MonitoringPowerDiagnostics.record(c,"check scheduled; rule=${r.id}; due=$due")
        MonitoringWakeup.refresh(c)
        MonitoringStore.log(c,"schedule requested; rule=${r.id}; work=${request.id}; policy=${if(replace) "REPLACE" else "KEEP"}; next=$due; timing approximate")
    }
    fun successor(c: Context,r: MonitoringRule) {
        if(!r.enabled) return
        val latest=MonitoringStore.get(c,r.id) ?: return
        if(latest.generation!=r.generation) return
        val now=System.currentTimeMillis()
        val backoff=MonitoringSettings.backoff(c)
        val savedRegular=r.customState.optLong("regularCheckDue")
        val regular=if(!backoff && savedRegular>now && MonitoringPolicy.inWindow(savedRegular,r.zone,r.start,r.end,r.days)) savedRegular
            else MonitoringPolicy.next(now,r.interval,if(backoff) r.failures else 0,r.zone,r.start,r.end,r.days)
        val candidate=MonitoringRecoveryPolicy.nextCheck(now,r.interval,r.failures,r.zone,r.start,r.end,r.days,r.customState.optString("recoveryKind"),backoff)
        val due=minOf(regular,candidate)
        val reason=if(due<regular) "short recovery retry" else if(backoff && r.failures>0) "optional failure backoff" else "selected interval / allowed timeframe"
        MonitoringStore.mutate(c,r.id) { if(it.generation==r.generation) {
            it.next=due;it.customState.put("regularCheckDue",regular).put("scheduleReason",reason)
        } }
        MonitoringStore.log(c,"next schedule decision; rule=${r.id}; reason=$reason; regular=$regular; next=$due; backoff=$backoff")
        val request=OneTimeWorkRequestBuilder<MonitoringWorker>()
            .setInputData(workDataOf("rule" to r.id,"generation" to r.generation,"due" to due))
            .setInitialDelay(due-now,TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("monitor-rule-${r.id}").build()
        WorkManager.getInstance(c).enqueueUniqueWork("monitor-rule-${r.id}",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        observeSchedule(c,r.id)
        MonitoringPowerDiagnostics.record(c,"check scheduled; rule=${r.id}; due=$due")
        MonitoringWakeup.refresh(c)
        MonitoringStore.log(c,"next check queued; rule=${r.id}; next=$due; failures=${r.failures}")
    }
    fun checkNow(c: Context,r: MonitoringRule) {
        WorkManager.getInstance(c).enqueueUniqueWork("monitor-now-${r.id}",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MonitoringWorker>()
                .setInputData(workDataOf("rule" to r.id,"generation" to r.generation,"manual" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        MonitoringStore.log(c,"manual test/check requested; rule=${r.id}")
    }
    fun cancel(c: Context,id: String) {
        WorkManager.getInstance(c).cancelUniqueWork("monitor-rule-$id")
        WorkManager.getInstance(c).cancelUniqueWork("monitor-now-$id")
        WorkManager.getInstance(c).cancelUniqueWork("monitor-wakeup-$id")
        MonitoringWakeup.refresh(c,exclude=id)
        MonitoringStore.log(c,"rule scheduling canceled; rule=$id; active media task retained")
    }
}

class MonitoringWatchdog(c: Context,p: WorkerParameters): Worker(c,p) {
    override fun doWork(): Result {
        try {
            MonitoringPowerDiagnostics.record(applicationContext,"watchdog")
            TransferQueue.initialize(applicationContext)
            ComicStore.initialize(applicationContext)
            TransferQueue.dispatch(applicationContext)
            MonitoringStore.recover(applicationContext)
            MonitoringStore.rules(applicationContext).filter { it.enabled }.forEach { MonitoringScheduler.ensure(applicationContext,it) }
            MonitoringCoordinator.dispatch(applicationContext)
            return Result.success()
        } catch(e: Exception) { MonitoringStore.log(applicationContext,"watchdog error: ${e.message}");return Result.retry() }
    }
}

class MonitoringWorker(c: Context,p: WorkerParameters): Worker(c,p) {
    companion object {
        internal val gate=ReentrantLock()
        fun isChecking() = gate.isLocked
    }
    override fun onStopped() {
        val reason = if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else -512
        MonitoringPowerDiagnostics.record(applicationContext,"check stopped; work=$id",reason)
        runCatching { MonitoringStore.log(applicationContext,"check stopped; work=$id; reason=$reason (${MonitoringFailure.stop(reason)}); ${DeviceDiagnostics.snapshot(applicationContext)}") }
        runCatching {
            val rule=inputData.getString("rule");val generation=inputData.getLong("generation",0)
            if(rule!=null) MonitoringStore.mutate(applicationContext,rule) { r ->
                if(r.generation==generation) r.status="Check interrupted: "+MonitoringFailure.stop(reason)+". Existing baseline retained; Android may retry."
            }
        }
        super.onStopped()
    }
    override fun doWork(): Result = MonitoringCheckRun(applicationContext,inputData,id,runAttemptCount,
        { isStopped }, {
            try { setForegroundAsync(it).get(8,TimeUnit.SECONDS) }
            catch(e: Exception) {
                if(MonitoringFailure.kind(MonitoringStore.error(e))=="quota") MonitoringExecutionBudget.exhausted(applicationContext,MonitoringStore.error(e))
                throw e
            }
        }).run()
}

/** One implementation for WorkManager and an alarm-owned foreground lifetime. */
internal class MonitoringCheckRun(
    private val applicationContext: Context,
    private val inputData: Data,
    private val workId: java.util.UUID,
    private val runAttemptCount: Int,
    private val stopped: () -> Boolean,
    private val promote: (ForegroundInfo) -> Unit
) {
    private val isStopped: Boolean get() = stopped()
    fun run(): ListenableWorker.Result {
        val c=applicationContext;val id=inputData.getString("rule") ?: return ListenableWorker.Result.failure()
        val generation=inputData.getLong("generation",0)
        val manual=inputData.getBoolean("manual",false)
        // Serial checks avoid bursts across many saved links and protect the embedded extractor.
        if(!MonitoringWorker.gate.tryLock(30,TimeUnit.SECONDS)) {
            MonitoringStore.log(c,"check deferred; another check owns extractor; rule=$id; attempt=$runAttemptCount")
            return ListenableWorker.Result.retry()
        }
        var scheduleNext=false
        try {
            val r=MonitoringStore.get(c,id) ?: return ListenableWorker.Result.success()
            if(r.generation!=generation || (!r.enabled && !manual) || isStopped) return ListenableWorker.Result.success()
            val now=System.currentTimeMillis()
            val expected=inputData.getLong("due",0)
            if(!manual && !MonitoringRecoveryPolicy.currentCheck(expected,r.next,now)) {
                MonitoringStore.log(c,"obsolete or early check skipped; rule=$id; expected=$expected; next=${r.next}; actual=$now")
                return ListenableWorker.Result.success()
            }
            scheduleNext=!manual
            MonitoringPowerDiagnostics.record(c,"check started; rule=$id; lateByMs=${(now-r.next).coerceAtLeast(0)}")
            MonitoringStore.log(c,"check started; rule=$id; scheduled=${r.next}; actual=$now; lateByMs=${if(manual) 0 else (now-r.next).coerceAtLeast(0)}; manual=$manual; work=${workId}; attempt=$runAttemptCount; ${DeviceDiagnostics.snapshot(c)}")
            if(!manual && !MonitoringPolicy.inWindow(now,r.zone,r.start,r.end,r.days)) {
                r.status="Outside expected timeframe";MonitoringStore.commitCheck(c,r);return ListenableWorker.Result.success()
            }
            if(!MonitoringPowerDiagnostics.networkUsable(c)) {
                r.last=now;r.failures++;r.customState.put("recoveryKind","network")
                r.status=MonitoringFailure.message("Network is unreachable")
                MonitoringStore.commitCheck(c,r)
                MonitoringStore.log(c,"network not validated before probe; rule=$id; baseline retained; bounded retry scheduled")
                return ListenableWorker.Result.success()
            }
            val canceled={ isStopped || MonitoringStore.get(c,id)?.generation!=generation }
            var browserPromoted=false
            val promoteBrowser: ()->Unit = {
                if(!browserPromoted) {
                        val manager=c.getSystemService(android.app.NotificationManager::class.java)
                        manager.createNotificationChannel(android.app.NotificationChannel("monitoring_checks","Monitoring checks",android.app.NotificationManager.IMPORTANCE_LOW))
                        val notification=androidx.core.app.NotificationCompat.Builder(c,"monitoring_checks")
                            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Checking monitored channel")
                            .setContentText("Loading the channel in the background")
                            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true)
                            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Cancel check",WorkManager.getInstance(c).createCancelPendingIntent(workId)).build()
                        try { promote(ForegroundInfo(21015,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
                        catch(e: Exception) { throw MonitoringRenderDeferred("Android did not allow the background browser worker; retry when permitted or use Open browser to check").apply { initCause(e) } }
                    browserPromoted=true
                    MonitoringStore.log(c,"browser fallback foreground accepted; rule=$id; shared session; no Activity launched")
                }
            }
            val custom=if(r.customSource.isNotBlank()) {
                try {
                    CustomMonitoringEngine.check(c,r,canceled,beforeRender=promoteBrowser)
                } catch(e: BrowserCheckRequired) {
                    if(canceled()) return ListenableWorker.Result.success()
                    r.last=now;r.failures=0;r.status="Interaction required: "+MonitoringStore.error(e)
                    if(r.notified!="browser-required") {
                        MonitoringNotifications.discovery(c,"rule:"+r.id,"Monitoring needs sign-in or verification",r.name+" — tap Open browser to check")
                        r.notified="browser-required"
                    }
                    MonitoringStore.commitCheck(c,r);MonitoringStore.log(c,"custom check needs user interaction; rule=$id; ${r.status}")
                    return ListenableWorker.Result.success()
                } catch(e: MonitoringRenderDeferred) {
                    if(canceled()) return ListenableWorker.Result.success()
                    val kind=MonitoringFailure.kind(MonitoringStore.error(e))
                    r.last=now;r.failures=MonitoringRecoveryPolicy.checkFailures(r.failures,r.customState.optString("recoveryKind"),kind);r.customState.put("recoveryKind",if(MonitoringRecoveryPolicy.transientFailure(kind)) kind else "");r.status="Background browser deferred: "+MonitoringFailure.message(MonitoringStore.error(e))
                    if(r.notified!="render-deferred") {
                        MonitoringNotifications.discovery(c,"rule:"+r.id,"Monitoring check delayed",r.name+" — retry later or use Open browser to check")
                        r.notified="render-deferred"
                    }
                    MonitoringStore.commitCheck(c,r);MonitoringStore.log(c,"custom render deferred; rule=$id; ${r.status}")
                    return ListenableWorker.Result.success()
                }
            } else null
            if(canceled()) return ListenableWorker.Result.success()
            if(custom!=null) r.customState.put("lastMethod",custom.method)
            val probe=MonitoringProbe(c) { isStopped || MonitoringStore.get(c,id)?.generation!=generation }.apply { beforeBrowser=promoteBrowser }
            val details=mutableListOf<String>();var failed=false;val failureKinds=linkedSetOf<String>()
            if(r.mode in setOf("uploads","both")) try {
                custom?.uploadsError?.let { throw it }
                val result=custom?.uploads ?: probe.check(r,false);details.add(result.detail)
                if(result.previewOnly) {
                    MonitoringStore.log(c,"upload preview only; rule=${r.id}; prior baseline retained; no entries queued")
                } else if(r.customSource.isNotBlank()) details.add(CustomSources.applyUploads(c,r,result.items,headValidated=custom?.headValidated==true)) else {
                if(r.customState.optInt("builtinRoutingVersion")<118) {
                    if(r.baseline && MonitoringLinkPolicy.changedUploadScope(r.url)) {
                        r.baseline=false
                        MonitoringStore.log(c,"built-in routing scope changed; rule=${r.id}; successful check establishes fresh baseline; previous seen IDs retained; no old items queued")
                    }
                    r.customState.put("builtinRoutingVersion",118)
                }
                CaptureSiteRoutes.vkLiveRoot(r.url)?.let { scope ->
                    if(r.customState.optString(VkReplayListing.BASELINE_SCOPE)!=scope) {
                        // A generic listing from an older build may have different IDs.
                        // Establish this provider baseline only after a verified check.
                        r.baseline=false
                        r.customState.put(VkReplayListing.BASELINE_SCOPE,scope)
                    }
                }
                val fresh=result.items.filter { it.id !in r.seen }
                if(r.baseline) fresh.reversed().forEach { item ->
                    if(!isStopped && MonitoringStore.get(c,id)?.generation==generation) {
                        val job=MonitoringStore.enqueue(c,r,item,false)
                        MonitoringNotifications.discovery(c,job,"New upload detected",r.name)
                    }
                } else {
                    r.baseline=true
                    MonitoringStore.log(c,"future uploads baseline established; rule=$id; items=${result.items.size}; no existing items downloaded")
                }
                r.seen.addAll(result.items.map { it.id })
                // IDs remain durable; do not evict old IDs and accidentally redownload pinned posts.
                }
            } catch(e: Exception) { failed=true;failureKinds.add(MonitoringFailure.kind(MonitoringStore.error(e)));details.add("Uploads: "+MonitoringFailure.message(MonitoringStore.error(e))+(if(e is BrowserCheckRequired) " "+MonitoringStore.error(e) else ""));MonitoringStore.log(c,"uploads check failed; category=${MonitoringFailure.kind(MonitoringStore.error(e))}; ${MonitoringStore.error(e)}") }
            if(r.mode in setOf("live","both")) try {
                custom?.liveError?.let { throw it }
                val result=custom?.live ?: probe.check(r,true);details.add(result.detail)
                if(result.offline) {
                    r.offlineChecks++
                    if(r.offlineChecks>=2) r.liveSession=""
                } else if(result.live!=null) {
                    r.offlineChecks=0
                    details.add(MonitoringCoordinator.acceptLive(c,r,result.live))
                } else { r.offlineChecks=0;details.add("Live state unknown; no recording queued") }

            } catch(e: Exception) { r.offlineChecks=0;failed=true;failureKinds.add(MonitoringFailure.kind(MonitoringStore.error(e)));details.add("Live: "+MonitoringFailure.message(MonitoringStore.error(e))+(if(e is BrowserCheckRequired) " "+MonitoringStore.error(e) else ""));MonitoringStore.log(c,"live check failed; category=${MonitoringFailure.kind(MonitoringStore.error(e))}; ${MonitoringStore.error(e)}") }
            if(isStopped || MonitoringStore.get(c,id)?.generation!=generation) return ListenableWorker.Result.success()
            val recoveryKind=failureKinds.firstOrNull { MonitoringRecoveryPolicy.transientFailure(it) }.orEmpty()
            r.last=System.currentTimeMillis();r.failures=if(failed) MonitoringRecoveryPolicy.checkFailures(r.failures,r.customState.optString("recoveryKind"),recoveryKind) else 0
            r.customState.put("recoveryKind",recoveryKind)
            r.status=(if(custom!=null) "Method: ${custom.method}\n" else "")+details.joinToString("\n")
            if(failed && r.notified!="check-failed:"+failureKinds.joinToString(",")) {
                MonitoringNotifications.discovery(c,"rule:"+r.id,"Monitoring check needs attention",r.name+" — "+details.lastOrNull().orEmpty().take(200))
                r.notified="check-failed:"+failureKinds.joinToString(",")
            } else if(!failed) r.notified=""
            if(!MonitoringStore.commitCheck(c,r)) return ListenableWorker.Result.success()
            MonitoringStore.log(c,"check completed; rule=$id; ${r.status}; backoff failures=${r.failures}")
            MonitoringCoordinator.dispatch(c)
            return ListenableWorker.Result.success()
        } catch(e: Exception) {
            if(isStopped || MonitoringStore.get(c,id)?.generation!=generation) return ListenableWorker.Result.success()
            MonitoringStore.log(c,"check infrastructure failure; rule=$id; ${e.message}")
            MonitoringStore.mutate(c,id) { if(it.generation==generation) { it.failures++;it.customState.put("recoveryKind",MonitoringFailure.kind(MonitoringStore.error(e)));it.status="Check failed: "+MonitoringStore.error(e) } }
            return ListenableWorker.Result.success()
        } finally {
            try {
                if(scheduleNext && !isStopped) MonitoringStore.get(c,id)?.takeIf { it.generation==generation && it.enabled }
                    ?.let { MonitoringScheduler.successor(c,it) }
            } finally { MonitoringWorker.gate.unlock() }
        }
    }
}
