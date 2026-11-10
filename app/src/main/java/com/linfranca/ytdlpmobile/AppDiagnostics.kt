package com.linfranca.ytdlpmobile

import android.app.*
import android.content.*
import android.os.*
import android.view.ViewGroup
import androidx.core.view.*
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

internal object DeviceDiagnostics {
    fun snapshot(c: Context): String = runCatching {
        val battery=c.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100) ?: 100
        val power=c.getSystemService(PowerManager::class.java)
        val manager=c.getSystemService(ActivityManager::class.java)
        val network=c.getSystemService(android.net.ConnectivityManager::class.java)
        val caps=network.getNetworkCapabilities(network.activeNetwork)
        "battery=${if(level>=0 && scale>0) level*100/scale else -1}%; charging=${(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: 0)!=0}; powerSave=${power.isPowerSaveMode}; idle=${power.isDeviceIdleMode}; interactive=${power.isInteractive}; backgroundRestricted=${manager.isBackgroundRestricted}; batteryExempt=${power.isIgnoringBatteryOptimizations(c.packageName)}; networkValidated=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)}; networkMetered=${network.isActiveNetworkMetered}"
    }.getOrElse { "device snapshot unavailable: ${it.javaClass.simpleName}" }
}

class MediaApplication: Application(), Application.ActivityLifecycleCallbacks {
    private val io=Executors.newSingleThreadExecutor()
    @Volatile private var screen="No activity"
    override fun onCreate() {
        super.onCreate()
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            // Best effort, bounded, synchronous: the process is about to terminate. Chain Android's handler.
            try {
                val stack=generateSequence(error) { it.cause }.take(5).joinToString("\nCaused by: ") { e ->
                    e.javaClass.name+": "+e.message.orEmpty().take(2000)+"\n"+e.stackTrace.take(65).joinToString("\n") { "frame $it" }
                }
                val dir=File(filesDir,"crashes").apply { mkdirs() }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
                File(dir,"crash-${System.currentTimeMillis()}.txt").writeText(
                    "${Instant.now()} App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nScreen=$screen; thread=${thread.name}; Android=${Build.VERSION.SDK_INT}\n"+
                    DeviceDiagnostics.snapshot(this)+"\n"+MonitoringStore.safe(stack).take(40_000))
            } catch(_: Throwable) { }
            if(previous!=null) previous.uncaughtException(thread,error) else { android.os.Process.killProcess(android.os.Process.myPid());kotlin.system.exitProcess(10) }
        }
        registerActivityLifecycleCallbacks(this)
        DisplaySettings.initialize(this)
        VisualPolish.install(this)
        MediaTaskRuntime.clearStaleProgress(this)
        MonitoringNetworkRecovery.initialize(this)
        androidx.core.content.ContextCompat.registerReceiver(this,object: BroadcastReceiver() {
            override fun onReceive(c: Context,i: Intent) {
                io.execute { MonitoringPowerDiagnostics.record(c,"power transition; action=${i.action?.substringAfterLast('.')}");runCatching { MonitoringStore.log(c,"power transition observed; action=${i.action?.substringAfterLast('.')}; ${DeviceDiagnostics.snapshot(c)}") } }
            }
        },IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW);addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED);addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        },androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        io.execute {
            runCatching {
                if(Build.VERSION.SDK_INT>=30) {
                    val prefs=getSharedPreferences("process_exits",MODE_PRIVATE)
                    val since=prefs.getLong("last",System.currentTimeMillis()-7*86400000L)
                    val exits=getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName,0,8)
                    exits.filter { it.timestamp>since }.forEach {
                        MonitoringStore.log(this,"previous process exit; at=${it.timestamp}; reason=${it.reason}; status=${it.status}; importance=${it.importance}; pssKB=${it.pss}; description=${it.description.orEmpty()}")
                    }
                    prefs.edit().putLong("last",System.currentTimeMillis()).apply()
                }
                MonitoringPowerDiagnostics.record(this,"application started")
                MonitoringStore.log(this,"application started; version=${BuildConfig.VERSION_NAME}; ${DeviceDiagnostics.snapshot(this)}")
                // Restore saved schedules for every process start, including a background wakeup.
                MonitoringScheduler.initialize(this)
            }
        }
    }
    override fun onActivityCreated(a: Activity,b: Bundle?) {
        ScreenInsets.apply(a)
        AppAppearance.install(a)
        if(a is MainActivity) {
            val crashes=File(filesDir,"crashes").listFiles()?.filter { it.extension=="txt" }.orEmpty()
            val prefs=getSharedPreferences("crash_notice",MODE_PRIVATE)
            val latest=crashes.maxOfOrNull { it.lastModified() } ?: 0L
            if(latest>prefs.getLong("seen",0)) {
                prefs.edit().putLong("seen",latest).apply()
                a.window.decorView.post { if(!a.isFinishing) androidx.appcompat.app.AlertDialog.Builder(a)
                    .setTitle("The previous session crashed")
                    .setMessage("A local diagnostic report is available. You can review and save it; nothing is sent automatically.")
                    .setPositiveButton("View reports") { _,_->a.startActivity(Intent(a,CrashReportsActivity::class.java)) }
                    .setNegativeButton("Later",null).show() }
            }
        }
    }
    override fun onActivityResumed(a: Activity) { MonitoringExecutionBudget.visible(this);if(a is MainActivity) MonitoringReadiness.resumed(a);screen=a.javaClass.simpleName;io.execute { runCatching { TransferQueue.retryBlockedMonitoring(this,setOf("background","quota")) } } }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity,b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

/** Opaque inset gutters outside the clipped content viewport, including Android 15 edge-to-edge. */
internal object ScreenInsets {
    fun apply(a: Activity) {
        val content=a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val value=android.util.TypedValue()
        a.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface,value,true)
        val color=if(value.resourceId!=0) androidx.core.content.ContextCompat.getColor(a,value.resourceId) else value.data
        content.setBackgroundColor(color);content.clipToPadding=true;content.clipChildren=true
        val light=androidx.core.graphics.ColorUtils.calculateLuminance(color)>0.5
        WindowInsetsControllerCompat(a.window,content).apply {
            isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light
        }
        WindowCompat.setDecorFitsSystemWindows(a.window,false)
        @Suppress("DEPRECATION")
        a.window.apply { statusBarColor=color;navigationBarColor=color }
        ViewCompat.setOnApplyWindowInsetsListener(content) { v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left,bars.top,bars.right+LandscapePanels.reserved(a),maxOf(bars.bottom,ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }
}

class CrashReportsActivity: DisplayActivity() {
    private val export=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri!=null) Thread {
            val result=runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(report()) } ?: error("Unable to open destination") }
            runOnUiThread { android.widget.Toast.makeText(this,if(result.isSuccess) "Crash report saved" else "Save failed",android.widget.Toast.LENGTH_LONG).show() }
        }.start()
    }
    private fun report()=File(filesDir,"crashes").listFiles()?.filter { it.extension=="txt" }?.sortedBy { it.name }?.joinToString("\n\n") { it.readText() }.orEmpty().ifBlank { "No app crash reports saved. Android process-exit observations appear in the monitoring log." }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val body=android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        body.addView(android.widget.Button(this).apply { text="Save crash reports";setOnClickListener { export.launch("yt-dlp-Mobile-crash-reports.txt") } })
        body.addView(android.widget.TextView(this).apply { text=report();setTextIsSelectable(true) })
        setContentView(android.widget.ScrollView(this).apply { addView(body) })
    }
}

/** Reconcile existing persisted work after reboot/update. Does not start downloads from a boot receiver. */
class MonitoringRestoreReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        val pending=goAsync()
        Thread {
            try {
                MonitoringPowerDiagnostics.record(c,"schedule restore")
                TransferQueue.initialize(c)
                MonitoringStore.log(c,"schedule restore; action=${i.action?.substringAfterLast('.')}; ${DeviceDiagnostics.snapshot(c)}")
                androidx.work.WorkManager.getInstance(c).enqueueUniquePeriodicWork("monitoring-watchdog",androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    androidx.work.PeriodicWorkRequestBuilder<MonitoringWatchdog>(15,java.util.concurrent.TimeUnit.MINUTES).build())
                MonitoringStore.rules(c).filter { it.enabled }.forEach { MonitoringScheduler.ensure(c,it) }
                ComicStore.initialize(c)
                MonitoringWakeup.refresh(c)
            } finally { pending.finish() }
        }.start()
    }
}
