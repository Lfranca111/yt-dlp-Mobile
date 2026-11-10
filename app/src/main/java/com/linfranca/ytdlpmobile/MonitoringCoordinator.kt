package com.linfranca.ytdlpmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

object MonitoringCoordinator {
    private val executor=Executors.newCachedThreadPool()
    @Volatile private var active=""
    private val journal = ProgressJournal()
    private var lastStateWrite=0L
    fun hasActive() = active.isNotBlank()
    fun dispatch(c: Context) {
        MonitoringStore.jobs(c).filter { it.optString("state")=="pending" && MonitoringStore.get(c,it.optString("rule"))?.enabled==true }
            .forEach { start(c,it.getString("id")) }
    }
    /** Caller owns the shared check gate. History never prevents a later recording. */
    fun acceptLive(c: Context,r: MonitoringRule,live: MonitoringItem): String {
        val outstanding=TransferQueue.liveInFlight(c,r.id,r.url,live.url)
        val notified=if(r.automatic) null else MonitoringStore.jobs(c).firstOrNull {
            it.optBoolean("live") && it.optString("rule")==r.id && it.optString("state")=="notify" && LiveRecordingPolicy.samePage(it.optString("url"),live.url)
        }
        val decision=if(outstanding!=null) LiveRecordingPolicy.status(outstanding.optString("state"))
        else if(notified!=null) "Live notification already waiting; automatic recording is disabled"
        else {
            val item=live.copy(id=live.id+":"+java.util.UUID.randomUUID())
            val id=MonitoringStore.enqueue(c,r,item,true)
            discoveredLive(c,id,r.automatic)
            r.liveSession=item.id
            if(r.customSource.isNotBlank()) r.customState.put("liveEndConfirmed",false)
            MonitoringNotifications.discovery(c,id,"Live stream detected",r.name)
            if(r.automatic) "Fresh live recording queued; prior recordings do not block live content"
            else "Live stream notification ready; automatic recording is disabled"
        }
        MonitoringStore.log(c,"live recording decision; rule=${r.id}; $decision")
        return decision
    }
    fun discoveredLive(c: Context,id: String,automatic: Boolean) {
        val job=MonitoringStore.jobs(c).find { it.optString("id")==id } ?: return
        val request=Intent(c,MonitoringRecordingService::class.java).putExtra("monitor_probe",true)
        val transfer=TransferQueue.submit(c,request,job.optString("name")+" — Live stream",true,id,!automatic)
        val existing=TransferQueue.jobs(c).find { it.optString("id")==transfer }
        if(existing!=null && existing.optString("monitor")!=id)
            MonitoringStore.updateJob(c,id,"duplicate","Existing live recording task retained; overlapping start prevented")
    }
    fun start(c: Context,id: String) {
        val job=MonitoringStore.jobs(c).find { it.optString("id")==id } ?: return
        val request=Intent(c,if(job.optBoolean("live")) MonitoringRecordingService::class.java else MonitoringDownloadService::class.java)
            .putExtra("monitor_probe",true).putExtra(DownloadService.EXTRA_URL,job.getString("url"))
        val transfer=TransferQueue.submit(c,request,job.optString("name")+" — "+job.optString("title"),job.optBoolean("live"),id)
        val existing=TransferQueue.jobs(c).find { it.optString("id")==transfer }
        if(existing?.optString("monitor")!=id) MonitoringStore.updateJob(c,id,if(existing?.optString("state")=="saved") "saved" else "duplicate","Duplicate prevented; existing task retained in Queue & history")
        else if(existing?.optString("state")=="attention") MonitoringStore.updateJob(c,id,"attention",existing.optString("detail"))
        else MonitoringStore.updateJob(c,id,"queued","Waiting in the shared media queue")
    }
    fun launchQueued(c: Context,id: String,transferId: String,lease: String) {
        active=id;journal.reset()
        executor.execute {
            val job=MonitoringStore.jobs(c).find { it.optString("id")==id } ?: run { active="";TransferQueue.complete(c,transferId,"Monitoring item was removed","",false,lease);return@execute }
            val trace=MonitoringTrace(c,"media-handoff").apply { rule(job.optString("rule")) }
            trace.event("queued-item","job=$id; transfer=$transferId; live=${job.optBoolean("live")}; freshDetection=true")
            try {
                if(!TransferQueue.owns(c,transferId,lease)) return@execute
                if(job.optString("state")=="dismissed") { TransferQueue.complete(c,transferId,"Monitoring item dismissed","",false,lease);return@execute }
                MonitoringStore.updateJob(c,id,"running","Verifying and starting")
                MonitoringStore.log(c,"media handoff attempt; job=$id; live=${job.optBoolean("live")}")
                if(job.optBoolean("live")) {
                    val url=job.getString("url")
                    val sourceMode=MonitoringSession.mode(c,MonitoringStore.get(c,job.optString("rule"))?.url ?: url)
                    MonitoringSession.save(c,url,sourceMode,MonitoringSession.marker(c,url))
                    if(MonitoringPolicy.handler(url) in setOf("Stripchat","XHamsterLive"))
                        error("Custom live player needs Browser Assist for fresh segment observations")
                    val rule=MonitoringStore.get(c,job.optString("rule"))
                    val source=rule?.takeIf { it.customSource.isNotBlank() }?.let { CustomSources.source(c,it) }
                    val verificationStarted=android.os.SystemClock.elapsedRealtime()
                    trace.event("playback-verification","event=start; custom=${source!=null}; handler=${MonitoringPolicy.handler(url)}")
                    val result=if(source!=null && source.getInt("schema")>=4) {
                        val liveSource=org.json.JSONObject(source.toString()).put("mode","live")
                        CustomSources.validate(liveSource)
                        check(CustomSetupPolicy.ready(liveSource)) { "Custom live setup changed or needs local testing; queued task retained for review" }
                        val fresh=CustomLiveResolver.resolve(c,source,null,{ !TransferQueue.owns(c,transferId,lease) })
                        check(fresh.live!=null && fresh.live.url==url && fresh.video.isNotBlank()) { "Queued custom broadcast is no longer verified live; fresh monitoring is required" }
                        fresh
                    } else MonitoringProbe(c) { !TransferQueue.owns(c,transferId,lease) }.apply { diagnosticRule=job.optString("rule") }.verify(MonitoringItem(job.getString("item"),url),MonitoringSession.mode(c,rule?.url ?: url))
                    trace.event("playback-verification","event=complete; stageElapsedMs=${android.os.SystemClock.elapsedRealtime()-verificationStarted}; videoAvailable=${result.video.isNotBlank()}; separateAudio=${result.audio.isNotBlank()}; videoHeaderCount=${result.videoHeaders.size}; audioHeaderCount=${result.audioHeaders.size}")
                    val request=Intent(c,MonitoringRecordingService::class.java).setAction(MonitoringRecordingService.ACTION_START)
                        .putExtra(MonitoringRecordingService.EXTRA_URL,result.video)
                        .putExtra(MonitoringRecordingService.EXTRA_AUDIO_URL,result.audio)
                        .putExtra(MonitoringRecordingService.EXTRA_PAGE_URL,url)
                        .putExtra(MonitoringRecordingService.EXTRA_PAGE_COOKIES,android.webkit.CookieManager.getInstance().getCookie(url).orEmpty())
                        .putExtra(MonitoringRecordingService.EXTRA_PAGE_USER_AGENT,MonitoringSession.agent(MonitoringSession.mode(c,MonitoringStore.get(c,job.optString("rule"))?.url ?: url),"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"))
                        .putExtra(MonitoringRecordingService.EXTRA_REFERER,url)
                        .putExtra(MonitoringRecordingService.EXTRA_COOKIES,android.webkit.CookieManager.getInstance().getCookie(result.video).orEmpty())
                        .putExtra(MonitoringRecordingService.EXTRA_AUDIO_COOKIES,if(result.audio.isBlank()) "" else android.webkit.CookieManager.getInstance().getCookie(result.audio).orEmpty())
                    if(source==null && YouTubeLivePolicy.videoId(url)!=null &&
                        result.video.substringBefore('?').endsWith(".m3u8",true) &&
                        (result.audio.isBlank() || result.audio.substringBefore('?').endsWith(".m3u8",true))) {
                        request.putExtra(MonitoringRecordingService.EXTRA_VERIFIED_YOUTUBE_HLS,true)
                        request.putExtra(MonitoringRecordingService.EXTRA_STARTUP_BROWSER_MODE,sourceMode)
                    }
                    if(source==null && CaptureMonitoringPolicy.isCaptureId(job.optString("item"),CaptureSiteRoutes.Site.VK) &&
                        CaptureSiteRoutes.live(url)?.site==CaptureSiteRoutes.Site.VK && CaptureSiteHandlers.isVkDash(result.video))
                        request.putExtra(CaptureMonitoringPolicy.VK_DASH,true)
                    fun header(h: Map<String,String>,name: String)=h.entries.firstOrNull { it.key.equals(name,true) }?.value.orEmpty()
                    request.putExtra(MonitoringRecordingService.EXTRA_HEADERS_JSON,org.json.JSONObject(result.videoHeaders).toString())
                    request.putExtra(MonitoringRecordingService.EXTRA_AUDIO_HEADERS_JSON,org.json.JSONObject(result.audioHeaders).toString())
                    request.putExtra(MonitoringRecordingService.EXTRA_USER_AGENT,header(result.videoHeaders,"user-agent"))
                    if(header(result.videoHeaders,"cookie").isNotBlank()) request.putExtra(MonitoringRecordingService.EXTRA_COOKIES,header(result.videoHeaders,"cookie"))
                    if(header(result.audioHeaders,"cookie").isNotBlank()) request.putExtra(MonitoringRecordingService.EXTRA_AUDIO_COOKIES,header(result.audioHeaders,"cookie"))
                    if(!TransferQueue.owns(c,transferId,lease)) return@execute
                    TransferQueue.preparationEvent(c,transferId,lease,"Fresh live stream verified; actual recorder service start requested")
                    ContextCompat.startForegroundService(c,request.putExtra(TransferQueue.JOB,transferId).putExtra(TransferQueue.LEASE,lease))
                } else {
                    val request=Intent(c,MonitoringDownloadService::class.java).setAction(MonitoringDownloadService.ACTION_DOWNLOAD)
                        .putExtra(MonitoringDownloadService.EXTRA_URL,job.getString("url"))
                        .putExtra(MonitoringDownloadService.EXTRA_PRESET,MonitoringDownloadService.PRESET_BEST)
                    if(!TransferQueue.owns(c,transferId,lease)) return@execute
                    ContextCompat.startForegroundService(c,request.putExtra(TransferQueue.JOB,transferId).putExtra(TransferQueue.LEASE,lease))
                }
                trace.event("service-start","outcome=accepted; job=$id; transfer=$transferId")
                MonitoringStore.log(c,"media service launch accepted; job=$id")
            } catch(e: Exception) {
                if(!TransferQueue.owns(c,transferId,lease)) return@execute
                trace.failure("preparation",e)
                val kind=MonitoringFailure.kind(MonitoringStore.error(e))
                TransferQueue.preparationEvent(c,transferId,lease,"Actual media preparation/start failed; category=$kind; "+MonitoringStore.error(e))
                if(kind=="quota" || MonitoringRecoveryPolicy.transientFailure(kind)) {
                    TransferQueue.deferMonitoring(c,transferId,lease,MonitoringStore.error(e))
                    return@execute
                }
                active=""
                if(kind=="quota") MonitoringExecutionBudget.exhausted(c,MonitoringStore.error(e))
                val explanation=MonitoringFailure.message(MonitoringStore.error(e))
                MonitoringStore.updateJob(c,id,"attention","$explanation Tap Browser Assist or Retry when appropriate.")
                MonitoringStore.log(c,"automatic attempt failed; job=$id; ${e.javaClass.simpleName}: ${e.message}")
                MonitoringNotifications.discovery(c,id,"Monitoring needs your help",job.optString("name")+" — open Monitoring")
                TransferQueue.complete(c,transferId,"Monitoring start needs attention: $explanation ${MonitoringStore.error(e)}","",false,lease)
            }
        }
    }
    /** Reuse the recorder's positive completion signal; user stops never reset a live session. */
    fun confirmedEnd(c: Context,transfer: String,lease: String) {
        if(!TransferQueue.owns(c,transfer,lease)) return
        val monitor=TransferQueue.jobs(c).find { it.optString("id")==transfer }?.optString("monitor") ?: return
        val job=MonitoringStore.jobs(c).find { it.optString("id")==monitor } ?: return
        val item=job.optString("item");val rule=job.optString("rule")
        val custom=MonitoringStore.get(c,rule)?.takeIf { it.customSource.isNotBlank() } ?: return
        if(runCatching { CustomSources.source(c,custom).getInt("schema")>=4 }.getOrDefault(false).not()) return
        executor.execute {
            MonitoringWorker.gate.lock()
            try {
                MonitoringStore.get(c,rule)?.takeIf { it.customSource.isNotBlank() && it.liveSession==item }?.let { r ->
                    if(CustomSources.source(c,r).getInt("schema")>=4) {
                        MonitoringStore.mutate(c,rule) { if(it.liveSession==item) { it.liveSession="";it.offlineChecks=0;it.customState.put("liveEndConfirmed",true) } }
                        MonitoringStore.log(c,"custom live session closed by existing recorder completion evidence; rule=$rule")
                    }
                }
            } catch(e: Exception) { MonitoringStore.log(c,"custom completion update unavailable; ${MonitoringStore.error(e)}") }
            finally { MonitoringWorker.gate.unlock() }
        }
    }
    fun preparationDeferred(c: Context,id: String) { if(active==id) { active="";journal.reset() } }
    fun channelChanged(c: Context,rule: String) {
        val old=MonitoringStore.jobs(c).filter { it.optString("rule")==rule && it.optString("state") !in setOf("saved","dismissed") }
        val retained=TransferQueue.dismissMonitoring(c,old.map { it.getString("id") }.toSet())
        old.filter { it.getString("id") !in retained }.forEach { MonitoringStore.updateJob(c,it.getString("id"),"dismissed","Channel changed; old waiting task dismissed; retained files remain in Queue & history") }
        MonitoringStore.log(c,"monitoring channel changed; rule=$rule; old waiting tasks retired; active captures and saved files retained")
    }
    /** Explicit recovery is available even when detection failed before a discovery was created. */
    fun browserForRule(c: Context,r: MonitoringRule): Intent? {
        val outstanding=TransferQueue.liveInFlight(c,r.id,r.url,r.url)
        val existing=outstanding?.optString("monitor")?.takeIf { it.isNotBlank() }
            ?: MonitoringStore.jobs(c).asReversed().firstOrNull { it.optString("rule")==r.id && it.optBoolean("live") && it.optString("state") !in setOf("saved","dismissed","duplicate") }?.optString("id")
        val id=existing ?: MonitoringStore.enqueue(c,r.copy(automatic=false),MonitoringItem("manual-browser:"+java.util.UUID.randomUUID(),r.url,"Manual live recording recovery"),true)
        MonitoringStore.log(c,"user requested live Browser Assist recovery; rule=${r.id}; job=$id; fresh playback required; no automatic capture started by opening browser")
        return browser(c,id)
    }
    @Synchronized fun browser(c: Context,id: String): Intent? {
        val job=MonitoringStore.jobs(c).find { it.optString("id")==id } ?: return null
        MonitoringStore.log(c,"user opened monitoring Browser Assist; job=$id")
        val transfer=TransferQueue.jobs(c).find { it.optString("monitor")==id && it.optString("state") in TransferRules.activeStates }
        val snapshot=MediaTaskRuntime.state(transfer?.optString("id").orEmpty(),job.getString("url"),true)
        val recovering=MonitoringBrowserRecoveryPolicy.reuse(job.optBoolean("live"),snapshot.running,
            snapshot.recording403RecoveryAvailable,job.getString("url"),snapshot.recordingPageUrl)
        return Intent(c,MonitoringBrowserSessionActivity::class.java).putExtra("monitor_job",id)
            .putExtra(TransferQueue.JOB,transfer?.optString("id").orEmpty())
            .putExtra(MonitoringBrowserSessionActivity.EXTRA_PAGE_URL,job.getString("url"))
            .putExtra(MonitoringBrowserSessionActivity.EXTRA_MODE,if(job.optBoolean("live")) MonitoringBrowserSessionActivity.MODE_RECORD else MonitoringBrowserSessionActivity.MODE_DOWNLOAD)
            .putExtra(MonitoringBrowserSessionActivity.EXTRA_RECOVERY,recovering)
    }
    @Synchronized fun browserStarted(c: Context,id: String) {
        // Recovery reuses the active recording. Its browser intent may not carry the original job ID.
        if(id.isBlank()) return
        active=id;journal.reset();MonitoringStore.updateJob(c,id,"running","Browser-assisted media task started")
    }
    private val taskJournals=mutableMapOf<String,ProgressJournal>()
    private val taskWrites=mutableMapOf<String,Long>()
    @Synchronized fun progress(c: Context,id: String,status: String,log: String) {
        if(id.isBlank()) return
        val delta=taskJournals.getOrPut(id) { ProgressJournal() }.accept(log)
        if(delta.isNotBlank()) MonitoringStore.log(c,"media diagnostics; job=$id\n$delta")
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-(taskWrites[id] ?: 0L)>=1000) {
            taskWrites[id]=now;MonitoringStore.updateJob(c,id,"running",status)
        }
    }
    @Synchronized fun finished(c: Context,id: String,status: String) {
        if(id.isBlank()) return
        val successful=(status.startsWith("Saved ") && !status.startsWith("Saved with missing segments")) || status.startsWith("Recording saved") || status.startsWith("Partial recording saved")
        MonitoringStore.updateJob(c,id,if(successful) "saved" else "attention",status)
        MonitoringStore.log(c,"media task finished; job=$id; $status")
        if(!successful) MonitoringNotifications.discovery(c,id,"Monitoring task needs attention",status)
        if(active==id) active=""
        taskJournals.remove(id);taskWrites.remove(id)
    }

}

object MonitoringNotifications {
    fun discovery(c: Context,id: String,title: String,text: String) {
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monitor_discoveries","Monitoring discoveries",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(c,MainActivity::class.java).putExtra("open_monitoring",true).putExtra("monitor_job",id)
            .setAction("monitor-open-$id").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(c,id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(id.hashCode(),NotificationCompat.Builder(c,"monitor_discoveries").setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title).setContentText(text).setContentIntent(pending).setOnlyAlertOnce(true).setAutoCancel(true).build())
            MonitoringStore.log(c,"notification posted; job=$id; notifications enabled=${manager.areNotificationsEnabled()}")
        } catch(e: SecurityException) { MonitoringStore.log(c,"notification permission missing; pending item remains on Monitoring page") }
    }
}
