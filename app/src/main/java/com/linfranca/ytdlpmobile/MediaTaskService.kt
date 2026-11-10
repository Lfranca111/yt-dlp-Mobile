package com.linfranca.ytdlpmobile

import android.app.*
import android.content.*
import android.os.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One Android component hosts isolated engines. No recording state is shared between engines. */
abstract class MediaTaskService : Service() {
    private val engines=linkedMapOf<String,MediaTaskEngine>()
    private val foreground=linkedMapOf<String,Pair<Int,Notification>>()
    private val main=Handler(Looper.getMainLooper())
    private var foregroundAt=0L
    private var primaryNotification: Int?=null
    override fun onCreate() {
        super.onCreate()
        MonitoringStore.log(this,"media service created; component=${javaClass.simpleName}; ${DeviceDiagnostics.snapshot(this)}")
    }
    internal abstract fun createEngine(id: String): MediaTaskEngine
    protected abstract fun isStart(action: String?): Boolean
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent==null) { if(engines.isEmpty()) stopSelfResult(startId);return START_NOT_STICKY }
        val explicit=intent.getStringExtra(TransferQueue.JOB).orEmpty()
        if(!isStart(intent.action) && intent.hasExtra(TransferQueue.JOB) && explicit.isBlank()) {
            if(engines.isEmpty()) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val id=explicit.ifBlank { MediaTaskRuntime.focused(javaClass.simpleName.startsWith("Monitoring")).takeIf { engines.containsKey(it) } ?: engines.keys.lastOrNull().orEmpty() }
        if(isStart(intent.action)) {
            val lease=intent.getStringExtra(TransferQueue.LEASE).orEmpty()
            if(explicit.isBlank() || lease.isBlank() || !TransferQueue.owns(this,id,lease)) {
                if(engines.isEmpty()) stopSelfResult(startId)
                return START_NOT_STICKY
            }
            if(!engines.containsKey(id)) engines[id]=createEngine(id).also {
                if (!it.monitoring) {
                    val source = intent.getStringExtra(RecordingService.EXTRA_PAGE_URL).orEmpty()
                        .ifBlank { intent.getStringExtra(DownloadService.EXTRA_URL).orEmpty() }
                    it.sourceHost = runCatching { java.net.URI(source).host.orEmpty() }.getOrDefault("")
                }
                it.onCreate()
            }
        }
        val engine=engines[id] ?: run {
            if(engines.isEmpty()) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try { engine.onStartCommand(intent,flags,startId) }
        catch(e: Exception) {
            val reason=MonitoringStore.error(e)
            val job=TransferQueue.jobs(this).find { it.optString("id")==id }
            if(MonitoringFailure.kind(reason) in setOf("background","quota") && job?.optString("monitor").orEmpty().isNotBlank())
                TransferQueue.deferMonitoring(this,id,job!!.optString("lease"),reason)
            else TransferQueue.complete(this,id,"Task could not start: $reason",reason,false,job?.optString("lease").orEmpty())
            engine.taskState.running=false;engine.taskState.status="Task could not start: $reason"
            engine.sendBroadcast(Intent(if(engine.monitoring) MonitoringDownloadService.ACTION_STATE else DownloadService.ACTION_STATE)
                .setPackage(packageName).putExtra(DownloadService.EXTRA_RUNNING,false)
                .putExtra(DownloadService.EXTRA_STATUS,engine.taskState.status)
                .putExtra(DownloadService.EXTRA_LOG,reason))
            release(id)
        }
        return START_NOT_STICKY
    }
    @Synchronized internal fun promote(id: String,notificationId: Int,n: Notification) {
        if(engines[id]?.notificationsClosed?.get()!=false) return
        // Only record acceptance after Android accepts foreground promotion.
        startForeground(notificationId,n)
        primaryNotification=notificationId
        if(foregroundAt==0L) foregroundAt=SystemClock.elapsedRealtime()
        foreground[id]=notificationId to n
        foreground.values.forEach { getSystemService(NotificationManager::class.java).notify(it.first,it.second) }
    }
    @Synchronized internal fun updateNotification(task: String,id: Int,n: Notification) {
        val engine=engines[task] ?: return
        if(engine.notificationsClosed.get() || !foreground.containsKey(task)) return
        if(foreground[task]?.first==id) foreground[task]=id to n
        getSystemService(NotificationManager::class.java).notify(id,n)
    }
    @Synchronized internal fun removeForeground(id: String) {
        val removed=foreground.remove(id) ?: return
        val remaining=foreground.values.lastOrNull()
        val action=ForegroundNotificationPolicy.removal(removed.first,primaryNotification,remaining?.first)
        when(action) {
            ForegroundNotificationPolicy.Action.REMOVE_LAST -> {
                // Removal must go through Android's service API while this notification
                // is still associated with the service. DETACH + cancel races that association.
                stopForeground(STOP_FOREGROUND_REMOVE)
                primaryNotification=null
            }
            ForegroundNotificationPolicy.Action.HAND_OVER -> {
                check(remaining!=null)
                runCatching { startForeground(remaining.first,remaining.second);primaryNotification=remaining.first }
                    .onFailure { MonitoringStore.log(this,"foreground notification handover failed; ${MonitoringStore.error(it)}") }
            }
            ForegroundNotificationPolicy.Action.KEEP_PRIMARY -> Unit
        }
        val manager=getSystemService(NotificationManager::class.java)
        manager.cancel(removed.first)
        MonitoringStore.log(this,"task notification cleanup; task=$id; notification=${removed.first}; action=$action; remainingTasks=${foreground.size}; primary=${primaryNotification ?: 0}")
        // Android releases former foreground IDs asynchronously. Recheck only this
        // finished task's ID; never cancel a different task's ongoing notification.
        main.postDelayed({
            if(foreground.values.none { it.first==removed.first }) {
                manager.cancel(removed.first)
                val stale=runCatching { manager.activeNotifications.firstOrNull { it.id==removed.first } }.getOrNull()
                val foregroundFlag=stale?.notification?.flags?.let { it and Notification.FLAG_FOREGROUND_SERVICE != 0 } ?: false
                MonitoringStore.log(this,"task notification cleanup verification; task=$id; notification=${removed.first}; stillPosted=${stale!=null}; foregroundFlag=$foregroundFlag; remainingTasks=${foreground.size}")
            }
        },500)
    }
    internal fun release(id: String) { main.post {
        val engine=engines.remove(id)
        removeForeground(id)
        engine?.onDestroy()
        MediaTaskRuntime.remove(id)
        if(engines.isEmpty()) stopSelf()
    } }
    override fun onTimeout(startId: Int,fgsType: Int) {
        MonitoringStore.log(this,"foreground service timeout; component=${javaClass.simpleName}; tasks=${engines.size}; type=$fgsType")
        engines.values.toList().forEach { it.onTimeout(startId,fgsType) }
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        primaryNotification=null
        engines.values.toList().forEach { it.onDestroy();MediaTaskRuntime.remove(it.taskId) }
        foreground.values.forEach { getSystemService(NotificationManager::class.java).cancel(it.first) }
        engines.clear();foreground.clear()
        MonitoringStore.log(this,"media service destroyed; component=${javaClass.simpleName}; observedForegroundSpanMs=${if(foregroundAt>0) SystemClock.elapsedRealtime()-foregroundAt else 0}; not an Android quota counter")
        super.onDestroy()
    }
}

internal abstract class MediaTaskEngine(private val owner: MediaTaskService,val taskId: String,val monitoring: Boolean) : ContextWrapper(owner) {
    val taskState=MediaTaskState()
    var sourceHost = ""
    val displayOrder = if (monitoring) 0 else MediaTaskRuntime.nextManualOrder.incrementAndGet()
    val notificationsClosed=java.util.concurrent.atomic.AtomicBoolean(false)
    private val notificationPresentation=ProgressPresentation(500)
    private val notificationIds=mutableMapOf<Int,Int>()
    @Synchronized fun notificationId(original: Int)=notificationIds.getOrPut(original) { MediaTaskRuntime.nextNotification.incrementAndGet() }
    open fun onCreate() { MediaTaskRuntime.add(this) }
    open fun onDestroy() {
        notificationsClosed.set(true)
        synchronized(this) { notificationIds.values.forEach { getSystemService(NotificationManager::class.java).cancel(it) } }
        if(foregroundAt>0) MonitoringStore.log(this,"task engine destroyed; task=$taskId; elapsedSincePromotionMs=${SystemClock.elapsedRealtime()-foregroundAt}; includes cleanup; ${DeviceDiagnostics.snapshot(this)}")
    }
    open fun onTimeout(startId: Int,fgsType: Int) { stopSelf() }
    open fun onBind(intent: Intent?): IBinder?=null
    abstract fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int
    private var foregroundAt=0L
    fun startForeground(id: Int,n: Notification) {
        MonitoringStore.log(this,"foreground promotion requested; task=$taskId; engine=${javaClass.simpleName}; ${DeviceDiagnostics.snapshot(this)}")
        owner.promote(taskId,id,n)
        foregroundAt=SystemClock.elapsedRealtime()
        MonitoringStore.log(this,"foreground promotion accepted; task=$taskId; engine=${javaClass.simpleName}")
    }
    fun updateTaskNotification(id: Int,n: Notification) {
        if(notificationsClosed.get() || !notificationPresentation.allow(SystemClock.elapsedRealtime())) return
        Handler(Looper.getMainLooper()).post { owner.updateNotification(taskId,id,n) }
    }
    fun stopForeground(flags: Int) { notificationsClosed.set(true);Handler(Looper.getMainLooper()).post { owner.removeForeground(taskId) } }
    fun stopSelf() { notificationsClosed.set(true);owner.release(taskId) }
    fun stopSelfResult(startId: Int): Boolean { stopSelf();return true }
    fun monitorId()=TransferQueue.jobs(this).find { it.optString("id")==taskId }?.optString("monitor").orEmpty()
    override fun sendBroadcast(intent: Intent) {
        MediaTaskRuntime.publish(this)
        super.sendBroadcast(intent.putExtra(TransferQueue.JOB,taskId))
    }
    companion object {
        const val START_NOT_STICKY=Service.START_NOT_STICKY
        const val STOP_FOREGROUND_REMOVE=Service.STOP_FOREGROUND_REMOVE
    }
}

internal class MediaTaskState {
    @Volatile var running=false
    @Volatile var operation="idle"
    @Volatile var progress=0
    @Volatile var status="Preparing task"
    @Volatile var log=""
    @Volatile var recording403RecoveryAvailable=false
    @Volatile var recordingRenewalSupported=false
    @Volatile var recordingPageUrl=""
    @Volatile var recordingVideoUrl=""
    @Volatile var recordingAudioUrl=""
    val OPERATION_IDLE="idle"
    val OPERATION_DOWNLOAD="download"
    val OPERATION_RECORD="record"
}

internal object MediaTaskRuntime {
    /** Called synchronously before any services start in a fresh app process. Results are a separate channel. */
    fun clearStaleProgress(c: Context) {
        runCatching {
            val manager=c.getSystemService(NotificationManager::class.java)
            val channels=setOf("download_channel","monitor_download_channel","recording_channel","monitor_recording_channel")
            val stale=manager.activeNotifications.filter { it.notification.channelId in channels }
            stale.forEach { manager.cancel(it.tag,it.id) }
            if(stale.isNotEmpty()) MonitoringStore.log(c,"startup notification cleanup; removed=${stale.size}; previous-process progress only; result notifications retained")
        }
    }
    val nextNotification=AtomicInteger(200000)
    val nextManualOrder=AtomicInteger()
    data class ManualTask(val id: String, val order: Int, val host: String, val recording: Boolean,
                          val progress: Int, val status: String, val log: String,
                          val page: String, val canRecover: Boolean)

    private fun MediaTaskEngine.manualSnapshot(): ManualTask? {
        val s = taskState
        if (monitoring || !s.running) return null
        return ManualTask(taskId, displayOrder, sourceHost, s.operation == s.OPERATION_RECORD,
            s.progress, s.status, s.log, s.recordingPageUrl, s.recording403RecoveryAvailable)
    }

    fun manualTasks(): List<ManualTask> = engines.values.mapNotNull { it.manualSnapshot() }.sortedBy { it.order }
    fun manualTask(id: String): ManualTask? = engines[id]?.manualSnapshot()
    fun manualState(id: String): MediaTaskState? = engines[id]?.takeUnless { it.monitoring }?.taskState
    private val engines=ConcurrentHashMap<String,MediaTaskEngine>()
    @Volatile private var lastManual=""
    @Volatile private var lastMonitoring=""
    fun focused(monitoring: Boolean)=if(monitoring) lastMonitoring else lastManual
    fun add(e: MediaTaskEngine) { engines[e.taskId]=e }
    fun remove(id: String) {
        engines.remove(id)
        DownloadState.running=engines.values.any { !it.monitoring && it.taskState.running }
        MonitoringTaskState.running=engines.values.any { it.monitoring && it.taskState.running }
    }
    fun idForPage(page: String,monitoring: Boolean): String = engines.values.firstOrNull {
        it.monitoring==monitoring && it.taskState.running && LiveRecordingPolicy.samePage(it.taskState.recordingPageUrl,page)
    }?.taskId.orEmpty()
    fun state(id: String,page: String,monitoring: Boolean): MediaTaskState =
        engines[id]?.takeIf { it.monitoring==monitoring }?.taskState
            ?: engines[idForPage(page,monitoring)]?.taskState ?: MediaTaskState()

    fun publish(e: MediaTaskEngine) {
        val selected=if(e.taskState.running) e else engines.values.firstOrNull { it.monitoring==e.monitoring && it.taskState.running } ?: e
        val s=selected.taskState
        if(s.running) { if(e.monitoring) lastMonitoring=selected.taskId else lastManual=selected.taskId }
        if(e.monitoring) {
            MonitoringTaskState.running=engines.values.any { it.monitoring && it.taskState.running }
            MonitoringTaskState.operation=s.operation;MonitoringTaskState.progress=s.progress
            MonitoringTaskState.status=s.status;MonitoringTaskState.log=s.log
            MonitoringTaskState.recording403RecoveryAvailable=s.recording403RecoveryAvailable
            MonitoringTaskState.recordingRenewalSupported=s.recordingRenewalSupported
            MonitoringTaskState.recordingPageUrl=s.recordingPageUrl
            MonitoringTaskState.recordingVideoUrl=s.recordingVideoUrl;MonitoringTaskState.recordingAudioUrl=s.recordingAudioUrl
        } else {
            DownloadState.running=engines.values.any { !it.monitoring && it.taskState.running }
            DownloadState.operation=s.operation;DownloadState.progress=s.progress
            DownloadState.status=s.status;DownloadState.log=s.log
            DownloadState.recording403RecoveryAvailable=s.recording403RecoveryAvailable
            DownloadState.recordingPageUrl=s.recordingPageUrl
            DownloadState.recordingVideoUrl=s.recordingVideoUrl;DownloadState.recordingAudioUrl=s.recordingAudioUrl
        }
    }
}
