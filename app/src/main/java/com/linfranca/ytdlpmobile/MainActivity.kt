package com.linfranca.ytdlpmobile

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.linfranca.ytdlpmobile.DownloadService.Companion.ACTION_DOWNLOAD
import com.linfranca.ytdlpmobile.DownloadService.Companion.ACTION_STATE
import com.linfranca.ytdlpmobile.DownloadService.Companion.ACTION_STOP
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_ARGS
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_AUTO_SUBTITLES
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_LOG
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_OPERATION
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_PLAYLIST
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_PRESET
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_PROGRESS
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_RUNNING
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_STATUS
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_SUBTITLES
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_SUBTITLE_LANGUAGES
import com.linfranca.ytdlpmobile.DownloadService.Companion.EXTRA_URL
import com.linfranca.ytdlpmobile.databinding.ActivityMainBinding
import com.linfranca.ytdlpmobile.databinding.PageCollectionsBinding
import com.linfranca.ytdlpmobile.databinding.RowManualTaskBinding
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : DisplayActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var collectionBinding: PageCollectionsBinding
    private lateinit var pager: ViewPager2
    private val pageButtons=mutableListOf<com.google.android.material.button.MaterialButton>()
    private lateinit var monitoringPage: MonitoringPage
    private lateinit var backToMain: OnBackPressedCallback
    private var updatingEngine = false
    private val manualRows = linkedMapOf<String, RowManualTaskBinding>()
    private var singleManualTaskId = ""
    private var manualRowCount = 0
    private val engineUiHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var lastEnginePresentation=""
    private val engineUiRefresh=object: Runnable {
        override fun run() { reconcileEngineStatus();engineUiHandler.postDelayed(this,500) }
    }
    private fun reconcileEngineStatus() {
        val snapshot=EngineCheckState.snapshot()
        updatingEngine=EngineUpdateCheck.busy
        val cached=getSharedPreferences("engine_display",MODE_PRIVATE).getString("version","").orEmpty()
        val version=snapshot.version.ifBlank { cached }
        binding.versionText.text="App ${BuildConfig.VERSION_NAME} • "+if(snapshot.checking) "yt-dlp checking…" else
            "yt-dlp "+version.ifBlank { "version not checked" }+if(snapshot.phase==EngineCheckState.Phase.TIMED_OUT) " • check timed out" else ""
        val presentation="${snapshot.token}:${snapshot.phase}:$updatingEngine"
        if(presentation!=lastEnginePresentation) {
            lastEnginePresentation=presentation
            val active=DownloadState.running || MonitoringTaskState.running || TransferQueue.active(this)!=null
            val status=if(snapshot.token>0 && EngineCheckState.shouldPresentStatus(snapshot.checking,binding.statusText.text.toString(),active)) when(snapshot.phase) {
                EngineCheckState.Phase.CHECKING -> "Checking yt-dlp engine…"
                EngineCheckState.Phase.TIMED_OUT -> snapshot.detail+if(updatingEngine) " • updater cleanup still running" else ""
                else -> snapshot.detail.ifBlank { binding.statusText.text.toString() }
            } else binding.statusText.text.toString()
            renderState(DownloadState.running,DownloadState.progress,status,DownloadState.log,DownloadState.operation)
        }
    }
    private val importedCookieFile by lazy { File(filesDir, "cookies/imported-cookies.txt") }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val cookiePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val result = runCatching {
            importedCookieFile.parentFile?.mkdirs()
            contentResolver.openInputStream(uri)?.use { input ->
                importedCookieFile.outputStream().use { input.copyTo(it) }
            } ?: error("Android could not open that file.")
            require(importedCookieFile.length() in 1..5_000_000) { "The cookie file is empty or unexpectedly large." }
        }
        result.onSuccess { refreshCookieStatus(); Toast.makeText(this, "cookies.txt imported", Toast.LENGTH_SHORT).show() }
            .onFailure { importedCookieFile.delete(); binding.statusText.text = "Cookie import failed: ${it.message}" }
    }

    private val browserAssist = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        if (BrowserDownloadBatch.consume(this, data) { batch ->
            BrowserDownloadBatch.enqueue(this, batch) { source ->
                var request: Intent? = null
                startDownload(
                    overrideUrl = source.url,
                    referer = batch.page,
                    userAgent = batch.agent,
                    browserCaptured = true,
                    downloadInfoJson = source.info.toString(),
                    downloadDiagnostics = source.diagnostics,
                    prepareBrowserBatch = { request = it }
                )
                request
            }
        }) return@registerForActivityResult
        val mediaUrl = data.getStringExtra(BrowserSessionActivity.EXTRA_MEDIA_URL).orEmpty()
        if (mediaUrl.isBlank()) return@registerForActivityResult
        if (data.getStringExtra(BrowserSessionActivity.EXTRA_MODE) != BrowserSessionActivity.MODE_RECORD) {
            startDownload(
                overrideUrl = mediaUrl,
                referer = data.getStringExtra(BrowserSessionActivity.EXTRA_REFERER).orEmpty(),
                cookies = data.getStringExtra(BrowserSessionActivity.EXTRA_COOKIES).orEmpty(),
                userAgent = data.getStringExtra(BrowserSessionActivity.EXTRA_USER_AGENT).orEmpty(),
                capturedHeadersJson = data.getStringExtra(BrowserSessionActivity.EXTRA_HEADERS_JSON).orEmpty(),
                browserCaptured = true,
                downloadInfoJson = data.getStringExtra(BrowserSessionActivity.EXTRA_DOWNLOAD_INFO_JSON).orEmpty(),
                downloadDiagnostics = data.getStringExtra(BrowserSessionActivity.EXTRA_DOWNLOAD_DIAGNOSTICS).orEmpty()
            )
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STATE) return
            renderState(DownloadState.running,DownloadState.progress,DownloadState.status,
                DownloadState.log,DownloadState.operation)
            val fallback = intent.getStringExtra("browser_fallback_page").orEmpty()
            if (!DownloadState.running && hasWindowFocus() && validUrl(fallback)) {
                if(intent.getBooleanExtra("browser_fallback_offer",false)) {
                    val server=android.net.Uri.parse(fallback).getQueryParameter("ydlp_server")?.toIntOrNull()
                    val selection=if(server!=null && server>0) "Select Server $server in the player if needed. " else ""
                    val english=android.net.Uri.parse(fallback).getQueryParameter("ydlp_audio")=="en"
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Media server is rate-limited")
                        .setMessage("Automatic retries stopped before the first fragment downloaded. "+
                            selection+(if(english) "Choose English audio if offered. " else "")+
                            "Play the video in Browser Assist, then tap Detect and download.")
                        .setNegativeButton("Not now",null)
                        .setPositiveButton("Open Browser Assist") { _, _ ->
                            launchBrowserAssist(BrowserSessionActivity.MODE_DOWNLOAD,fallback)
                        }.show()
                } else {
                    val guidance = intent.getStringExtra(DownloadService.EXTRA_STATUS)?.takeIf { it.startsWith("Rumble rejected the player metadata request (403).") }
                        ?: "Opening Browser Assist. Sign in or play the video if needed."
                    Toast.makeText(this@MainActivity, guidance, Toast.LENGTH_LONG).show()
                    launchBrowserAssist(BrowserSessionActivity.MODE_DOWNLOAD, fallback)
                }
            }
        }
    }

    private val collectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CollectionDownloadService.ACTION_STATE) renderCollectionState()
        }
    }

    private lateinit var extraOptions: DownloadOptionsPanel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        collectionBinding = PageCollectionsBinding.inflate(layoutInflater)
        monitoringPage = MonitoringPage(this)
        setContentView(R.layout.activity_home_pager)
        VisualPolish.markMonitoring(monitoringPage.root)
        pager = findViewById(R.id.homePager)
        // All pages retain their view and scroll position while navigating.
        // Main is on the right, so a right swipe reveals Collections on the left.
        installPageButtons()
        pager.adapter = HomePagesAdapter()
        pager.offscreenPageLimit = 2
        pager.isSaveEnabled = false // A fresh launch always starts at Main.
        pager.setCurrentItem(MAIN_PAGE, false)
        backToMain = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                pager.setCurrentItem(MAIN_PAGE, true)
            }
        }
        onBackPressedDispatcher.addCallback(this, backToMain)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                showPageIndicators(position)
                backToMain.isEnabled = position != MAIN_PAGE
            }
        })
        showPageIndicators(MAIN_PAGE)
        applyInsets()
        TransferQueue.initialize(this)
        setupUi()
        extraOptions=DownloadOptionsPanel(this, {
            org.json.JSONObject().put("commands",binding.customArgsInput.text.toString()).put("format",binding.formatInput.text.toString())
                .put("playlist",binding.playlistSwitch.isChecked).put("speed",binding.speedBoostSwitch.isChecked)
                .put("subtitles",binding.subtitleSwitch.isChecked).put("auto_subtitles",binding.autoSubtitleSwitch.isChecked)
                .put("languages",binding.subLanguageInput.text.toString())
        }, { p ->
            binding.customArgsInput.setText(p.optString("commands"))
            binding.formatInput.setText(p.optString("format",DownloadService.PRESET_BEST),false)
            binding.playlistSwitch.isChecked=p.optBoolean("playlist");binding.speedBoostSwitch.isChecked=p.optBoolean("speed")
            binding.subtitleSwitch.isChecked=p.optBoolean("subtitles");binding.autoSubtitleSwitch.isChecked=p.optBoolean("auto_subtitles")
            binding.subLanguageInput.setText(p.optString("languages","en.*"))
        })
        val parent=binding.downloadButton.parent.parent as android.widget.LinearLayout
        parent.addView(extraOptions.root,parent.indexOfChild(binding.downloadButton.parent as View))
        parent.addView(android.widget.Button(this).apply { text="Queue & history / Logs";setOnClickListener { startActivity(Intent(this@MainActivity,TransferQueueActivity::class.java)) } },parent.indexOfChild(extraOptions.root)+1)
        TransferQueue.dispatch(this)

        setupCollections()
        val appearanceButton=android.widget.Button(this).apply {
            text="Appearance & display"
            setOnClickListener { startActivity(Intent(this@MainActivity,DisplaySettingsActivity::class.java)) }
        }
        (binding.instructionsButton.parent as android.widget.LinearLayout).let { parent ->
            parent.addView(appearanceButton,parent.indexOfChild(binding.instructionsButton))
            parent.addView(android.widget.Button(this).apply { text="Settings / App updates & recovery";setOnClickListener { startActivity(Intent(this@MainActivity,AppSettingsActivity::class.java)) } },parent.indexOfChild(binding.instructionsButton))
        }
        if (intent.getBooleanExtra("open_monitoring", false)) pager.setCurrentItem(MONITORING_PAGE, false)
        if (savedInstanceState != null) restoreFormState(savedInstanceState)
        acceptSharedLink(intent)
        requestNotificationPermissionIfNeeded()
        refreshCookieStatus()
        AppUpdates.startup(this)
        EngineUpdateCheck.recover(applicationContext)
        reconcileEngineStatus()
        if (!autoUpdateCheckedThisProcess && !DownloadState.running && TransferQueue.active(this)==null) {
            updateEngine(automatic = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedLink(intent)
        if (intent.getBooleanExtra("open_monitoring", false)) {
            pager.setCurrentItem(MONITORING_PAGE, false)
            return
        }
        if (intent.action == Intent.ACTION_SEND ||
            (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER))) {
            pager.setCurrentItem(MAIN_PAGE, false)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, collectionReceiver,
            IntentFilter(CollectionDownloadService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        renderState(DownloadState.running, DownloadState.progress, DownloadState.status, DownloadState.log, DownloadState.operation)
        renderCollectionState()
        monitoringPage.start()
        lastEnginePresentation="";engineUiHandler.removeCallbacks(engineUiRefresh);engineUiHandler.post(engineUiRefresh)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (MonitoringTaskState.running && MonitoringTaskState.operation == MonitoringTaskState.OPERATION_RECORD) {
            startService(Intent(this, MonitoringRecordingService::class.java).setAction(MonitoringRecordingService.ACTION_USER_INTERACTION))
        }
        if (DownloadState.running && DownloadState.operation == DownloadState.OPERATION_RECORD) {
            startService(Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_USER_INTERACTION))
        }
    }

    override fun onStop() {
        engineUiHandler.removeCallbacks(engineUiRefresh)
        monitoringPage.stop()
        unregisterReceiver(stateReceiver)
        unregisterReceiver(collectionReceiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("extra_options",extraOptions.snapshot().toString())
        super.onSaveInstanceState(outState)
        with(binding) {
            outState.putString("main_url", urlInput.text?.toString())
            outState.putString("main_format", formatInput.text?.toString())
            outState.putString("main_arguments", customArgsInput.text?.toString())
            outState.putString("main_languages", subLanguageInput.text?.toString())
            outState.putBoolean("main_playlist", playlistSwitch.isChecked)
            outState.putBoolean("main_speed", speedBoostSwitch.isChecked)
            outState.putBoolean("main_subtitles", subtitleSwitch.isChecked)
            outState.putBoolean("main_auto_subtitles", autoSubtitleSwitch.isChecked)
            outState.putInt("main_scroll", root.scrollY)
        }
        outState.putString("collections_url", collectionBinding.collectionLinkInput.text?.toString())
        outState.putInt("collections_scroll", collectionBinding.root.scrollY)
    }

    private fun restoreFormState(state: Bundle) {
        runCatching { extraOptions.apply(org.json.JSONObject(state.getString("extra_options","{}"))) }
        with(binding) {
            urlInput.setText(state.getString("main_url").orEmpty())
            formatInput.setText(state.getString("main_format", DownloadService.PRESET_BEST), false)
            customArgsInput.setText(state.getString("main_arguments").orEmpty())
            subLanguageInput.setText(state.getString("main_languages").orEmpty())
            playlistSwitch.isChecked = state.getBoolean("main_playlist")
            speedBoostSwitch.isChecked = state.getBoolean("main_speed", true)
            subtitleSwitch.isChecked = state.getBoolean("main_subtitles")
            autoSubtitleSwitch.isChecked = state.getBoolean("main_auto_subtitles")
            root.post { root.scrollTo(0, state.getInt("main_scroll")) }
        }
        collectionBinding.collectionLinkInput.setText(state.getString("collections_url").orEmpty())
        collectionBinding.root.post {
            collectionBinding.root.scrollTo(0, state.getInt("collections_scroll"))
        }
    }

    private fun setupUi() = with(binding) {
        formatInput.setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, DownloadService.PRESETS))
        formatInput.setText(DownloadService.PRESET_BEST, false)
        formatInput.setOnClickListener { formatInput.showDropDown() }
        logText.movementMethod = ScrollingMovementMethod()
        fun subtitleOptionsEnabled() {
            val enabled=subtitleSwitch.isChecked || formatInput.text.toString()==DownloadService.PRESET_SUBTITLES
            autoSubtitleSwitch.isEnabled=enabled;subLanguageLayout.isEnabled=enabled
            if(!enabled) autoSubtitleSwitch.isChecked=false
        }
        subtitleSwitch.setOnCheckedChangeListener { _, _ -> subtitleOptionsEnabled() }
        formatInput.doAfterTextChanged { subtitleOptionsEnabled() }
        subtitleOptionsEnabled()
        downloadButton.setOnClickListener { startDownload() }
        browserAssistButton.setOnClickListener { openBrowserAssist(BrowserSessionActivity.MODE_DOWNLOAD) }
        recordStreamButton.setOnClickListener { openBrowserAssist(BrowserSessionActivity.MODE_RECORD) }
        reopenStalledStreamButton.setOnClickListener {
            val task = MediaTaskRuntime.manualTask(singleManualTaskId)
            if (task?.recording == true && task.canRecover && validUrl(task.page)) {
                startActivity(Intent(this@MainActivity, BrowserSessionActivity::class.java)
                    .putExtra(BrowserSessionActivity.EXTRA_PAGE_URL, task.page)
                    .putExtra(BrowserSessionActivity.EXTRA_MODE, BrowserSessionActivity.MODE_RECORD)
                    .putExtra(BrowserSessionActivity.EXTRA_RECOVERY, true)
                    .putExtra(TransferQueue.JOB, task.id))
            } else {
                Toast.makeText(this@MainActivity, "The recording is no longer stalled under 403.", Toast.LENGTH_SHORT).show()
            }
        }
        stopButton.setOnClickListener { stopManualTask(singleManualTaskId) }
        discardRecordingButton.setOnClickListener { stopManualTask(singleManualTaskId, discard = true) }
        importCookiesButton.setOnClickListener { cookiePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }
        removeCookiesButton.setOnClickListener {
            importedCookieFile.delete(); refreshCookieStatus()
            Toast.makeText(this@MainActivity, "Imported cookies removed", Toast.LENGTH_SHORT).show()
        }
        copyLogButton.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("yt-dlp Mobile diagnostics", DownloadState.log))
            Toast.makeText(this@MainActivity, "Diagnostic log copied", Toast.LENGTH_SHORT).show()
        }
        updateButton.setOnClickListener { updateEngine(automatic = false) }
        instructionsButton.setOnClickListener { startActivity(Intent(this@MainActivity, InstructionsActivity::class.java)) }
    }

    private fun setupCollections() = with(collectionBinding) {
        collectionConvertButton.setOnClickListener {
            startActivity(Intent(this@MainActivity, CollectionConvertActivity::class.java))
        }
        collectionLinkInput.doAfterTextChanged { input ->
            val url = input?.toString()?.trim().orEmpty()
            val match = CollectionLinkRecognizer.recognize(url)
            collectionE6UserLayout.visibility =
                if (match?.site in setOf("E621", "E6AI", "E926")) View.VISIBLE else View.GONE
            collectionE6KeyLayout.visibility = collectionE6UserLayout.visibility
            collectionFurbooruKeyLayout.visibility =
                if (match?.site == "Furbooru") View.VISIBLE else View.GONE
            collectionRule34UserLayout.visibility =
                if (match?.site == "Rule34") View.VISIBLE else View.GONE
            collectionRule34KeyLayout.visibility = collectionRule34UserLayout.visibility
            when {
                url.isEmpty() -> {
                    detectedSiteText.text = "Waiting for a link…"
                    detectedPageTypeText.visibility = View.GONE
                    collectionLinkHint.visibility = View.GONE
                }
                match == null -> {
                    detectedSiteText.text = "No supported collection website recognized"
                    detectedPageTypeText.visibility = View.GONE
                    collectionLinkHint.visibility = View.VISIBLE
                    collectionLinkHint.text = "Enter a complete http:// or https:// page link from a site listed below."
                }
                else -> {
                    detectedSiteText.text = "Recognized website: ${match.site}"
                    detectedPageTypeText.text = "Link type: ${match.pageType}"
                    detectedPageTypeText.visibility = View.VISIBLE
                    collectionLinkHint.text = match.hint
                    collectionLinkHint.visibility = View.VISIBLE
                }
            }
            renderCollectionState()
        }
        collectionDownloadButton.setOnClickListener {
            val link = collectionLinkInput.text?.toString()?.trim().orEmpty()
            try {
                CollectionRoute.from(link)
                if (CollectionState.running) return@setOnClickListener
                val request = Intent(this@MainActivity, CollectionDownloadService::class.java)
                    .setAction(CollectionDownloadService.ACTION_START)
                    .putExtra(CollectionDownloadService.EXTRA_LINK, link)
                    .putExtra(CollectionDownloadService.EXTRA_USER, collectionE6User.text?.toString()?.trim().orEmpty())
                    .putExtra(CollectionDownloadService.EXTRA_KEY, collectionE6Key.text?.toString().orEmpty())
                    .putExtra(CollectionDownloadService.EXTRA_FURBOORU_KEY, collectionFurbooruKey.text?.toString().orEmpty())
                    .putExtra(CollectionDownloadService.EXTRA_RULE34_USER, collectionRule34User.text?.toString()?.trim().orEmpty())
                    .putExtra(CollectionDownloadService.EXTRA_RULE34_KEY, collectionRule34Key.text?.toString().orEmpty())
                ContextCompat.startForegroundService(this@MainActivity, request)
                collectionDownloadButton.isEnabled = false
                collectionStatusText.text = "Starting collection download…"
            } catch (error: IllegalArgumentException) {
                collectionStatusText.text = error.message
            }
        }
        collectionStopButton.setOnClickListener {
            startService(Intent(this@MainActivity, CollectionDownloadService::class.java)
                .setAction(CollectionDownloadService.ACTION_STOP))
            collectionStatusText.text = "Stopping collection download…"
        }
        collectionCopyLogButton.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("Collection download log", CollectionState.log))
            Toast.makeText(this@MainActivity, "Collection log copied", Toast.LENGTH_SHORT).show()
        }
        renderCollectionState()
    }

    private fun renderCollectionState() = with(collectionBinding) {
        val downloadable = runCatching {
            CollectionRoute.from(collectionLinkInput.text?.toString().orEmpty())
        }.isSuccess
        collectionDownloadButton.isEnabled = downloadable && !CollectionState.running
        collectionStopButton.isEnabled = CollectionState.running
        collectionProgressBar.visibility = if (CollectionState.running) View.VISIBLE else View.GONE
        collectionProgressBar.max = CollectionState.pageTotal.coerceAtLeast(1)
        VisualPolish.progress(collectionProgressBar, CollectionState.pageProcessed, CollectionState.running,
            CollectionState.pageTotal == 0, VisualPolish.waiting(CollectionState.status))
        collectionStatusText.text = CollectionState.status
        VisualPolish.status(collectionStatusText, CollectionState.status)
        collectionLogText.text = CollectionState.log
    }

    private fun installPageButtons() {
        listOf(binding.mainTopPageIndicator,collectionBinding.collectionsTopPageIndicator,monitoringPage.topIndicator)
            .forEach { it.visibility=View.GONE }
        val row=findViewById<android.widget.LinearLayout>(R.id.homeNavigation)
        listOf("Collections","Main","Monitoring").forEachIndexed { page,label ->
            val button=com.google.android.material.button.MaterialButton(this).apply {
                text=label;isAllCaps=false;textSize=12f;minWidth=0;minimumWidth=0
                setPadding(4,0,4,0);contentDescription="Open $label page"
                setOnClickListener { pager.setCurrentItem(page,true) }
            }
            row.addView(button)
            pageButtons+=button
        }
        adaptHomeNavigation()
    }

    private fun adaptHomeNavigation() {
        val landscape=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root=findViewById<android.widget.LinearLayout>(R.id.homeShell)
        val row=findViewById<android.widget.LinearLayout>(R.id.homeNavigation)
        root.orientation=if(landscape) android.widget.LinearLayout.HORIZONTAL else android.widget.LinearLayout.VERTICAL
        row.orientation=if(landscape) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
        val dp=resources.displayMetrics.density
        row.layoutParams=android.widget.LinearLayout.LayoutParams(
            if(landscape) (120*dp).toInt() else -1,if(landscape) -1 else -2)
        row.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,R.color.polish_canvas))
        pager.layoutParams=android.widget.LinearLayout.LayoutParams(if(landscape) 0 else -1,if(landscape) -1 else 0,1f)
        pageButtons.forEach { button ->
            button.layoutParams=android.widget.LinearLayout.LayoutParams(if(landscape) -1 else 0,-2,if(landscape) 0f else 1f)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::pager.isInitialized) {
            adaptHomeNavigation()
            showPageIndicators(pager.currentItem)
        }
    }

    private fun showPageIndicators(position: Int) {
        val purple=androidx.core.content.ContextCompat.getColor(this,R.color.polish_violet)
        val labels=listOf("Collections","Main","Monitoring download")
        val dots=listOf("○","○","○").toMutableList().apply { this[position]="●" }.joinToString("   ")
        val label="$dots  ${labels[position]}"
        listOf(binding.mainBottomPageIndicator,collectionBinding.collectionsBottomPageIndicator,monitoringPage.bottomIndicator).forEach {
            val text=android.text.SpannableString(label);val start=position*4
            text.setSpan(android.text.style.ForegroundColorSpan(purple),start,start+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (position == MONITORING_PAGE && it === monitoringPage.bottomIndicator) {
                val labelColor = binding.mainBottomPageIndicator.currentTextColor
                text.setSpan(android.text.style.ForegroundColorSpan(labelColor), dots.length + 2, label.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            it.text=text;it.contentDescription="${labels[position]}, page ${position+1} of three"
        }
        pageButtons.forEachIndexed { index,button ->
            val selected=index%3==position;button.isSelected=selected
            VisualPolish.tab(button, selected)
            button.contentDescription="${labels[index%3]}${if(selected) ", current page" else ", tap to open"}"
        }
    }

    private inner class HomePagesAdapter : RecyclerView.Adapter<HomePageHolder>() {
        override fun getItemCount() = 3

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomePageHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return HomePageHolder(container)
        }

        override fun onBindViewHolder(holder: HomePageHolder, position: Int) {
            val page = when (position) { COLLECTIONS_PAGE -> collectionBinding.root; MAIN_PAGE -> binding.root; else -> monitoringPage.root }
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.removeAllViews()
            holder.container.addView(page, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private class HomePageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private val resolvingDownloadPage=java.util.concurrent.atomic.AtomicBoolean(false)
    private fun startDownload(
        overrideUrl: String? = null, referer: String = "", cookies: String = "", userAgent: String = "",
        capturedHeadersJson: String = "", browserCaptured: Boolean = false, downloadInfoJson: String = "", downloadDiagnostics: String = "", playbackChosen: Boolean = false, pageChecked: Boolean = false, choiceTarget: String? = null,
        prepareBrowserBatch: ((Intent) -> Unit)? = null
    ) {
        val url = overrideUrl ?: binding.urlInput.text?.toString()?.trim().orEmpty()
        if (!validUrl(url)) { binding.urlInput.error = "Enter a complete http:// or https:// link."; return }
        if(!browserCaptured && !pageChecked && downloadInfoJson.isBlank()) {
            if(!resolvingDownloadPage.compareAndSet(false,true)) return
            binding.statusText.text="Checking website address and handler…"
            Thread {
                val pageLog=StringBuilder()
                val result=runCatching { PageRedirects.resolve(this,url,{ isFinishing || isDestroyed },{ pageLog.append(it).append('\n') },
                    importedCookieFile.takeIf { it.isFile }?.absolutePath.orEmpty(),cookies,userAgent,extractorFallback=true) }
                runOnUiThread {
                    resolvingDownloadPage.set(false)
                    if(!isFinishing && !isDestroyed) {
                        if(overrideUrl==null && binding.urlInput.text?.toString()?.trim()!=url) binding.statusText.text="Link changed; press Download again."
                        else result.onSuccess { resolved ->
                            binding.statusText.text="Website checked; choose download options."
                            startDownload(overrideUrl=url,referer=referer,cookies=cookies,userAgent=userAgent,capturedHeadersJson=capturedHeadersJson,
                                browserCaptured=false,downloadInfoJson=downloadInfoJson,downloadDiagnostics=(downloadDiagnostics+"\n"+pageLog).takeLast(2000),
                                playbackChosen=playbackChosen || resolved.extractorOnly,pageChecked=true,choiceTarget=resolved.target)
                        }.onFailure { binding.statusText.text=it.message ?: "Website check failed; review the link in Browser Assist." }
                    }
                }
            }.start()
            return
        }
        if(!browserCaptured && !playbackChosen && WebsiteSourceResolver.site(choiceTarget ?: url) in setOf("AniWaves","h123movies")) {
            val target=choiceTarget ?: url
            WebsiteChoiceDialog.choose(this,target) {
                PlaybackChoice.set(this,url,PlaybackChoice.get(this,PlaybackChoice.clean(target)))
                startDownload(overrideUrl=PlaybackChoice.apply(this,url,true),playbackChosen=true,pageChecked=true,choiceTarget=target)
            }
            return
        }
        if(browserCaptured && binding.formatInput.text.toString()==DownloadService.PRESET_SUBTITLES) {
            binding.statusText.text="For subtitles, use the original video webpage URL on the main page instead of a browser-captured media URL."
            return
        }
        val args = try { CommandTokenizer.parse(binding.customArgsInput.text?.toString().orEmpty()) }
        catch (error: IllegalArgumentException) { binding.customArgsInput.error = error.message; return }
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_DOWNLOAD).apply {
            putExtra(EXTRA_URL, url); putExtra(EXTRA_PRESET, binding.formatInput.text.toString())
            putStringArrayListExtra(EXTRA_ARGS, ArrayList(args)); putExtra(EXTRA_PLAYLIST, binding.playlistSwitch.isChecked)
            putExtra(DownloadService.EXTRA_SPEED_BOOST, binding.speedBoostSwitch.isChecked)
            putExtra(EXTRA_SUBTITLES, binding.subtitleSwitch.isChecked); putExtra(EXTRA_AUTO_SUBTITLES, binding.autoSubtitleSwitch.isChecked)
            putExtra(EXTRA_SUBTITLE_LANGUAGES, binding.subLanguageInput.text?.toString()?.trim().orEmpty())
            putExtra(DownloadService.EXTRA_REFERER, referer); putExtra(DownloadService.EXTRA_COOKIES, cookies)
            putExtra(DownloadService.EXTRA_USER_AGENT, userAgent); putExtra(DownloadService.EXTRA_HEADERS_JSON, capturedHeadersJson)
            putExtra(DownloadService.EXTRA_BROWSER_CAPTURED, browserCaptured)
            putExtra(DownloadService.EXTRA_DOWNLOAD_INFO_JSON, downloadInfoJson)
            putExtra(DownloadService.EXTRA_DOWNLOAD_DIAGNOSTICS, downloadDiagnostics)
            if (importedCookieFile.isFile) putExtra(DownloadService.EXTRA_COOKIE_FILE, importedCookieFile.absolutePath)
        }
        if(!browserCaptured) {
            try {
                val options=extraOptions.snapshot()
                intent.putStringArrayListExtra(EXTRA_ARGS,ArrayList(args+ExtraDownloadOptions.arguments(options,binding.playlistSwitch.isChecked)))
                ExtraDownloadOptions.template(options,binding.playlistSwitch.isChecked)?.let { intent.putExtra("normal_output_template",it) }
            } catch(e: IllegalArgumentException) { Toast.makeText(this,e.message,Toast.LENGTH_LONG).show();return }
        }
        if (prepareBrowserBatch != null) {
            prepareBrowserBatch(intent)
            return
        }
        DownloadDuplicates.submit(this,intent,if(browserCaptured) "Browser-assisted video download" else url,
            declined={ binding.statusText.text="Existing task kept. No new download started." }) { id ->
            if(TransferQueue.active(this)?.optString("id")!=id) Toast.makeText(this,"Added to the shared download queue",Toast.LENGTH_LONG).show()
        }
    }

    private fun openBrowserAssist(mode: String) {
        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (!validUrl(url)) { binding.urlInput.error = "Enter the protected webpage link first."; return }
        launchBrowserAssist(mode, url)
    }

    private fun launchBrowserAssist(mode: String, url: String) {
        val ig = SocialPostDownload.route(url)?.takeIf { it.name == "Instagram" }
        val page = if (ig != null) "https://www.instagram.com/reel/${ig.postId}/".takeIf {
            java.net.URI(url).path.orEmpty().startsWith("/reel")
        } ?: url else url
        browserAssist.launch(Intent(this, BrowserSessionActivity::class.java).apply {
            putExtra(BrowserSessionActivity.EXTRA_PAGE_URL, page); putExtra(BrowserSessionActivity.EXTRA_MODE, mode)
        })
    }

    private fun updateEngine(automatic: Boolean) {
        if (EngineUpdateCheck.busy || TransferQueue.active(this)!=null || DownloadState.running || MonitoringTaskState.running || MonitoringCoordinator.hasActive() || MonitoringWorker.isChecking()) {
            MonitoringStore.log(this,"engine check skipped; another operation active; checking label reconciled")
            reconcileEngineStatus();return
        }
        if(EngineUpdateCheck.start(applicationContext)) {
            autoUpdateCheckedThisProcess=true
            reconcileEngineStatus()
            if(!automatic) Toast.makeText(this,"Checking yt-dlp update…",Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopManualTask(id: String, discard: Boolean = false) {
        val task = MediaTaskRuntime.manualTask(id) ?: return
        if (discard && !task.recording) return
        val target = if (task.recording) RecordingService::class.java else DownloadService::class.java
        val action = when {
            discard -> RecordingService.ACTION_CANCEL
            task.recording -> RecordingService.ACTION_STOP_SAVE
            else -> ACTION_STOP
        }
        startService(Intent(this, target).setAction(action).putExtra(TransferQueue.JOB, task.id))
    }

    private fun taskLabel(task: MediaTaskRuntime.ManualTask): String {
        val type = if (task.recording) "Recording" else "Download"
        return "$type ${task.order}" + task.host.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()
    }

    private fun createManualRow(id: String): RowManualTaskBinding =
        RowManualTaskBinding.inflate(layoutInflater, binding.manualTasks, true).apply {
            taskStop.setOnClickListener { stopManualTask(id) }
            taskDiscard.setOnClickListener {
                MediaTaskRuntime.manualTask(id)?.takeIf { it.recording }?.let { task ->
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Discard ${taskLabel(task)}?")
                        .setMessage("This recording will be deleted instead of saved.")
                        .setNegativeButton("Keep recording", null)
                        .setPositiveButton("Discard") { _, _ -> stopManualTask(id, discard = true) }
                        .show()
                }
            }
            taskRecover.setOnClickListener {
                MediaTaskRuntime.manualTask(id)?.takeIf { it.recording && it.canRecover && validUrl(it.page) }?.let { task ->
                    startActivity(Intent(this@MainActivity, BrowserSessionActivity::class.java)
                        .putExtra(BrowserSessionActivity.EXTRA_PAGE_URL, task.page)
                        .putExtra(BrowserSessionActivity.EXTRA_MODE, BrowserSessionActivity.MODE_RECORD)
                        .putExtra(BrowserSessionActivity.EXTRA_RECOVERY, true)
                        .putExtra(TransferQueue.JOB, id))
                }
            }
            taskCopyLog.setOnClickListener {
                MediaTaskRuntime.manualTask(id)?.let { task ->
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(taskLabel(task), task.log))
                    Toast.makeText(this@MainActivity, "Diagnostic log copied", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private fun renderManualRows(tasks: List<MediaTaskRuntime.ManualTask>) {
        val ids = tasks.map { it.id }.toSet()
        manualRows.keys.filterNot(ids::contains).forEach { id ->
            manualRows.remove(id)?.let { binding.manualTasks.removeView(it.root) }
        }
        for (task in tasks) {
            val row = manualRows.getOrPut(task.id) { createManualRow(task.id) }
            val label = taskLabel(task)
            val detail = task.status.ifBlank { "Preparing task" }
            val stopLabel = if (task.recording) "Stop & save" else "Stop download"
            if (row.taskTitle.text.toString() != label) row.taskTitle.text = label
            if (row.taskStop.text.toString() != stopLabel) {
                row.taskStop.text = stopLabel
                row.taskStop.contentDescription = "$stopLabel: $label"
                row.taskDiscard.contentDescription = "Discard $label"
                row.taskRecover.contentDescription = "Reopen stalled stream: $label"
                row.taskCopyLog.contentDescription = "Copy diagnostic log: $label"
            }
            row.taskDiscard.visibility = if (task.recording) View.VISIBLE else View.GONE
            row.taskRecover.visibility = if (task.recording && task.canRecover && validUrl(task.page)) View.VISIBLE else View.GONE
            row.taskCopyLog.isEnabled = task.log.isNotBlank()
            if (row.taskStatus.text.toString() != detail) row.taskStatus.text = detail
            VisualPolish.status(row.taskStatus, detail, task.recording)
            VisualPolish.progress(row.taskProgress, task.progress, true,
                task.progress <= 0 && (task.recording || VisualPolish.preparing(detail)), VisualPolish.waiting(detail))
        }
    }

    private fun renderState(running: Boolean, progress: Int, status: String, log: String, operation: String) = with(binding) {
        val tasks = MediaTaskRuntime.manualTasks()
        val solo = tasks.singleOrNull()
        val multiple = tasks.size > 1
        singleManualTaskId = solo?.id.orEmpty()
        val idle = !running && tasks.isEmpty() && !updatingEngine
        downloadButton.isEnabled = !updatingEngine; browserAssistButton.isEnabled = !updatingEngine; recordStreamButton.isEnabled = !updatingEngine
        updateButton.isEnabled = idle; importCookiesButton.isEnabled = idle
        removeCookiesButton.isEnabled = idle && importedCookieFile.isFile; speedBoostSwitch.isEnabled = idle

        stopButton.visibility = if (multiple) View.GONE else View.VISIBLE
        progressBar.visibility = if (multiple) View.GONE else View.VISIBLE
        logText.visibility = if (multiple) View.GONE else View.VISIBLE
        copyLogButton.visibility = if (multiple) View.GONE else View.VISIBLE
        manualTasks.visibility = if (multiple) View.VISIBLE else View.GONE
        val recording = solo?.recording ?: (operation == DownloadState.OPERATION_RECORD)
        stopButton.isEnabled = solo != null
        stopButton.text = if (recording) "Stop & save" else "Stop"
        discardRecordingButton.visibility = if (solo?.recording == true) View.VISIBLE else View.GONE
        discardRecordingButton.isEnabled = solo?.recording == true
        reopenStalledStreamButton.visibility = if (solo?.recording == true &&
            solo.canRecover && validUrl(solo.page)) View.VISIBLE else View.GONE

        renderManualRows(if (multiple) tasks else emptyList())
        val previousRowCount = manualRowCount
        manualRowCount = if (multiple) tasks.size else 0
        if (multiple) {
            // Keep Main's input/validation messages visible while rows refresh independently.
            if (previousRowCount != tasks.size) {
                val summary = "${tasks.size} manual tasks running"
                statusText.text = summary
                VisualPolish.status(statusText, summary)
            }
            return@with
        }
        val visibleStatus=(solo?.status ?: status).ifBlank { "Ready • Saves to Downloads/yt-dlp Mobile" }
        val currentProgress=solo?.progress ?: progress
        VisualPolish.progress(progressBar, currentProgress.coerceIn(0, 100), running,
            running && currentProgress <= 0 && (recording ||
                VisualPolish.preparing(visibleStatus)), VisualPolish.waiting(visibleStatus))
        if(statusText.text.toString()!=visibleStatus) statusText.text=visibleStatus
        VisualPolish.status(statusText, visibleStatus, running && recording)
        val visibleLog=(solo?.log ?: log).ifBlank { "Waiting for a link…" }
        if(logText.text.toString()!=visibleLog) {
            logText.text=visibleLog
            if(logText.layout!=null) logText.scrollTo(0,logText.layout.getLineTop(logText.lineCount))
        }
    }

    private fun refreshCookieStatus() = with(binding) {
        cookieStatusText.text = if (importedCookieFile.isFile) "Cookies: imported (${importedCookieFile.length()} bytes) • keep this file private" else "Cookies: none imported"
        removeCookiesButton.isEnabled = importedCookieFile.isFile && !DownloadState.running
    }

    private fun validUrl(url: String) = Patterns.WEB_URL.matcher(url).matches() && (url.startsWith("http://") || url.startsWith("https://"))

    private fun acceptSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']')?.let(binding.urlInput::setText)
    }

    private fun applyInsets() {
        listOf(findViewById<View>(R.id.homeShell)).forEach { root ->
            val left = root.paddingLeft; val top = root.paddingTop
            val right = root.paddingRight; val bottom = root.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom)
                insets
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val COLLECTIONS_PAGE = 0
        private const val MAIN_PAGE = 1
        private const val MONITORING_PAGE = 2
        @Volatile private var autoUpdateCheckedThisProcess = false
    }
}
