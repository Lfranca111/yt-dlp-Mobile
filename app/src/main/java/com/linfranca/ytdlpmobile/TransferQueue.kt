package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Durable media lease. Collections deliberately never acquire it. Payloads stay app-private. */
object TransferQueue {
    const val LEASE = "transfer_attempt_token"
    const val JOB = "transfer_job_id"
    private var initialized = false
    private var state = JSONObject()
    private val main = Handler(Looper.getMainLooper())
    private fun file(c: Context) = AtomicFile(File(c.filesDir,"transfers/state.json").apply { parentFile!!.mkdirs() })
    @Synchronized fun initialize(c: Context) {
        if(initialized) return
        state = runCatching { JSONObject(file(c).openRead().bufferedReader().use { it.readText() }) }.getOrElse { JSONObject().put("jobs",JSONArray()) }
        initialized=true
        // Only preparations without a started engine are safe to retry automatically after process death.
        val preparing=jobs(c).filter { it.optString("monitor").isNotBlank() && it.optString("state")=="running" && !it.optBoolean("engine_started") && !FragmentDiagnosticJob.pending(it) }.map { it.optString("id") }.toSet()
        TransferRules.recover(jobs(c))
        jobs(c).filter { FragmentDiagnosticJob.pending(it) && it.optString("state")=="interrupted" }.forEach {
            FragmentDiagnosticJob.restore(it,"Retained-media action interrupted by process restart; original media and cache kept")
        }
        jobs(c).filter { it.has("partial_download_payload") && it.optString("state")=="interrupted" }.forEach {
            it.put("payload",it.getString("partial_download_payload"));it.remove("partial_download_payload")
        }
        jobs(c).filter { it.optString("id") in preparing }.forEach {
            val count=it.optInt("start_failures")+1
            val delay=MonitoringStartPolicy.delay(it.optBoolean("live"),"canceled",count)
            it.put("start_failures",count).put("state",if(delay>0) "held" else "attention").put("lease","")
                .put("retry_at",if(delay>0) System.currentTimeMillis()+delay else 0L).put("detail","Media preparation interrupted; automatic retry pending")
        }
        jobs(c).filter { it.optString("monitor").isNotBlank() && it.optString("state")=="attention" && !it.optBoolean("startup_recovered_119") && MonitoringFailure.kind(it.optString("detail"))=="background" }.forEach {
            it.put("startup_recovered_119",true).put("state","held").put("retry_at",System.currentTimeMillis()+60_000)
        }
        jobs(c).filter { MonitoringStartPolicy.legacySkippedAttempt(it) }.forEach {
            it.put("startup_recovered_124",true).put("start_failures",0).put("retry_at",System.currentTimeMillis()+1000)
                .put("detail","Previous app cooldown skipped startup; a real preparation attempt is now pending")
            event(it,"v1.0.24 recovery: old app-imposed cooldown removed; no Android rejection inferred")
        }
        jobs(c).filter { it.optString("state")=="held" && it.optLong("retry_at")>0 }.forEach {
            MonitoringMediaRetryWorker.schedule(c,it.getString("id"),it.getLong("retry_at"))
        }
        save(c)
    }
    @Synchronized private fun save(c: Context) {
        val f=file(c);val out=f.startWrite()
        try { out.write(state.toString().toByteArray());f.finishWrite(out) } catch(e: Exception) { f.failWrite(out);throw e }
    }
    @Synchronized fun jobs(c: Context): List<JSONObject> {
        initialize(c);val a=state.optJSONArray("jobs") ?: JSONArray().also { state.put("jobs",it) }
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    @Synchronized fun snapshot(c: Context): List<JSONObject> = jobs(c).map { JSONObject(it.toString()) }
    @Synchronized fun active(c: Context) = TransferRules.active(jobs(c))
    @Synchronized fun paused(c: Context): Boolean { initialize(c);return state.optBoolean("paused") }
    @Synchronized fun pause(c: Context,value: Boolean) { initialize(c);state.put("paused",value);save(c);if(!value) dispatch(c) }
    @Synchronized fun submit(c: Context,request: Intent,title: String,live: Boolean=false,monitor: String="",hold: Boolean=false,allowAgain: Boolean=false): String {
        initialize(c)
        val existing=if(monitor.isBlank()) null else jobs(c).firstOrNull { it.optString("monitor")==monitor && it.optString("state") in setOf("queued","held","running","stopping","reserved","finalizing","attention") }
        if(existing!=null) {
            val id=existing.getString("id")
            if(FragmentDiagnosticJob.pending(existing)) { save(c);dispatch(c);return id }
            if(existing.optString("state") !in TransferRules.activeStates) {
                if(!request.getBooleanExtra("monitor_probe",false)) existing.put("payload",Intent(request).putExtra(JOB,id).toUri(Intent.URI_INTENT_SCHEME)).put("created",System.currentTimeMillis())
                    .put("arguments",JSONArray(request.getStringArrayListExtra(DownloadService.EXTRA_ARGS).orEmpty()))
                if(!hold) existing.put("state","queued")
            }
            save(c);dispatch(c);return id
        }
        if(!live && !allowAgain) duplicate(c,request)?.let {
            MonitoringStore.log(c,"duplicate download prevented; existing=${it.optString("id")}; state=${it.optString("state")}")
            return it.getString("id")
        }
        if(live) {
            val discovery=MonitoringStore.jobs(c).find { it.optString("id")==monitor }
            val rule=discovery?.let { MonitoringStore.get(c,it.optString("rule")) }
            val page=discovery?.optString("url").orEmpty().ifBlank {
                request.getStringExtra(RecordingService.EXTRA_PAGE_URL).orEmpty().ifBlank { request.getStringExtra(RecordingService.EXTRA_URL).orEmpty() }
            }
            liveInFlight(c,rule?.id.orEmpty(),rule?.url.orEmpty(),page)?.let {
                MonitoringStore.log(c,"live recording retained; existing=${it.optString("id")}; state=${it.optString("state")}; overlapping start prevented")
                return it.getString("id")
            }
        }
        val id=UUID.randomUUID().toString()
        val copy=Intent(request).putExtra(JOB,id)
        // Cookie imports can change while waiting: freeze the selected jar for this job.
        copy.getStringExtra(DownloadService.EXTRA_COOKIE_FILE)?.let { path ->
            val source=File(path)
            if(source.isFile) { val jar=File(c.filesDir,"transfers/$id/cookies.txt");jar.parentFile!!.mkdirs();source.copyTo(jar,true);copy.putExtra(DownloadService.EXTRA_COOKIE_FILE,jar.absolutePath) }
        }
        val job=JSONObject().put("id",id).put("title",safeTitle(title).take(200)).put("live",live).put("monitor",monitor)
            .put("state",if(hold) "held" else "queued").put("created",System.currentTimeMillis())
            .put("payload",copy.toUri(Intent.URI_INTENT_SCHEME)).put("arguments",JSONArray(copy.getStringArrayListExtra(DownloadService.EXTRA_ARGS).orEmpty()))
            .put("files",JSONArray()).put("duplicate_key",if(live) "" else identity(request)).put("detail","Waiting in shared media queue")
        event(job,if(hold) "Created; awaiting user action" else "Created with settings snapshot; queued")
        state.getJSONArray("jobs").put(job);save(c)
        if(live && jobs(c).count { it.optString("state") in TransferRules.activeStates }>=TransferSettings.limit(c)) TransferNotifications.show(c,job,"Live stream found — media task already running",true)
        dispatch(c);return id
    }
    /** Keep exact-source duplicate checks and submission under the same queue lock. */
    @Synchronized fun submitBrowserBatchSource(c: Context, request: Intent, title: String, monitor: String): Boolean {
        if (duplicate(c, request) != null) return false
        val old = monitor.takeIf { it.isNotBlank() }?.let { id ->
            jobs(c).firstOrNull { it.optString("monitor") == id &&
                it.optString("state") in setOf("queued","held","running","stopping","reserved","finalizing","attention") }
        }
        // Only the first new source can own a monitoring discovery. Never replace an active
        // capture or a task whose retained media is being used by a diagnostic.
        val owner = monitor.takeUnless {
            old != null && (old.optString("state") in TransferRules.activeStates || FragmentDiagnosticJob.pending(old))
        }.orEmpty()
        if (owner.isNotBlank()) request.setClass(c, MonitoringDownloadService::class.java)
            .setAction(MonitoringDownloadService.ACTION_DOWNLOAD)
        val id = submit(c, request, title, monitor = owner)
        if (old != null && owner.isNotBlank() && id == old.optString("id")) {
            old.put("duplicate_key", identity(request)).put("title", safeTitle(title).take(200))
            save(c)
        }
        return true
    }

    /** Resolve legacy payloads too, so installing an update cannot duplicate an existing capture. */
    @Synchronized fun liveInFlight(c: Context,rule: String,source: String,page: String): JSONObject? {
        return jobs(c).firstOrNull { job ->
            if(!job.optBoolean("live") || job.optString("state") !in LiveRecordingPolicy.outstanding) false
            else {
                val discovery=MonitoringStore.jobs(c).find { it.optString("id")==job.optString("monitor") }
                val owner=discovery?.let { MonitoringStore.get(c,it.optString("rule")) }
                val request=runCatching { Intent.parseUri(job.getString("payload"),Intent.URI_INTENT_SCHEME) }.getOrNull()
                val identity=JSONObject(job.toString()).put("rule",owner?.id.orEmpty()).put("source",owner?.url.orEmpty())
                    .put("page",discovery?.optString("url").orEmpty().ifBlank {
                        request?.getStringExtra(RecordingService.EXTRA_PAGE_URL).orEmpty().ifBlank { request?.getStringExtra(RecordingService.EXTRA_URL).orEmpty() }
                    })
                LiveRecordingPolicy.blocks(identity,rule,source,page)
            }
        }
    }
    private fun identity(request: Intent): String {
        var url=request.getStringExtra(DownloadService.EXTRA_URL).orEmpty()
        if(request.getBooleanExtra(DownloadService.EXTRA_BROWSER_CAPTURED,false)) {
            val info=runCatching { JSONObject(request.getStringExtra(DownloadService.EXTRA_DOWNLOAD_INFO_JSON).orEmpty()) }.getOrNull()
            info?.optString("webpage_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url=it }
        }
        val metadata=runCatching { JSONObject(request.getStringExtra(DownloadService.EXTRA_DOWNLOAD_INFO_JSON).orEmpty()) }.getOrNull()
        val variant=metadata?.optString("_app_variant").orEmpty().take(160)
        url=PlaybackChoice.identityUrl(url,variant)
        val key=TransferIdentity.key(url,request.getBooleanExtra(DownloadService.EXTRA_PLAYLIST,false))
        val typedKey = if(key.isNotBlank() && request.getStringExtra(DownloadService.EXTRA_PRESET)==DownloadService.PRESET_SUBTITLES) "subtitles:$key" else key
        return if (request.getBooleanExtra(DownloadService.EXTRA_BROWSER_CAPTURED, false))
            typedKey + BrowserDownloadBatch.identitySuffix(request) else typedKey
    }
    @Synchronized fun duplicate(c: Context,request: Intent): JSONObject? {
        val key=identity(request);if(key.isBlank()) return null
        return jobs(c).firstOrNull { j ->
            !j.optBoolean("live") && j.optString("state") in setOf("queued","held","running","stopping","reserved","finalizing","saved","attention","interrupted") &&
                (j.optString("duplicate_key")==key || run {
                    runCatching {
                        val stored=Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME)
                        if(stored.getStringExtra(DownloadService.EXTRA_URL).isNullOrBlank() && j.optString("monitor").isNotBlank()) {
                            MonitoringStore.jobs(c).find { it.optString("id")==j.optString("monitor") }?.let { stored.putExtra(DownloadService.EXTRA_URL,it.optString("url")) }
                        }
                        identity(stored)
                    }.getOrDefault("")
                }==key)
        }
    }
    /** Explicit user repeat creates a separate history entry; failures use Retry instead. */
    @Synchronized fun downloadAgain(c: Context,id: String) {
        val j=jobs(c).find { it.optString("id")==id } ?: return
        if(j.optBoolean("live") || j.optString("state")!="saved") return
        var request=Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME)
        if(request.getBooleanExtra("monitor_probe",false)) {
            val discovery=MonitoringStore.jobs(c).find { it.optString("id")==j.optString("monitor") } ?: return
            request=Intent(c,DownloadService::class.java).setAction(DownloadService.ACTION_DOWNLOAD)
                .putExtra(DownloadService.EXTRA_URL,discovery.getString("url")).putExtra(DownloadService.EXTRA_PRESET,DownloadService.PRESET_BEST)
        }
        val args=j.optJSONArray("arguments") ?: JSONArray()
        request.putStringArrayListExtra(DownloadService.EXTRA_ARGS,ArrayList((0 until args.length()).map { args.getString(it) }))
        val repeat=submit(c,request,j.optString("title"),allowAgain=true,hold=true)
        val copy=jobs(c).first { it.getString("id")==repeat }
        copy.put("created",if(request.getBooleanExtra(DownloadService.EXTRA_BROWSER_CAPTURED,false)) 0L else System.currentTimeMillis()).put("state","queued")
        event(copy,"User explicitly selected Download again");save(c);dispatch(c)
    }
    fun workDirectory(c: Context,id: String,kind: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Missing durable task identifier" }
        require(kind in setOf("download","recording"))
        return File(c.filesDir,"transfers/$id/work/$kind")
    }
    fun hasRetained(c: Context,id: String)=runCatching { File(c.filesDir,"transfers/$id/work").walkTopDown().any { it.isFile && it.length()>0 } }.getOrDefault(false)
    fun recoveryRoot(c: Context,id: String): File = workDirectory(c,id,"recording")
    @Synchronized fun discardRetained(c: Context,id: String) {
        if(owns(c,id) || jobs(c).any { it.optString("id")==id && FragmentDiagnosticJob.pending(it) }) return
        File(c.filesDir,"transfers/$id/work").deleteRecursively()
        jobs(c).find { it.optString("id")==id }?.let { event(it,"User removed retained recovery files") };save(c)
    }
    @Synchronized fun previousExport(c: Context,id: String,source: File,signature: String=""): android.net.Uri? {
        val exports=jobs(c).find { it.optString("id")==id }?.optJSONArray("exports") ?: return null
        val receipt=(0 until exports.length()).map { exports.getJSONObject(it) }.lastOrNull {
            it.optString("path")==source.absolutePath && it.optString("name")==source.name && it.optLong("bytes")==source.length() && (if(signature.isBlank()) it.optLong("modified")==source.lastModified() else it.optString("signature")==signature)
        } ?: return null
        val uri=android.net.Uri.parse(receipt.getString("uri"))
        return runCatching { c.contentResolver.openFileDescriptor(uri,"r")?.use { uri } }.getOrNull()
    }
    @Synchronized fun reserve(c: Context,request: Intent,title: String,monitor: String=""): String? {
        initialize(c);if(!TransferRules.canReserve(jobs(c),paused(c),TransferSettings.limit(c))) return null
        val id=submit(c,request,title,true,monitor,true)
        val lease=UUID.randomUUID().toString()
        jobs(c).first { it.getString("id")==id }.put("state","reserved").put("lease",lease);save(c)
        // A crashed/abandoned JS setup cannot hold the queue indefinitely.
        main.postDelayed({ synchronized(this) { val j=jobs(c).find { it.optString("id")==id };if(j?.optString("state")=="reserved" && j.optString("lease")==lease) complete(c,id,"Browser capture setup timed out","",false,lease) } },30_000)
        return id
    }
    @Synchronized fun launchReserved(c: Context,id: String,request: Intent,lease: String) {
        check(owns(c,id,lease)) { "Browser setup attempt expired" }
        val j=jobs(c).first { it.optString("id")==id };check(j.optString("state")=="reserved")
        j.put("state","running").put("engine_started",false);save(c)
        try { ContextCompat.startForegroundService(c,Intent(request).putExtra(JOB,id).putExtra(LEASE,lease)) }
        catch(e: Exception) { complete(c,id,"Browser recording could not start: ${e.javaClass.simpleName}","",false,lease);throw e }
    }
    @Synchronized fun started(c: Context,id: String,lease: String) {
        if(!owns(c,id,lease)) return
        jobs(c).first { it.optString("id")==id }.put("engine_started",true);save(c)
    }
    @Synchronized fun finalizing(c: Context,id: String,status: String,lease: String) {
        if(!owns(c,id,lease)) return
        jobs(c).first { it.optString("id")==id }.put("state","finalizing").put("detail",safe(status));save(c)
    }
    @Synchronized fun owns(c: Context,id: String,lease: String=""): Boolean = TransferRules.acceptsCompletion(jobs(c),id,lease)
    @Synchronized fun dispatch(c: Context) {
        initialize(c)
        jobs(c).filter { it.optString("state")=="held" && it.optLong("retry_at")>0 && it.optLong("retry_at")<=System.currentTimeMillis() }.forEach { j ->
            val discovery=MonitoringStore.jobs(c).find { it.optString("id")==j.optString("monitor") }
            if(discovery!=null && discovery.optString("state")!="dismissed" && MonitoringStore.get(c,discovery.optString("rule"))?.enabled==true) {
                j.put("state","queued").remove("retry_at")
                event(j,"Automatic media preparation retry ready")
            }
        }
        save(c)
        if(paused(c)||jobs(c).count { it.optString("state") in TransferRules.activeStates }>=TransferSettings.limit(c)) return
        // RecoveryExportService has one active exporter. Keep further recovery
        // jobs queued while allowing ordinary downloads/recordings to use free slots.
        fun dispatchable(): List<JSONObject> {
            val all=jobs(c)
            fun recovery(job: JSONObject)=runCatching { Intent.parseUri(job.getString("payload"),Intent.URI_INTENT_SCHEME).component?.className==RecoveryExportService::class.java.name }.getOrDefault(false)
            return if(all.any { it.optString("state") in TransferRules.activeStates && recovery(it) })
                all.filterNot { it.optString("state")=="queued" && recovery(it) } else all
        }
        var candidate=TransferRules.next(dispatchable(),paused(c),TransferSettings.limit(c))
        while(candidate!=null) {
            val request=Intent.parseUri(candidate.getString("payload"),Intent.URI_INTENT_SCHEME)
            try { if(!request.getBooleanExtra("recover_files",false)) StorageGuard.working(c,StorageGuard.expected(request));break }
            catch(e: InsufficientStorage) {
                candidate.put("state","attention").put("storage_blocked",true).put("detail",e.message)
                event(candidate,"Storage preflight blocked start; queue entry and settings retained")
                candidate.optString("monitor").takeIf { it.isNotBlank() }?.let { MonitoringStore.updateJob(c,it,"attention",StorageGuard.MESSAGE) }
                TransferNotifications.show(c,candidate,StorageGuard.MESSAGE,false)
                save(c);candidate=TransferRules.next(dispatchable(),false,TransferSettings.limit(c))
            }
        }
        val j=candidate ?: return
        j.put("state","running").put("engine_started",false).put("lease",UUID.randomUUID().toString()).put("detail","Preparing task");event(j,"Lease acquired; preparing source");save(c)
        val id=j.getString("id");val lease=j.getString("lease")
        main.post { dispatch(c.applicationContext) }
        main.post {
            try {
                if(!owns(c,id,lease)) return@post
                val monitor=j.optString("monitor")
                if(monitor.isNotBlank() && Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME).getBooleanExtra("monitor_probe",false)) { if(!MonitoringAlarmService.handoff(id,lease)) MonitoringHandoffWorker.enqueue(c,id,lease);return@post }
                val request=Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME)
                // Intent.toUri omits ArrayList extras; persist command tokens explicitly.
                val args=j.optJSONArray("arguments") ?: JSONArray()
                request.putStringArrayListExtra(DownloadService.EXTRA_ARGS,ArrayList((0 until args.length()).map { args.getString(it) }))
                val component=request.component?.className.orEmpty()
                require(component in setOf(DownloadService::class.java.name,RecordingService::class.java.name,MonitoringDownloadService::class.java.name,MonitoringRecordingService::class.java.name,RecoveryExportService::class.java.name))
                // A captured URL is safe for immediate handoff only. Reopen the page for deferred live/browser jobs.
                if(!request.getBooleanExtra("recover_files",false) && request.action in setOf(RecordingService.ACTION_BROWSER_START,MonitoringRecordingService.ACTION_BROWSER_START)) {
                    complete(c,id,"Browser element capture needs a fresh browser session","",false,lease);return@post
                }
                if(!request.getBooleanExtra("recover_files",false) && (j.optBoolean("live") || request.getBooleanExtra(DownloadService.EXTRA_BROWSER_CAPTURED,false)) && TransferRules.expiredBrowser(j.getLong("created"),System.currentTimeMillis())) {
                    complete(c,id,"Fresh Browser Assist detection needed before this deferred task can start","",false,lease);return@post
                }
                ContextCompat.startForegroundService(c,request.putExtra(JOB,id).putExtra(LEASE,lease))
            } catch(e: Exception) { complete(c,id,"Could not start. Open the app and retry: ${e.javaClass.simpleName}","",false,lease) }
        }
    }
    /** Release only a pre-engine lease. Never restart an active recording or re-use a stale lease. */
    @Synchronized fun deferMonitoring(c: Context,id: String,lease: String,reason: String) {
        if(!owns(c,id,lease)) return
        val j=jobs(c).first { it.optString("id")==id }
        if(j.optBoolean("engine_started")) return
        val monitor=j.optString("monitor")
        if(monitor.isBlank()) return
        val count=j.optInt("start_failures")+1
        val kind=MonitoringFailure.kind(reason)
        if(kind=="quota") MonitoringExecutionBudget.exhausted(c,reason)
        val delay=MonitoringStartPolicy.delay(j.optBoolean("live"),kind,count)
        val due=if(delay>0) System.currentTimeMillis()+delay else 0L
        j.put("start_failures",count).put("state",if(due>0) "held" else "attention")
            .put("retry_at",due).put("lease","").put("detail",safe(reason))
        event(j,"Media start deferred; attempt=$count; category=$kind; delayMs=$delay; next=$due; "+safe(reason));save(c)
        MonitoringCoordinator.preparationDeferred(c,monitor)
        MonitoringStore.updateJob(c,monitor,if(due>0) "queued" else "attention",safe(reason))
        MonitoringStore.log(c,"media preparation deferred; job=$monitor; category=${MonitoringFailure.kind(reason)}; attempt=$count; next=$due; reason=$reason")
        MonitoringPowerDiagnostics.record(c,"media preparation deferred; job=$monitor")
        if(kind=="quota") MonitoringNotifications.discovery(c,monitor,"Open app to resume", "Android's background transfer allowance is exhausted. Open the app to retry this saved task.")
        if(due>0) {
            MonitoringMediaRetryWorker.schedule(c,id,due)
            if(j.optBoolean("live")) MonitoringAlarmService.shortRetry(id,due)
        }
        else MonitoringNotifications.discovery(c,monitor,"Monitoring needs your help",if(kind=="quota") MonitoringFailure.message(reason) else "Automatic media start retries exhausted; review monitoring diagnostics")
        main.post { dispatch(c.applicationContext) }
    }
    @Synchronized fun preparationEvent(c: Context,id: String,lease: String,message: String,foregroundReady: Boolean=false) {
        if(!owns(c,id,lease)) return
        val j=jobs(c).first { it.optString("id")==id }
        if(foregroundReady) j.put("preparation_foreground_lease",lease)
        event(j,message);save(c)
        MonitoringStore.log(c,"media preparation event; transfer=$id; attempt=${j.optInt("start_failures")+1}; $message")
    }
    /** A new visible session or an observed exemption can make previously blocked starts eligible. */
    @Synchronized fun retryBlockedMonitoring(c: Context,kinds: Set<String> = setOf("background")) {
        initialize(c)
        val blocked=jobs(c).filter { (it.optString("state")=="attention" || (it.optString("state")=="held" && MonitoringFailure.kind(it.optString("detail"))=="quota")) && it.optString("monitor").isNotBlank() && MonitoringFailure.kind(it.optString("detail")) in kinds }
        blocked.forEach { j ->
            val discovery=MonitoringStore.jobs(c).find { it.optString("id")==j.optString("monitor") }
            if(discovery!=null && discovery.optString("state")!="dismissed" && MonitoringStore.get(c,discovery.optString("rule"))?.enabled==true) {
                j.put("state","held").put("start_failures",0).put("retry_at",System.currentTimeMillis()+1000)
                event(j,"Execution opportunity changed; bounded background startup retries restored")
                MonitoringMediaRetryWorker.schedule(c,j.getString("id"),j.getLong("retry_at"))
            }
        }
        if(blocked.isNotEmpty()) save(c)
    }
    private val lastWrite=mutableMapOf<String,Long>()
    @Synchronized fun progress(c: Context,id: String,status: String,log: String,lease: String,percent: Int=0) {
        if(!owns(c,id,lease)) return
        val now=System.currentTimeMillis();if(now-(lastWrite[id] ?: 0L)<1500) return;lastWrite[id]=now
        jobs(c).first { it.optString("id")==id }.put("detail",safe(status)).put("log",safe(log).takeLast(50_000)).put("progress",percent.coerceIn(0,100));save(c)
    }
    private fun event(j: JSONObject,message: String) { j.put("events",(j.optString("events")+"\n"+java.time.Instant.now()+" "+safe(message)).takeLast(16000)) }
    private fun safeTitle(s: String): String = if(s.startsWith("http://") || s.startsWith("https://"))
        runCatching { val u=java.net.URI(s);u.host+u.path }.getOrDefault("Video download") else safe(s)
    private fun safe(s: String) = s.lines().mapNotNull { FfmpegDiagnostic.safeLine(it) }.joinToString("\n")
    @Synchronized fun exported(c: Context,id: String,uri: android.net.Uri,lease: String,source: File?=null,signature: String="") {
        if(!owns(c,id,lease)) return
        val j=jobs(c).find { it.optString("id")==id } ?: return
        j.getJSONArray("files").put(uri.toString())
        if(source!=null) {
            val exports=j.optJSONArray("exports") ?: JSONArray().also { j.put("exports",it) }
            exports.put(JSONObject().put("name",source.name).put("path",source.absolutePath).put("bytes",source.length()).put("modified",source.lastModified()).put("signature",signature).put("uri",uri.toString()))
        }
        save(c)
    }
    @Synchronized fun complete(c: Context,id: String,status: String,log: String,success: Boolean?=null,lease: String="") {
        if(!owns(c,id,lease)) return
        val j=jobs(c).first { it.getString("id")==id }
        if(FragmentDiagnosticJob.pending(j)) {
            val repair=FragmentDiagnosticJob.operation(j)=="repair"
            FragmentDiagnosticJob.restore(j,safe(status))
            if(repair) j.put("retained_recovery_log",safe(log).takeLast(50000))
            event(j,"Retained-media action finished; $status");save(c)
            TransferNotifications.show(c,j,status,false)
            main.postDelayed({ dispatch(c.applicationContext) },750)
            return
        }
        val ok=success ?: ((status.startsWith("Saved ") && !status.startsWith("Saved with missing segments")) || status.startsWith("Recording saved") || status.startsWith("Partial recording saved"))
        if(j.has("partial_download_payload")) { j.put("payload",j.getString("partial_download_payload"));j.remove("partial_download_payload") }
        j.put("state",if(ok) "saved" else if(status.contains("cancel",true)||status.contains("discard",true)||status.startsWith("Download stopped")) "canceled" else "attention")
            .put("detail",safe(status)).put("log",safe(log).takeLast(50_000)).put("finished",System.currentTimeMillis())
        j.put("storage_blocked",!ok && StorageGuard.failure(status+"\n"+log))
        if(j.optBoolean("storage_blocked")) j.put("detail",StorageGuard.MESSAGE)
        event(j,"Lease released after cleanup; $status");save(c)
        if(j.optString("monitor").isNotBlank()) MonitoringCoordinator.finished(c,j.optString("monitor"),status)
        TransferNotifications.show(c,j,status,false)
        // Dispatch on the UI loop after the service's cleanup callback returns.
        main.postDelayed({ dispatch(c.applicationContext) },750)
    }
    @Synchronized fun change(c: Context,id: String,action: String) {
        val list=jobs(c).toMutableList();val j=list.find { it.optString("id")==id } ?: return
        if(FragmentDiagnosticJob.pending(j) && action !in setOf("first","up","down")) {
            if(action=="dismiss" && !owns(c,id)) {
                FragmentDiagnosticJob.restore(j,"Retained-media action canceled before start; original media and cache kept")
                event(j,"Diagnostic export canceled before start");save(c);dispatch(c)
            }
            return
        }
        when(action) {
            "first","up","down" -> {
                if(j.optString("state") in TransferRules.activeStates) return
                val old=list.indexOf(j)
                fun waiting(item: JSONObject)=item.optString("state") in setOf("queued","held")
                val index=when(action) {
                    "first"->0
                    "up"->list.take(old).indexOfLast(::waiting).takeIf { it>=0 } ?: old
                    else->list.drop(old+1).indexOfFirst(::waiting).takeIf { it>=0 }?.let { old+1+it } ?: old
                }
                list.remove(j);list.add(index.coerceAtMost(list.size),j);state.put("jobs",JSONArray(list));if(action=="first") j.put("state","queued")
            }
            "retry" -> { if(owns(c,id)) return;if(j.has("partial_download_payload")) { j.put("payload",j.getString("partial_download_payload"));j.remove("partial_download_payload") };j.put("retry_count",j.optInt("retry_count")+1).put("state","queued").put("created",0).put("start_failures",0).put("storage_blocked",false).remove("retry_at") }
            "repair_wrapped_video" -> {
                if(owns(c,id) || j.optBoolean("live") || !hasRetained(c,id) || j.optString("state") in setOf("queued","held")) return
                val original=Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME)
                if(original.getBooleanExtra(DownloadService.EXTRA_BROWSER_CAPTURED,false)) return
                val request=Intent(c,RecoveryExportService::class.java).setAction(RecoveryExportService.SAVE)
                    .putExtra("recover_files",true).putExtra("repair_wrapped_video",true)
                    .putExtra("partial_monitor",j.optString("monitor").isNotBlank())
                FragmentDiagnosticJob.begin(j,request.toUri(Intent.URI_INTENT_SCHEME),System.currentTimeMillis(),"repair")
            }
            "export_fragment_samples" -> {
                if(owns(c,id) || j.optBoolean("live") || !hasRetained(c,id) || j.optString("state") in setOf("queued","held")) return
                val request=Intent(c,RecoveryExportService::class.java).setAction(RecoveryExportService.SAVE)
                    .putExtra("recover_files",true).putExtra("fragment_samples",true)
                FragmentDiagnosticJob.begin(j,request.toUri(Intent.URI_INTENT_SCHEME),System.currentTimeMillis(),"samples")
            }
            "export_fragment_diagnostics" -> {
                if(owns(c,id) || j.optBoolean("live") || !hasRetained(c,id) || j.optString("state") in setOf("queued","held")) return
                val request=Intent(c,RecoveryExportService::class.java).setAction(RecoveryExportService.SAVE)
                    .putExtra("recover_files",true).putExtra("fragment_diagnostics",true)
                FragmentDiagnosticJob.begin(j,request.toUri(Intent.URI_INTENT_SCHEME),System.currentTimeMillis())
            }
            "save_partial" -> {
                if(owns(c,id) || j.optBoolean("live") || !hasRetained(c,id)) return
                val original=Intent.parseUri(j.optString("partial_download_payload",j.getString("payload")),Intent.URI_INTENT_SCHEME)
                if(original.getBooleanExtra(DownloadService.EXTRA_BROWSER_CAPTURED,false)) return
                if(!j.has("partial_download_payload")) j.put("partial_download_payload",j.getString("payload"))
                val request=Intent(c,RecoveryExportService::class.java).setAction(RecoveryExportService.SAVE)
                    .putExtra("recover_files",true).putExtra("partial_download",true)
                    .putExtra("partial_audio_only",original.getStringExtra(DownloadService.EXTRA_PRESET) in setOf(DownloadService.PRESET_MP3,DownloadService.PRESET_M4A))
                    .putExtra("partial_monitor",j.optString("monitor").isNotBlank())
                j.put("payload",request.toUri(Intent.URI_INTENT_SCHEME)).put("state","queued").put("created",System.currentTimeMillis())
            }
            "save_recovered" -> {
                if(owns(c,id) || !hasRetained(c,id)) return
                if(!j.has("capture_payload")) j.put("capture_payload",j.getString("payload"))
                val request=Intent(c,RecoveryExportService::class.java).setAction(RecoveryExportService.SAVE).putExtra("recover_files",true)
                j.put("payload",request.toUri(Intent.URI_INTENT_SCHEME)).put("state","queued").put("created",System.currentTimeMillis())
            }
            "dismiss" -> { if(owns(c,id)) return;j.put("state","dismissed") }
        }
        event(j,"User queue action: $action");save(c);dispatch(c)
    }
    /** Drop waiting jobs belonging to removed monitoring rules; never stop the active lease. */
    @Synchronized fun dismissMonitoring(c: Context,monitorIds: Set<String>): Set<String> {
        val retained=mutableSetOf<String>()
        jobs(c).filter { it.optString("monitor") in monitorIds }.forEach { j ->
            if(j.optString("state") in TransferRules.activeStates) retained.add(j.optString("monitor"))
            else if(j.optString("state") in setOf("queued","held","attention")) {
                j.put("state","dismissed");event(j,"Source/rule removed; waiting task dismissed; files retained")
            }
        }
        save(c);return retained
    }
    @Synchronized fun recordNow(c: Context,id: String) {
        if(!TransferRules.canPrioritize(jobs(c).find { it.optString("id")==id })) return
        val current=active(c);state.put("paused",true);change(c,id,"first");state.put("paused",false);save(c)
        if(current!=null && current.optString("id")!=id && jobs(c).count { it.optString("state") in TransferRules.activeStates }>=TransferSettings.limit(c)) stop(c,current.getString("id")) else dispatch(c)
    }
    @Synchronized fun stop(c: Context,id: String="") {
        val j=if(id.isBlank()) active(c) else jobs(c).find { it.optString("id")==id && it.optString("state") in TransferRules.activeStates };if(j==null) return
        if(j.optString("state")=="reserved" || !j.optBoolean("engine_started")) { complete(c,j.getString("id"),"Browser setup canceled","",false);return }
        if(j.optString("state")=="finalizing") return
        val request=Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME)
        if(request.component?.className==RecoveryExportService::class.java.name) {
            c.startService(Intent(c,RecoveryExportService::class.java).setAction(RecoveryExportService.CANCEL));return
        }
        val monitoring=request.component?.className?.contains("Monitoring")==true
        val live=j.optBoolean("live")
        val type=if(live) { if(monitoring) MonitoringRecordingService::class.java else RecordingService::class.java } else { if(monitoring) MonitoringDownloadService::class.java else DownloadService::class.java }
        j.put("state","stopping").put("detail",if(live) "Stopping and saving before next task" else "Stopping current download");event(j,"User requested stop; retaining lease through save and cleanup");save(c)
        c.startService(Intent(c,type).putExtra(JOB,j.getString("id")).setAction(if(live) { if(monitoring) MonitoringRecordingService.ACTION_STOP_SAVE else RecordingService.ACTION_STOP_SAVE } else { if(monitoring) MonitoringDownloadService.ACTION_STOP else DownloadService.ACTION_STOP }))
    }
}
