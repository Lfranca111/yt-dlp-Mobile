package com.linfranca.ytdlpmobile

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton

class AppSettingsActivity: DisplayActivity() {
    private lateinit var status: TextView
    private lateinit var backups: TextView
    private lateinit var backupToggle: CheckBox
    private val handler=Handler(Looper.getMainLooper())
    private var lastStatus=""
    private var lastBackup=""
    private var taskRunning=false
    private var exportText=""
    private val chooseFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) safely {
            contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ApkBackups.choose(this,uri);background("Saving current APK…") { ApkBackups.capture(applicationContext);"Current APK saved and verified" }
        }
        refresh()
    }
    private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) background("Exporting settings…") { contentResolver.openOutputStream(uri,"w").use { it ?: error("Cannot write export");it.write(exportText.ifBlank { AppRecoverySettings.export(applicationContext) }.toByteArray()) };exportText="";"Settings exported. Keep this file private; it includes channel URLs." }
    }
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) background("Reading recovery settings…") {
            val raw=contentResolver.openInputStream(uri).use { input -> require(input!=null);val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(true) { val n=input.read(b);if(n<0) break;require(out.size()+n<=1_048_576) { "Settings file exceeds 1 MB" };out.write(b,0,n) };out.toString("UTF-8") }
            val plan=AppRecoverySettings.plan(raw)
            runOnUiThread { if(!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("Import recovery settings?").setMessage("Add ${plan.rules.size} disabled monitoring rule copies and ${plan.sources.size} source templates, and restore theme/orientation. Existing rules remain. Review and retest sources, sign in and select folders before enabling rules. No jobs start automatically.")
                .setNegativeButton("Cancel",null).setPositiveButton("Import") { _,_ -> background("Importing…") { AppRecoverySettings.apply(applicationContext,plan);"Settings imported; monitoring copies are disabled" } }.show() }
            "Recovery settings validated; confirm import"
        }
    }
    private fun safely(action: ()->Unit) { try { action() } catch(e: Exception) { message(e.message ?: e.javaClass.simpleName) } }
    private fun message(s: String) { if(!isFinishing && !isDestroyed) AlertDialog.Builder(this).setMessage(s).setPositiveButton("OK",null).show() }
    private fun background(label: String,work: ()->String) {
        if(taskRunning) { message("Wait for the current settings operation to finish");return }
        taskRunning=true;AppUpdates.status(this,label)
        AppUpdates.executor.execute { val result=runCatching(work).getOrElse { "Operation failed: ${it.message ?: it.javaClass.simpleName}" };AppUpdates.status(applicationContext,result);runOnUiThread { taskRunning=false;if(!isDestroyed) refresh() } }
    }
    private fun refresh() {
        if(!::status.isInitialized) return
        val s=AppUpdates.prefs(this).getString("status","No app update check yet").orEmpty()
        if(lastStatus!=s) { status.text=s;lastStatus=s }
        val b=ApkBackups.description(this);if(lastBackup!=b) { backups.text=b;lastBackup=b }
        backupToggle.isChecked=ApkBackups.enabled(this)
    }
    private val poll=object: Runnable { override fun run() { refresh();handler.postDelayed(this,750) } }
    override fun onResume() { super.onResume();handler.removeCallbacks(poll);handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll);super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp=resources.displayMetrics.density
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding((20*dp).toInt(),(16*dp).toInt(),(20*dp).toInt(),(24*dp).toInt()) }
        fun text(s: String,size: Float=16f): TextView=TextView(this).apply { text=s;textSize=size;setPadding(0,(12*dp).toInt(),0,(8*dp).toInt());body.addView(this) }
        fun button(s: String,action: ()->Unit) { body.addView(MaterialButton(this).apply { text=s;setOnClickListener { safely(action) } }) }
        button("Back") { finish() };text("Settings",26f)
        button("Appearance & display") { startActivity(Intent(this,DisplaySettingsActivity::class.java)) }
        text("App updates",22f);text("Installed app: ${BuildConfig.VERSION_NAME}. Checks published stable GitHub releases for an APK. This is separate from Update yt-dlp on Main. Downloads never install automatically.")
        body.addView(CheckBox(this).apply { text="Check and prompt for app updates at startup";isChecked=AppUpdates.prefs(this@AppSettingsActivity).getBoolean("startup",true);setOnCheckedChangeListener { _,on -> AppUpdates.prefs(this@AppSettingsActivity).edit().putBoolean("startup",on).apply() } })
        status=text("");status.setTextIsSelectable(true)
        button("Check for app update") { AppUpdates.status(this,"Checking GitHub…");AppUpdates.check(this) }
        button("Release notes") { message(AppUpdates.release(this)?.optString("notes")?.ifBlank { "No release notes supplied" } ?: "Check for an update first") }
        button("Open GitHub releases") { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(AppUpdatePolicy.PAGE))) }
        button("Download app update") { AppUpdates.download(this) }
        button("Cancel app APK download") { WorkManager.getInstance(this).cancelUniqueWork("app-apk-update");AppUpdates.status(this,"App APK cancellation requested") }
        button("Install downloaded update") {
            require(!AppUpdates.busy(this)) { "Finish active media tasks before installing" }
            val app=applicationContext
            background("Preparing app installation…") {
                if(ApkBackups.enabled(app)) ApkBackups.capture(app)
                AppUpdates.validate(app,AppUpdates.ready(app))
                runOnUiThread { safely { AppUpdates.install(this,AppUpdates.ready(app)) } };"Android installation requested. If permission was needed, allow it and tap Install again."
            }
        }
        text("Previous APK & recovery",22f)
        text("Keep the installed universal APK and one older APK in a folder you choose. Files remain after uninstall. This may use twice the current APK size (${java.io.File(applicationInfo.sourceDir).length()/1048576} MiB now), plus a downloaded update. Signed APKs do not contain your settings, cookies or downloaded media.")
        backupToggle=CheckBox(this).apply { text="Keep previous app APK locally";isChecked=ApkBackups.enabled(this@AppSettingsActivity);setOnClickListener {
            if(!isChecked) { ApkBackups.setEnabled(this@AppSettingsActivity,false);refresh() }
            else { isChecked=false;AlertDialog.Builder(this@AppSettingsActivity).setTitle("Use storage for APK backups?").setMessage("Up to two installed APK snapshots will be kept in a public folder. Choose a folder outside Android/data. Turning this off keeps existing files; Delete saved APK backups removes tracked APKs.")
                .setNegativeButton("Cancel",null).setPositiveButton("Choose folder") { _,_ -> if(ApkBackups.folder(this@AppSettingsActivity)!=null) { ApkBackups.setEnabled(this@AppSettingsActivity,true);background("Saving APK…") { ApkBackups.capture(applicationContext);"APK backup verified" } } else chooseFolder.launch(null) }.show() }
        } };body.addView(backupToggle)
        backups=text("");backups.setTextIsSelectable(true)
        button("Choose / reconnect backup folder") { chooseFolder.launch(ApkBackups.folder(this)) }
        button("Save / verify current APK backup") { require(ApkBackups.enabled(this)) { "Enable APK backup first" };background("Verifying APK backup…") { ApkBackups.capture(applicationContext);"Installed APK backup verified" } }
        button("Install previous APK") {
            require(!AppUpdates.busy(this)) { "Finish active media tasks before recovery" }
            background("Preparing older APK…") { val file=ApkBackups.recovery(applicationContext);runOnUiThread { safely { AppUpdates.install(this,file,true) } };"Older APK offered to Android. If Android blocks a downgrade, use the separate fallback below." }
        }
        button("Uninstall / reinstall fallback…") {
            require(!AppUpdates.busy(this)) { "Finish active media tasks before recovery" }
            background("Verifying recovery APK before fallback…") {
                ApkBackups.recovery(applicationContext)
                val older=ApkBackups.previous(applicationContext)!!
                runOnUiThread { if(!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("Uninstall only if downgrade was blocked").setMessage("Export recovery settings first if needed. Uninstalling removes app-private settings, cookies, monitoring rules, queue records and private media/fragments. Public downloaded files and the chosen backup folder remain.\n\nAfter uninstall, open Android Files and install ${older.getString("name")} from ${ApkBackups.folder(this)}. This app cannot reinstall itself after removal. Older apps may not import newer recovery settings.\n\nContinue to Android's uninstall confirmation?")
                    .setNegativeButton("Cancel",null).setPositiveButton("Open Android uninstall") { _,_ -> safely { require(!AppUpdates.busy(this));startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:$packageName"))) } }.show() }
                "Older APK verified in your backup folder. Review the data-loss warning before uninstalling."
            }
        }
        button("Delete saved APK backups…") { AlertDialog.Builder(this).setTitle("Delete tracked APK backups?").setMessage("This removes the current and previous saved APK copies and disables backups. Other folder files remain.").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> background("Deleting tracked APKs…") { ApkBackups.deleteAll(applicationContext);"Tracked APK backups deleted" } }.show() }
        text("Recovery settings",22f)
        text("Exports theme, orientation, monitoring rules and custom source templates. Channel URLs are included: keep the file private. Cookies, sign-ins, queue/history, media, comic update rules, download presets and folder permissions are not included. Import adds disabled rule copies and requires local source tests. Only versions supporting this format can import it; older apps may need manual setup.")
        button("Export recovery settings") { exportText=AppRecoverySettings.export(this);exportFile.launch("yt-dlp-Mobile-recovery-${BuildConfig.VERSION_NAME}.json") }
        button("Import recovery settings") { importFile.launch(arrayOf("application/json","text/plain")) }
        setContentView(ScrollView(this).apply { addView(body) });refresh()
    }
}
