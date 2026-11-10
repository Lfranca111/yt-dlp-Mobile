package com.linfranca.ytdlpmobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog

class TransferQueueActivity : DisplayActivity() {
    private var browserJob=""
    private val browser=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==RESULT_OK) result.data?.let { data ->
            val retryJob = browserJob
            if (BrowserDownloadBatch.consume(this, data) { batch ->
                val monitor = TransferQueue.snapshot(this).find { it.optString("id") == retryJob }
                    ?.optString("monitor").orEmpty()
                BrowserDownloadBatch.enqueue(this, batch, monitor)
            }) return@registerForActivityResult
            val url=data.getStringExtra(BrowserSessionActivity.EXTRA_MEDIA_URL).orEmpty()
            if(url.isNotBlank()) {
                val old=TransferQueue.jobs(this).find { it.optString("id")==browserJob }
                val monitor=old?.optString("monitor").orEmpty()
                val request=Intent(this,if(monitor.isBlank()) DownloadService::class.java else MonitoringDownloadService::class.java)
                    .setAction(if(monitor.isBlank()) DownloadService.ACTION_DOWNLOAD else MonitoringDownloadService.ACTION_DOWNLOAD)
                    .putExtra(DownloadService.EXTRA_URL,url).putExtra(DownloadService.EXTRA_PRESET,DownloadService.PRESET_BEST)
                    .putExtra(DownloadService.EXTRA_BROWSER_CAPTURED,true)
                    .putExtra(DownloadService.EXTRA_REFERER,data.getStringExtra(BrowserSessionActivity.EXTRA_REFERER))
                    .putExtra(DownloadService.EXTRA_COOKIES,data.getStringExtra(BrowserSessionActivity.EXTRA_COOKIES))
                    .putExtra(DownloadService.EXTRA_USER_AGENT,data.getStringExtra(BrowserSessionActivity.EXTRA_USER_AGENT))
                    .putExtra(DownloadService.EXTRA_HEADERS_JSON,data.getStringExtra(BrowserSessionActivity.EXTRA_HEADERS_JSON))
                    .putExtra(DownloadService.EXTRA_DOWNLOAD_INFO_JSON,data.getStringExtra(BrowserSessionActivity.EXTRA_DOWNLOAD_INFO_JSON))
                if(monitor.isBlank()) TransferQueue.change(this,browserJob,"dismiss")
                DownloadDuplicates.submit(this,request,"Fresh browser-assisted download",false,monitor)
            }
        }
    }
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var loading=false
    private var visible=false
    private var signature=""
    private val rowVisuals=VisualPolish.QueueRows()
    private val activeLabels=mutableMapOf<String,TextView>()
    private val activeBars=mutableMapOf<String,ProgressBar>()
    private lateinit var body: LinearLayout
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object: Runnable { override fun run() { render();handler.postDelayed(this,2000) } }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        browserJob=b?.getString("browser_job").orEmpty()
        val scroll=ScrollView(this)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { v,insets -> val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,48,24,48) };scroll.addView(body);setContentView(scroll)
        if(intent.getStringExtra("queue_action")=="record_now") AlertDialog.Builder(this)
            .setTitle("Prioritize this live stream?").setMessage("The current download will stop, or the current recording will stop and save. This live stream starts after saving finishes. Browser Assist may be needed.")
            .setPositiveButton("Stop current & prioritize") { _,_->TransferQueue.recordNow(this,intent.getStringExtra("job").orEmpty());render() }.setNegativeButton("Keep queued",null).show()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("browser_job",browserJob);super.onSaveInstanceState(outState) }
    override fun onStart() { super.onStart();visible=true;handler.post(refresh) }
    override fun onStop() { visible=false;handler.removeCallbacks(refresh);super.onStop() }
    override fun onDestroy() { worker.shutdown();super.onDestroy() }
    private fun text(s: String,size: Float=16f) { body.addView(TextView(this).apply { text=s;textSize=size;setPadding(0,12,0,8) }) }
    private fun button(label: String,action: ()->Unit) { body.addView(Button(this).apply { text=label;setOnClickListener { action();render() } }) }
    private fun queueAction(action: ()->Unit) { worker.execute { action();runOnUiThread { render() } } }
    private fun render() {
        if(loading || !visible) return
        loading=true
        worker.execute {
            try {
                TransferQueue.initialize(this)
                val snapshot=TransferQueue.snapshot(this)
                val foreground=snapshot.filter { it.optString("state") in TransferRules.activeStates || it.optString("state") in setOf("queued","held") || it.optString("id")==intent.getStringExtra("highlight_job") }
                val jobs=(foreground+snapshot.filterNot { it in foreground }.takeLast(100)).distinctBy { it.optString("id") }
                val retained=jobs.filter { it.optString("state") !in TransferRules.activeStates }.associate { it.getString("id") to TransferQueue.hasRetained(this,it.getString("id")) }
                val recoverable=retained.filterValues { it }.keys.associateWith { PartialDownloadCache.recoverable(TransferQueue.workDirectory(this,it,"download")).isNotEmpty() }
                val collections=CollectionJournal.jobs(this)
                val limit=TransferSettings.limit(this);val paused=TransferQueue.paused(this)
                val notifications=getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
                val key=QueuePresentation.signature(jobs)+retained.toString()+recoverable.toString()+collections.toString()+limit+paused+notifications
                runOnUiThread {
                    loading=false
                    if(visible && !isFinishing && !isDestroyed) {
                        if(key==signature) jobs.forEach { j ->
                            val id=j.optString("id")
                            activeLabels[id]?.let { label ->
                                label.text=QueuePresentation.label(j)
                                rowVisuals.style(label,id,j.optString("state"))
                            }
                            activeBars[id]?.apply { progress=j.optInt("progress").coerceIn(0,100);isIndeterminate=progress==0 }
                        } else { signature=key;renderSnapshot(jobs,retained,recoverable,collections,limit,paused,notifications) }
                    }
                }
            } catch(e: Exception) { runOnUiThread { loading=false } }
        }
    }
    private fun renderSnapshot(jobs: List<org.json.JSONObject>,retained: Map<String,Boolean>,recoverable: Map<String,Boolean>,collections: List<org.json.JSONObject>,limit: Int,paused: Boolean,notifications: Boolean) {
        activeLabels.clear();activeBars.clear()
        body.removeAllViews();text("Queue & history",27f)
        if(!notifications) text("Notifications are disabled in Android settings. Enable them for live-stream alerts and task results.")
        text("Maximum ${limit} active media tasks, including saving. Live recordings first, then retries, then downloads. Collections run separately. Queued settings are snapshots. Restarted tasks are marked interrupted.")
        button("Simultaneous downloads / recordings: ${limit}") { TransferSettings.configure(this) }
        button(if(paused) "Resume queue" else "Pause after current") { queueAction { TransferQueue.pause(this,!TransferQueue.paused(this)) } }
        jobs.firstOrNull { it.optString("state") in TransferRules.activeStates }?.let { button("Stop active task / save recording") { queueAction { TransferQueue.stop(this) } } }
        val active=jobs.filter { it.optString("state") in setOf("running","stopping","reserved","finalizing","queued","held") }
        val highlighted=jobs.find { it.optString("id")==intent.getStringExtra("highlight_job") }
        (listOfNotNull(highlighted)+active+jobs.filterNot { it in active }.asReversed().take(100)).distinctBy { it.optString("id") }.forEach { j ->
            val id=j.getString("id");val state=j.optString("state")
            val label=TextView(this).apply { text=QueuePresentation.label(j);textSize=19f;setPadding(0,12,0,8) }
            body.addView(label)
            rowVisuals.style(label,id,state)
            if(state in TransferRules.activeStates || state=="held") activeLabels[id]=label
            if(j.optString("fragment_sample_status").isNotBlank()) text(j.optString("fragment_sample_status"))
            if(j.optString("retained_recovery_status").isNotBlank()) text(j.optString("retained_recovery_status"))
            if(j.optString("fragment_diagnostic_status").isNotBlank()) text(j.optString("fragment_diagnostic_status"))
            if(state in TransferRules.activeStates) {
                body.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                    activeBars[id]=this;max=100;progress=j.optInt("progress").coerceIn(0,100);isIndeterminate=progress==0
                })
            }
            if(state in TransferRules.activeStates) button("Stop this task / save recording") { queueAction { TransferQueue.stop(this,id) } }
            if(state in setOf("queued","held")) {
                button("Move up") { queueAction { TransferQueue.change(this,id,"up") } };button("Move down") { queueAction { TransferQueue.change(this,id,"down") } }
                if(j.optBoolean("live")) button("Record now…") { AlertDialog.Builder(this).setMessage("Stop the active task and prioritize this live stream? An active recording is saved first.").setPositiveButton("Prioritize") { _,_->queueAction { TransferQueue.recordNow(this,id) } }.setNegativeButton("Cancel",null).show() }
                button("Remove from queue") { queueAction { TransferQueue.change(this,id,"dismiss") } }
            }
            if(state in setOf("attention","interrupted","canceled")) {
                button("Retry with fresh detection") { queueAction { TransferQueue.change(this,id,"retry") } }
                button("Open Browser Assist") {
                    val request=Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME)
                    val monitor=j.optString("monitor")
                    val browser=if(monitor.isNotBlank()) MonitoringCoordinator.browser(this,monitor) else Intent(this,BrowserSessionActivity::class.java)
                        .putExtra(BrowserSessionActivity.EXTRA_PAGE_URL,request.getStringExtra(RecordingService.EXTRA_PAGE_URL).orEmpty().ifBlank { request.getStringExtra(DownloadService.EXTRA_REFERER).orEmpty().ifBlank { request.getStringExtra(DownloadService.EXTRA_URL).orEmpty() } })
                        .putExtra(BrowserSessionActivity.EXTRA_MODE,if(j.optBoolean("live")) BrowserSessionActivity.MODE_RECORD else BrowserSessionActivity.MODE_DOWNLOAD)
                    if(browser!=null) { browserJob=id;this.browser.launch(browser) }
                }
            }
            if(state=="saved" && !j.optBoolean("live")) button("Download again") {
                AlertDialog.Builder(this).setTitle("Download again?").setMessage("Create another download of this saved item? The existing file and history entry are kept.")
                    .setPositiveButton("Download again") { _,_->queueAction { TransferQueue.downloadAgain(this,id) };render() }.setNegativeButton("Cancel",null).show()
            }
            if(state !in TransferRules.activeStates && retained[id]==true) {
                text("Recoverable files are retained for this task.")
                if(!j.optBoolean("live") && state !in setOf("queued","held") && !FragmentDiagnosticJob.pending(j)) button("Export fragment diagnostics") {
                    queueAction { TransferQueue.change(this,id,"export_fragment_diagnostics") }
                }
                if(!j.optBoolean("live") && state !in setOf("queued","held") && !FragmentDiagnosticJob.pending(j)) button("Export sample fragments") {
                    worker.execute {
                    val folder=TransferQueue.workDirectory(this,id,"download")
                    val plan=FragmentSampleCore.plan(folder)
                    runOnUiThread { if(!visible || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this).setTitle("Export sample fragments?")
                        .setMessage("Copies up to ${plan.size} media samples (${android.text.format.Formatter.formatFileSize(this,plan.sumOf { it.length })} before ZIP compression). Includes actual media bytes, but no cookies, URLs or original filenames from app metadata. Large fragments are represented by labeled byte excerpts. Original files stay intact.")
                        .setPositiveButton("Export") { _,_->queueAction { TransferQueue.change(this,id,"export_fragment_samples") };render() }.setNegativeButton("Cancel",null).show()
                    } }
                }
                if(j.optBoolean("live")) button("Save recovered recording") { queueAction { TransferQueue.change(this,id,"save_recovered") } }
                if(!FragmentDiagnosticJob.pending(j) && !j.optBoolean("live") && runCatching { !Intent.parseUri(j.getString("payload"),Intent.URI_INTENT_SCHEME).getBooleanExtra(DownloadService.EXTRA_BROWSER_CAPTURED,false) }.getOrDefault(false) && recoverable[id]==true) {
                    text("Retry reuses compatible completed pieces. Saving a partial copy keeps the temporary media for another retry; missing pieces may cause playback gaps.")
                    if(state !in setOf("queued","held")) button("Recover retained video locally") { queueAction { TransferQueue.change(this,id,"repair_wrapped_video") } }
                    button("Save partial video") { queueAction { TransferQueue.change(this,id,"save_partial") } }
                }
                if(!FragmentDiagnosticJob.pending(j)) button("Remove recovery files") {
                    AlertDialog.Builder(this).setMessage("Permanently remove this task's retained temporary files? Saved files in Downloads are kept.")
                        .setPositiveButton("Remove") { _,_->queueAction { TransferQueue.discardRetained(this,id) };render() }.setNegativeButton("Cancel",null).show()
                }
            }
            button("Details / Copy diagnostics") {
                worker.execute {
                val fresh=TransferQueue.snapshot(this).find { it.optString("id")==id } ?: j
                val diagnostics=fresh.optString("events")+"\n\n"+fresh.optString("log",fresh.optString("detail"))+fresh.optString("retained_recovery_log").takeIf { it.isNotBlank() }?.let { "\n\nLocal recovery:\n$it" }.orEmpty()
                runOnUiThread { if(!visible || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this).setTitle("Task diagnostics").setMessage(diagnostics)
                    .setPositiveButton("Copy") { _,_->getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Task diagnostics",diagnostics)) }.setNegativeButton("Close",null).show()
                } }
            }
            val files=j.optJSONArray("files")
            if(files!=null && files.length()>0) button("Open saved files (${files.length()})") {
                val uris=(0 until files.length()).map { android.net.Uri.parse(files.getString(it)) }
                val labels=uris.map { uri -> runCatching { contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else uri.lastPathSegment } }.getOrNull() ?: "Saved file" }
                AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _,i ->runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uris[i],contentResolver.getType(uris[i]) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { Toast.makeText(this,"No viewer is available for this file",Toast.LENGTH_LONG).show() } }.show()
            }
        }
        text("Collection history",24f)
        CollectionJournal.render(this,body,collections)
    }
}
