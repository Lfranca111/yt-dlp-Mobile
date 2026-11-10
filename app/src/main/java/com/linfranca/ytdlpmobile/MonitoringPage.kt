package com.linfranca.ytdlpmobile

import android.app.Activity
import android.content.*
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Third pager surface; it owns all monitoring UI and does not bind Main's form or state. */
class MonitoringPage(private val activity: AppCompatActivity) {
    val root=StableScrollView(activity)
    private val body=AdaptiveContentLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(36),dp(20),dp(36)) }
    val topIndicator=TextView(activity)
    val bottomIndicator=TextView(activity)
    private val comics=ComicMonitoringPanel(activity)
    private val cards=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val handler=Handler(Looper.getMainLooper())
    private var started=false
    private val renderGate=MonitoringRenderGate()
    private val refresh=object: Runnable { override fun run() { render();if(started) handler.postDelayed(this,3000) } }
    private val receiver=object: BroadcastReceiver() { override fun onReceive(c: Context?,i: Intent?) { render() } }
    private var browserJob=""
    private val browser=activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK) result.data?.let { data ->
            val monitorJob = browserJob
            if (BrowserDownloadBatch.consume(activity, data) { batch ->
                BrowserDownloadBatch.enqueue(activity, batch, monitorJob)
                render()
            }) return@registerForActivityResult
            val url=data.getStringExtra(MonitoringBrowserSessionActivity.EXTRA_MEDIA_URL).orEmpty()
            if(url.isNotBlank()) {
                val request=Intent(activity,MonitoringDownloadService::class.java).setAction(MonitoringDownloadService.ACTION_DOWNLOAD)
                    .putExtra(MonitoringDownloadService.EXTRA_URL,url)
                    .putExtra(MonitoringDownloadService.EXTRA_PRESET,MonitoringDownloadService.PRESET_BEST)
                    .putExtra(MonitoringDownloadService.EXTRA_BROWSER_CAPTURED,true)
                    .putExtra(MonitoringDownloadService.EXTRA_REFERER,data.getStringExtra(MonitoringBrowserSessionActivity.EXTRA_REFERER))
                    .putExtra(MonitoringDownloadService.EXTRA_COOKIES,data.getStringExtra(MonitoringBrowserSessionActivity.EXTRA_COOKIES))
                    .putExtra(MonitoringDownloadService.EXTRA_USER_AGENT,data.getStringExtra(MonitoringBrowserSessionActivity.EXTRA_USER_AGENT))
                    .putExtra(MonitoringDownloadService.EXTRA_HEADERS_JSON,data.getStringExtra(MonitoringBrowserSessionActivity.EXTRA_HEADERS_JSON))
                    .putExtra(MonitoringDownloadService.EXTRA_DOWNLOAD_INFO_JSON,data.getStringExtra(MonitoringBrowserSessionActivity.EXTRA_DOWNLOAD_INFO_JSON))
                    .putExtra(MonitoringDownloadService.EXTRA_DOWNLOAD_DIAGNOSTICS,data.getStringExtra(MonitoringBrowserSessionActivity.EXTRA_DOWNLOAD_DIAGNOSTICS))
                try { TransferQueue.submit(activity,request,"Monitoring browser download",false,browserJob) }
                catch(e: Exception) { MonitoringCoordinator.finished(activity,"","Download could not start: ${e.message}") }
            }
        }
        render()
    }
    private val sourceImport=activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) Thread {
            val result=runCatching {
                val bytes=activity.contentResolver.openInputStream(uri)?.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(out.size()<=CustomSources.LIMIT) {
                        val n=input.read(buffer,0,minOf(buffer.size,CustomSources.LIMIT+1-out.size()));if(n<0) break;out.write(buffer,0,n)
                    }
                    out.toByteArray()
                } ?: error("Could not open source")
                require(bytes.size<=CustomSources.LIMIT) { "Source file exceeds 256 KB" }
                CustomSources.imported(bytes.toString(Charsets.UTF_8))
            }
            activity.runOnUiThread {
                result.onSuccess { j ->
                    AlertDialog.Builder(activity).setTitle("Import custom source?")
                        .setMessage("${j.getString("name")}\n${j.getString("url")}\nMode: ${j.getString("mode")}\nNo login data is imported. Test locally before monitoring.")
                        .setPositiveButton("Import & inspect") { _,_->
                            CustomSources.put(activity,j)
                            activity.startActivity(Intent(activity,CustomSourceActivity::class.java).putExtra("source",j.getString("id")))
                        }.setNegativeButton("Cancel",null).show()
                }.onFailure { toast(MonitoringStore.error(it)) }
            }
        }.start()
    }
    init {
        root.addView(body)
        body.addView(topIndicator)
        body.addView(text("Monitoring download",30f))
        body.addView(text("Watch channels for new uploads or live streams. Each link has its own schedule. Checks are approximate and may be delayed by Android, network availability, or website limits."))
        body.addView(button("Shared queue & history / Logs") { activity.startActivity(Intent(activity,TransferQueueActivity::class.java)) })
        body.addView(button("Add monitoring link") { edit(null) })
        body.addView(button("Copy detailed monitoring log") {
            Thread {
                val result=runCatching { MonitoringStore.recentLog(activity) }
                activity.runOnUiThread {
                    try {
                        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Recent monitoring diagnostics",result.getOrThrow()))
                        toast("Recent log copied. Save full log for all retained events.")
                    } catch(e: Exception) { toast("Copy failed. Use Save full log. "+MonitoringStore.error(e)) }
                }
            }.start()
        })
        body.addView(button("Save full detailed monitoring log") { Thread { exportLog() }.start() })
        body.addView(button("Crash reports") { activity.startActivity(Intent(activity,CrashReportsActivity::class.java)) })
        body.addView(button("Set up custom monitoring source") { activity.startActivity(Intent(activity,CustomSourceActivity::class.java)) })
        body.addView(button("Import custom monitoring source") { sourceImport.launch(arrayOf("application/json","text/plain","application/octet-stream")) })
        body.addView(button("Background / battery settings") {
            activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+activity.packageName)))
        })
        body.addView(android.widget.CheckBox(activity).apply {
            text="Increase interval after repeated failures"
            isChecked=MonitoringSettings.backoff(activity)
            setOnCheckedChangeListener { _, enabled ->
                if(enabled) androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("Allow longer monitoring intervals?")
                    .setMessage("Repeated failures can extend the selected interval and miss short live streams. Short recovery retries are separate. Android and website restrictions still apply.")
                    .setPositiveButton("Enable") { _,_ -> MonitoringSettings.setBackoff(activity,true) }
                    .setNegativeButton("Cancel") { _,_ -> isChecked=false }
                    .setOnCancelListener { isChecked=false }.show()
                else MonitoringSettings.setBackoff(activity,false)
            }
        })
        cards.id=View.generateViewId()
        body.splitAtId=cards.id
        body.addView(cards)
        body.addView(comics.root)
        body.addView(bottomIndicator)
        MonitoringScheduler.initialize(activity)
    }
    fun start() {
        if(started) return;started=true
        ContextCompat.registerReceiver(activity,receiver,IntentFilter(MonitoringStore.ACTION_STATE),ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post(refresh)
    }
    fun stop() { if(!started) return;started=false;handler.removeCallbacks(refresh);activity.unregisterReceiver(receiver) }
    private fun render() {
        comics.render()
        val sources=CustomSources.all(activity)
        val rules=MonitoringStore.rules(activity)
        val jobs=MonitoringStore.jobs(activity)
        val now=System.currentTimeMillis()
        val snapshot=org.json.JSONArray().put(org.json.JSONArray(sources.map { it.optString("id")+":"+it.optString("name") }))
            .put(org.json.JSONArray(rules.map { r -> org.json.JSONObject().put("id",r.id).put("name",r.name).put("url",r.url)
                .put("mode",r.mode).put("automatic",r.automatic).put("interval",r.interval).put("enabled",r.enabled)
                .put("next",r.next).put("last",r.last).put("status",r.status).put("source",r.customSource)
                .put("scheduleReason",r.customState.optString("scheduleReason"))
                .put("pending",r.customState.optJSONObject("pending")) }))
            .put(org.json.JSONArray(jobs.takeLast(60)))
            .put(org.json.JSONArray(rules.map { if(it.enabled && it.next>0 && now-it.next>120000) (now-it.next)/60000 else 0 }))
            .put(MonitoringTaskState.running).put(MonitoringTaskState.status)
            .put(MonitoringTaskState.progress).put(MonitoringTaskState.operation)
            .put(MonitoringTaskState.recording403RecoveryAvailable).toString()
        if(!renderGate.changed(snapshot)) return
        cards.removeAllViews()
        sources.forEach { source ->
            cards.addView(button("Custom source: ${source.getString("name")} — Edit / Test / Export") {
                activity.startActivity(Intent(activity,CustomSourceActivity::class.java).putExtra("source",source.getString("id")))
            })
            cards.addView(button("Remove source: ${source.getString("name")}") { removeSource(source) })
        }
        if(MonitoringTaskState.running) {
            cards.addView(text("Active monitoring task\n${MonitoringTaskState.status}"))
            cards.addView(ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=100
                progress=MonitoringTaskState.progress.coerceIn(0,100)
                isIndeterminate=progress==0
                contentDescription=if(isIndeterminate) "Monitoring task in progress" else "Monitoring progress $progress percent"
            })
            cards.addView(button(if(MonitoringTaskState.operation==MonitoringTaskState.OPERATION_RECORD) "Stop monitoring recording & save" else "Stop monitoring download") {
                val recording=MonitoringTaskState.operation==MonitoringTaskState.OPERATION_RECORD
                activity.startService(Intent(activity,if(recording) MonitoringRecordingService::class.java else MonitoringDownloadService::class.java)
                    .setAction(if(recording) MonitoringRecordingService.ACTION_STOP_SAVE else MonitoringDownloadService.ACTION_STOP))
                MonitoringStore.log(activity,"user stopped monitoring media task")
            })
            if(MonitoringTaskState.recording403RecoveryAvailable) cards.addView(button("Retry current recording in Browser Assist") {
                browser.launch(Intent(activity,MonitoringBrowserSessionActivity::class.java)
                    .putExtra(MonitoringBrowserSessionActivity.EXTRA_PAGE_URL,MonitoringTaskState.recordingPageUrl)
                    .putExtra(MonitoringBrowserSessionActivity.EXTRA_MODE,MonitoringBrowserSessionActivity.MODE_RECORD)
                    .putExtra(MonitoringBrowserSessionActivity.EXTRA_RECOVERY,true))
            })
        }
        if(rules.isEmpty()) cards.addView(text("No monitoring links yet. Add a channel or performer page to begin."))
        rules.forEach { r ->
            val card=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(0,dp(20),0,dp(12)) }
            card.addView(text(r.name,22f))
            if(r.enabled && r.next>0 && System.currentTimeMillis()-r.next>120000) card.addView(text("Check overdue by ${(System.currentTimeMillis()-r.next)/60000} minutes. Cause not yet established."))
            if(r.customSource.isNotBlank()) card.addView(button("Edit shared custom-source rules") {
                activity.startActivity(Intent(activity,CustomSourceActivity::class.java).putExtra("source",r.customSource).putExtra("rule",r.id))
            })
            card.addView(text("${CustomSources.method(activity,r)} • ${r.mode} • ${if(r.automatic) "Download discovered items" else "Notify only"}\n${r.url}\nCheck every ${intervalLabel(r.interval)} • ${if(r.enabled) "Enabled" else "Paused"}\nLast: ${time(r.last)}\nNext: ${if(r.enabled) time(r.next) else "Paused"}\n${r.customState.optString("scheduleReason").let { if(it.isBlank()) "" else "$it\n" }}${r.status}"))
            card.addView(button("Browser mode / Saved session settings") { sessionSettings(r) })
            card.addView(button("Test link / Check now") { checkRule(r) })
            if(r.mode in setOf("live","both")) card.addView(button("Retry live stream in Browser Assist") {
                runCatching { MonitoringCoordinator.browserForRule(activity,r) }.onSuccess { intent ->
                    if(intent==null) toast("Monitoring item is no longer available")
                    else { browserJob=intent.getStringExtra("monitor_job").orEmpty();browser.launch(intent) }
                }.onFailure { toast(MonitoringStore.error(it)) }
            })
            if(r.customSource.isNotBlank()) {
                card.addView(text("Automatic checks try the channel extractor, HTML and browser rendering. Open browser manually if sign-in or verification is needed."))
                card.addView(button("Open browser to check") { openCustom(r,true) })
            }
            card.addView(button(if(r.enabled) "Pause monitoring" else "Resume monitoring") {
                r.enabled=!r.enabled;r.generation=System.currentTimeMillis();r.next=0
                MonitoringStore.put(activity,r);MonitoringScheduler.cancel(activity,r.id)
                if(r.enabled) MonitoringScheduler.ensure(activity,r)
                render()
            })
            card.addView(button("Sign in / Inspect channel in Browser Assist") {
                if(r.customSource.isNotBlank()) { openCustom(r,inspect=true);return@button }
                browserJob=""
                browser.launch(Intent(activity,MonitoringBrowserSessionActivity::class.java)
                    .putExtra(MonitoringBrowserSessionActivity.EXTRA_PAGE_URL,r.url)
                    .putExtra(MonitoringBrowserSessionActivity.EXTRA_MODE,MonitoringBrowserSessionActivity.MODE_DOWNLOAD)
                    .putExtra("monitor_signin_rule",r.id))
            })
            val pending=r.customState.optJSONObject("pending")
            if(pending!=null && pending.length()>0) card.addView(button("Review uncertain uploads (${pending.length()})") { review(r) })
            card.addView(button("Edit") { edit(r) })
            card.addView(button("Remove") {
                AlertDialog.Builder(activity).setMessage("Remove this monitoring rule? An active download or recording will continue.")
                    .setPositiveButton("Remove") { _,_->MonitoringScheduler.cancel(activity,r.id);MonitoringStore.remove(activity,r.id);render() }
                    .setNegativeButton("Cancel",null).show()
            })
            cards.addView(card)
        }
        cards.addView(text("Discovered items",24f))
        if(jobs.isEmpty()) cards.addView(text("Future uploads are monitored after the first successful baseline check. Existing uploads are not downloaded automatically."))
        jobs.asReversed().take(60).forEach { job ->
            val id=job.getString("id");val state=job.optString("state")
            cards.addView(text("${job.optString("name")} • ${if(job.optBoolean("live")) "Live stream" else "Upload"}\n${job.optString("title").ifBlank { job.optString("url") }}\n$state — ${job.optString("detail")}"))
            if(state !in setOf("saved","running","dismissed")) {
                cards.addView(button("Start / Retry automatic detection") { MonitoringCoordinator.start(activity,id) })
                cards.addView(button(if(job.optBoolean("live")) "Retry in Browser Assist" else "Open Browser Assist") {
                    val intent=MonitoringCoordinator.browser(activity,id)
                    if(intent==null) toast("Finish the active monitoring task or recording first")
                    else { browserJob=id;browser.launch(intent) }
                })
                cards.addView(button("Dismiss this item") { MonitoringStore.updateJob(activity,id,"dismissed","Dismissed by user");TransferQueue.jobs(activity).filter { it.optString("monitor")==id }.forEach { TransferQueue.change(activity,it.getString("id"),"dismiss") };render() })
            }
        }
        VisualPolish.prepareMonitoring(cards)
    }
    private fun sessionSettings(r: MonitoringRule) {
        if(r.customSource.isNotBlank()) { openCustom(r,inspect=true);return }
        val form=LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12)) }
        val desktop=CheckBox(activity).apply { text="Request desktop site";isChecked=MonitoringSession.mode(activity,r.url)=="desktop";form.addView(this) }
        form.addView(text("Monitoring keeps the shared website login and storage. Multiple channels on the same website use the same current account. Sign in through Inspect channel first."))
        val marker=EditText(activity).apply { hint="Unique signed-in account text (optional, no passwords)";setText(MonitoringSession.marker(activity,r.url));form.addView(this) }
        form.addView(text("This text must appear on the channel only while signed in. Without it, a successful page load proves public access only. Sessions can expire. A fresh-view test does not prove reboot survival."))
        val result=TextView(activity).apply { form.addView(this) }
        val test=button("Save settings & test session reuse") {
            val mode=if(desktop.isChecked) "desktop" else "mobile"
            MonitoringSession.save(activity,r.url,mode,marker.text.toString())
            result.text="Testing in a separate monitoring browser view…"
            java.util.concurrent.Executors.newSingleThreadExecutor().let { executor -> executor.execute {
                val outcome=runCatching { MonitoringSession.test(activity,r.url,mode,{ activity.isFinishing || activity.isDestroyed }) }.getOrElse { "Test could not finish; retry after opening the channel." }
                activity.runOnUiThread { if(!activity.isDestroyed) result.text=outcome };executor.shutdown()
            } }
        }
        form.addView(test)
        AlertDialog.Builder(activity).setTitle("Monitoring browser & session").setView(ScrollView(activity).apply { addView(form) }).setPositiveButton("Close",null).show()
    }
    private fun edit(original: MonitoringRule?) {
        val form=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8)) }
        fun input(label: String,value: String): EditText = EditText(activity).apply { hint=label;setText(value);form.addView(this) }
        val name=input("Name",original?.name.orEmpty())
        val link=input("Channel / performer URL",original?.url.orEmpty()).apply { inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        var builtinSelected=original?.customSource.isNullOrBlank()
        val playbackButton=button("Playback audio / server") {
            val target=link.text.toString().trim()
            if(WebsiteSourceResolver.site(target) in setOf("AniWaves","h123movies")) WebsiteChoiceDialog.choose(activity,target) {
                link.setText(PlaybackChoice.apply(activity,target,true))
            }
        }
        fun showPlayback() { playbackButton.visibility=if(builtinSelected && WebsiteSourceResolver.site(link.text.toString()) in setOf("AniWaves","h123movies")) View.VISIBLE else View.GONE }
        form.addView(playbackButton);showPlayback()
        link.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { showPlayback() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        fun choice(label: String,values: List<String>,selected: Int): Spinner {
            form.addView(text(label));return PanelSpinner(activity).apply {
                adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,values);setSelection(selected.coerceAtLeast(0));form.addView(this)
            }
        }
        val sources=CustomSources.all(activity)
        val source=choice("Detection source",listOf("Built-in / general handler")+sources.map { it.getString("name") },
            sources.indexOfFirst { it.getString("id")==original?.customSource }+1)
        val modes=listOf("uploads","live","both")
        val mode=choice("Monitor",listOf("New video uploads","Live streams","Both"),modes.indexOf(original?.mode ?: "uploads"))
        var previousSource=source.selectedItemPosition
        source.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {
                builtinSelected=position==0;showPlayback()
                if(position!=previousSource) sources.getOrNull(position-1)?.let { mode.setSelection(modes.indexOf(it.getString("mode"))) }
                previousSource=position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        form.addView(text("Custom sources reuse a tested website layout. Enter this channel's URL above; selecting a source does not replace it. Different layouts may need their own setup."))
        val intervals=MonitoringPolicy.INTERVALS.toList()
        val interval=choice("Check every",intervals.map { intervalLabel(it) },intervals.indexOf(original?.interval ?: 15))
        val automatic=CheckBox(activity).apply { text="Automatically download / record when possible";isChecked=original?.automatic ?: false;form.addView(this) }
        form.addView(text("If a website or Android needs interaction, a notification opens this page so you can use Browser Assist. Allow notifications in Android settings. A long check interval can miss short broadcasts."))
        val window=CheckBox(activity).apply { text="Only check during an expected timeframe";isChecked=(original?.start ?: -1)>=0;form.addView(this) }
        val start=input("Start HH:mm",if((original?.start ?: -1)>=0) minuteText(original!!.start) else "08:00")
        val end=input("End HH:mm",if((original?.end ?: -1)>=0) minuteText(original!!.end) else "22:00")
        val days=mutableListOf<CheckBox>()
        listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday").forEachIndexed { i,label ->
            days.add(CheckBox(activity).apply { text=label;isChecked=original?.days?.contains(i+1) ?: true;form.addView(this) })
        }
        form.addView(text("Timezone: ${original?.zone ?: ZoneId.systemDefault().id}. Overnight windows are supported. Pausing monitoring does not stop a capture already running."))
        val dialog=AlertDialog.Builder(activity).setTitle(if(original==null) "Add monitoring link" else "Edit monitoring link")
            .setView(ScrollView(activity).apply { addView(form) }).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val selectedSource=sources.getOrNull(source.selectedItemPosition-1)
                    selectedSource?.let {
                        require(it.optLong("testedAt")>0) { "Test this imported/custom source before using it" }
                        require(link.text.toString().isNotBlank()) { "Enter the channel URL to monitor; the custom source example is not used automatically" }
                    }
                    val url=MonitoringLinkPolicy.input(link.text.toString(),selectedSource==null).let { if(selectedSource==null) PlaybackChoice.apply(activity,it) else it }
                    if(selectedSource==null) MonitoringLinkPolicy.validateChannel(url)
                    else {
                        val bound=CustomSourceBinding.bind(selectedSource,url,modes[mode.selectedItemPosition])
                        require(bound.getInt("schema")<4 || CustomSetupPolicy.ready(bound)) { "This source has not been tested for the selected uploads/live mode. Test that section in custom source setup." }
                    }
                    require(selectedSource!=null || java.net.URI(url).path.trim('/').isNotBlank()) { "Use a channel/performer link rather than the site home page" }
                    val selectedDays=days.mapIndexedNotNull { i,b -> if(b.isChecked) i+1 else null }.toSet()
                    require(selectedDays.isNotEmpty()) { "Select at least one day" }
                    val r=original ?: MonitoringRule(name=name.text.toString().trim().ifBlank { MonitoringPolicy.handler(url) },url=url)
                    val changed=r.url!=url || r.mode!=modes[mode.selectedItemPosition] || r.customSource!=selectedSource?.optString("id").orEmpty()
                    if(changed) CustomSourceBinding.resetChannel(r)
                    r.customSource=selectedSource?.optString("id").orEmpty()
                    r.name=name.text.toString().trim().ifBlank { selectedSource?.optString("name") ?: MonitoringPolicy.handler(url) };r.url=url
                    r.mode=modes[mode.selectedItemPosition];r.interval=intervals[interval.selectedItemPosition];r.automatic=automatic.isChecked
                    r.days=selectedDays;r.start=if(window.isChecked) parseMinute(start.text.toString()) else -1
                    r.end=if(window.isChecked) parseMinute(end.text.toString()) else -1
                    r.generation=System.currentTimeMillis();r.next=0
                    check(MonitoringWorker.gate.tryLock()) { "A monitoring check is running. Wait for it to finish, then save this edit." }
                    try {
                        if(changed && original!=null) MonitoringCoordinator.channelChanged(activity,r.id)
                        MonitoringScheduler.cancel(activity,r.id);MonitoringStore.put(activity,r);MonitoringScheduler.ensure(activity,r)
                    } finally { MonitoringWorker.gate.unlock() }
                    MonitoringStore.log(activity,"rule saved; rule=${r.id}; handler=${MonitoringPolicy.handler(url)}; mode=${r.mode}; interval=${r.interval}m; automatic=${r.automatic}")
                    dialog.dismiss();checkRule(r);render()
                } catch(e: Exception) { toast(e.message ?: "Invalid monitoring settings") }
            }
        }
        dialog.show()
    }
    private fun openCustom(r: MonitoringRule,check: Boolean=false,inspect: Boolean=false) {
        activity.startActivity(Intent(activity,CustomSourceActivity::class.java).putExtra("source",r.customSource).putExtra("rule",r.id).putExtra("check_now",check).putExtra("check_only",check || inspect))
    }
    private fun checkRule(r: MonitoringRule) {
        try {
            val hint=MonitoringCheckPolicy.route(r.customSource.isNotBlank(),r.customSource.isNotBlank() && CustomSources.needsBrowser(activity,r))
            MonitoringStore.log(activity,"manual check queued; rule=${r.id}; custom=${r.customSource.isNotBlank()}; savedHint=$hint; automatic fallbacks enabled; no window launched")
            MonitoringScheduler.checkNow(activity,r);toast("Check queued; results appear here")
        } catch(e: Exception) { AlertDialog.Builder(activity).setMessage(MonitoringStore.error(e)).setPositiveButton("OK",null).show() }
    }
    private fun removeSource(source: org.json.JSONObject) {
        val id=source.getString("id")
        val linked=MonitoringStore.rules(activity).filter { it.customSource==id }
        AlertDialog.Builder(activity).setTitle("Remove ${source.getString("name")}?")
            .setMessage(if(linked.isEmpty()) "Remove this source? Downloaded files are kept." else
                "This also removes ${linked.size} linked monitoring rule(s):\n"+linked.joinToString("\n") { it.name }+"\nWaiting downloads from these rules are dismissed. Downloaded files and active downloads/recordings are kept.")
            .setPositiveButton(if(linked.isEmpty()) "Remove source" else "Remove source & rules") { _,_->
                Thread {
                    val result=runCatching { CustomSources.remove(activity,id,true) }
                    activity.runOnUiThread {
                        result.onSuccess { toast("Source and linked rules removed; files kept");render() }
                            .onFailure { AlertDialog.Builder(activity).setTitle("Source was not removed").setMessage(MonitoringStore.error(it)).setPositiveButton("OK",null).show() }
                    }
                }.start()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun review(original: MonitoringRule) {
        val pending=original.customState.optJSONObject("pending") ?: return
        val items=pending.keys().asSequence().map { pending.getJSONObject(it) }.toList()
        val selected=BooleanArray(items.size)
        AlertDialog.Builder(activity).setTitle("Uncertain: these may be existing videos")
            .setMultiChoiceItems(items.map { it.optString("title").ifBlank { "Video" }+"\n"+it.optString("url") }.toTypedArray(),selected) { _,i,on -> selected[i]=on }
            .setPositiveButton("Download selected") { _,_->
                Thread {
                    val result=runCatching {
                        check(MonitoringWorker.gate.tryLock()) { "A check is running. Try review again shortly." }
                        try {
                            val r=MonitoringStore.get(activity,original.id) ?: error("Rule removed")
                            val current=r.customState.optJSONObject("pending") ?: org.json.JSONObject()
                            items.filterIndexed { i,_->selected[i] }.forEach { item ->
                                if(current.has(item.getString("id"))) {
                                    val job=MonitoringStore.enqueue(activity,r,MonitoringItem(item.getString("id"),item.getString("url"),item.optString("title")),false)
                                    MonitoringCoordinator.start(activity,job);current.remove(item.getString("id"))
                                }
                            }
                            MonitoringStore.put(activity,r)
                            MonitoringStore.log(activity,"uncertain items reviewed; rule=${r.id}; selected=${selected.count { it }}; explicit download requested")
                        } finally { MonitoringWorker.gate.unlock() }
                    }
                    activity.runOnUiThread { result.onFailure { toast(MonitoringStore.error(it)) };render() }
                }.start()
            }.setNeutralButton("Mark all as existing") { _,_->
                Thread {
                    val result=runCatching {
                        check(MonitoringWorker.gate.tryLock()) { "A check is running. Try review again shortly." }
                        try {
                        MonitoringStore.mutate(activity,original.id) { it.customState.put("pending",org.json.JSONObject()) }
                        MonitoringStore.log(activity,"uncertain items reviewed; rule=${original.id}; all marked existing")
                        } finally { MonitoringWorker.gate.unlock() }
                    }
                    activity.runOnUiThread { result.onFailure { toast(MonitoringStore.error(it)) };render() }
                }.start()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun exportLog() {
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"monitoring-${System.currentTimeMillis()}-diagnostic-log.txt")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"text/plain")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/yt-dlp Mobile/Monitoring")
            put(android.provider.MediaStore.Downloads.IS_PENDING,1)
        }
        var uri: android.net.Uri?=null
        try {
            uri=activity.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Could not create log")
            activity.contentResolver.openOutputStream(uri)?.use { MonitoringStore.copyLogTo(activity,it) } ?: error("Could not write log")
            values.clear();values.put(android.provider.MediaStore.Downloads.IS_PENDING,0);activity.contentResolver.update(uri,values,null,null)
            toast("Saved to Downloads/yt-dlp Mobile/Monitoring")
        } catch(e: Exception) { uri?.let { activity.contentResolver.delete(it,null,null) };toast("Could not save log: ${e.message}") }
    }
    private fun text(value: String,size: Float=16f)=TextView(activity).apply { text=value;textSize=size;setPadding(0,dp(8),0,dp(8));setTextIsSelectable(true) }
    private fun button(label: String,action: ()->Unit)=com.google.android.material.button.MaterialButton(activity).apply { text=label;setOnClickListener { action() } }
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun toast(s: String) { activity.runOnUiThread { Toast.makeText(activity,s,Toast.LENGTH_LONG).show() } }
    private fun intervalLabel(n: Int)=when { n==1440 -> "24 hours";n>=60 -> "${n/60} hour${if(n>60) "s" else ""}";else -> "$n minutes" }
    private fun minuteText(n: Int)="%02d:%02d".format(n/60,n%60)
    private fun parseMinute(s: String): Int { val t=java.time.LocalTime.parse(s.trim(),DateTimeFormatter.ofPattern("HH:mm"));return t.hour*60+t.minute }
    private fun time(t: Long)=if(t==0L) "Not checked / scheduled yet" else Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
}
