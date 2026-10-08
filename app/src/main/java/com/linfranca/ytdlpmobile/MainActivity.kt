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
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var collectionBinding: PageCollectionsBinding
    private lateinit var pager: ViewPager2
    private lateinit var backToMain: OnBackPressedCallback
    private var updatingEngine = false
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
        val mediaUrl = data.getStringExtra(BrowserSessionActivity.EXTRA_MEDIA_URL).orEmpty()
        if (mediaUrl.isBlank()) return@registerForActivityResult
        if (data.getStringExtra(BrowserSessionActivity.EXTRA_MODE) != BrowserSessionActivity.MODE_RECORD) {
            startDownload(
                overrideUrl = mediaUrl,
                referer = data.getStringExtra(BrowserSessionActivity.EXTRA_REFERER).orEmpty(),
                cookies = data.getStringExtra(BrowserSessionActivity.EXTRA_COOKIES).orEmpty(),
                userAgent = data.getStringExtra(BrowserSessionActivity.EXTRA_USER_AGENT).orEmpty(),
                capturedHeadersJson = data.getStringExtra(BrowserSessionActivity.EXTRA_HEADERS_JSON).orEmpty(),
                browserCaptured = true
            )
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STATE) return
            renderState(
                intent.getBooleanExtra(EXTRA_RUNNING, false), intent.getIntExtra(EXTRA_PROGRESS, 0),
                intent.getStringExtra(EXTRA_STATUS).orEmpty(), intent.getStringExtra(EXTRA_LOG).orEmpty(),
                intent.getStringExtra(EXTRA_OPERATION) ?: DownloadState.operation,
                intent.getBooleanExtra(RecordingService.EXTRA_403_RECOVERY_AVAILABLE,
                    DownloadState.recording403RecoveryAvailable)
            )
        }
    }

    private val collectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CollectionDownloadService.ACTION_STATE) renderCollectionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        collectionBinding = PageCollectionsBinding.inflate(layoutInflater)
        setContentView(R.layout.activity_home_pager)
        pager = findViewById(R.id.homePager)
        // Both pages retain their view and scroll position while navigating.
        // Main is on the right, so a right swipe reveals Collections on the left.
        pager.adapter = HomePagesAdapter()
        pager.offscreenPageLimit = 1
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
                backToMain.isEnabled = position == COLLECTIONS_PAGE
            }
        })
        showPageIndicators(MAIN_PAGE)
        applyInsets()
        setupUi()
        setupCollections()
        if (savedInstanceState != null) restoreFormState(savedInstanceState)
        acceptSharedLink(intent)
        requestNotificationPermissionIfNeeded()
        refreshCookieStatus()
        binding.versionText.text = "App ${BuildConfig.VERSION_NAME} • yt-dlp checking…"
        if (!autoUpdateCheckedThisProcess && !DownloadState.running) {
            autoUpdateCheckedThisProcess = true
            updateEngine(automatic = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedLink(intent)
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
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (DownloadState.running && DownloadState.operation == DownloadState.OPERATION_RECORD) {
            startService(Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_USER_INTERACTION))
        }
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        unregisterReceiver(collectionReceiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
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
        subtitleSwitch.setOnCheckedChangeListener { _, checked ->
            autoSubtitleSwitch.isEnabled = checked; subLanguageLayout.isEnabled = checked
            if (!checked) autoSubtitleSwitch.isChecked = false
        }
        downloadButton.setOnClickListener { startDownload() }
        browserAssistButton.setOnClickListener { openBrowserAssist(BrowserSessionActivity.MODE_DOWNLOAD) }
        recordStreamButton.setOnClickListener { openBrowserAssist(BrowserSessionActivity.MODE_RECORD) }
        reopenStalledStreamButton.setOnClickListener {
            val page = DownloadState.recordingPageUrl
            if (DownloadState.running && DownloadState.recording403RecoveryAvailable && validUrl(page)) {
                startActivity(Intent(this@MainActivity, BrowserSessionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(BrowserSessionActivity.EXTRA_PAGE_URL, page)
                    .putExtra(BrowserSessionActivity.EXTRA_MODE, BrowserSessionActivity.MODE_RECORD)
                    .putExtra(BrowserSessionActivity.EXTRA_RECOVERY, true))
            } else {
                Toast.makeText(this@MainActivity, "The recording is no longer stalled under 403.", Toast.LENGTH_SHORT).show()
            }
        }
        stopButton.setOnClickListener {
            val recording = DownloadState.operation == DownloadState.OPERATION_RECORD
            val target = if (recording) RecordingService::class.java else DownloadService::class.java
            val action = if (recording) RecordingService.ACTION_STOP_SAVE else ACTION_STOP
            startService(Intent(this@MainActivity, target).setAction(action))
        }
        discardRecordingButton.setOnClickListener {
            startService(Intent(this@MainActivity, RecordingService::class.java).setAction(RecordingService.ACTION_CANCEL))
        }
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
        collectionProgressBar.isIndeterminate = CollectionState.pageTotal == 0
        collectionProgressBar.max = CollectionState.pageTotal.coerceAtLeast(1)
        collectionProgressBar.progress = CollectionState.pageProcessed
        collectionStatusText.text = CollectionState.status
        collectionLogText.text = CollectionState.log
    }

    private fun showPageIndicators(position: Int) {
        val label = if (position == MAIN_PAGE) "○   ●  Main" else "●   ○  Collections"
        val description = if (position == MAIN_PAGE) "Main screen, page two of two"
            else "Collections screen, page one of two"
        listOf(binding.mainTopPageIndicator, binding.mainBottomPageIndicator,
            collectionBinding.collectionsTopPageIndicator, collectionBinding.collectionsBottomPageIndicator)
            .forEach { indicator ->
                indicator.text = label
                indicator.contentDescription = description
            }
    }

    private inner class HomePagesAdapter : RecyclerView.Adapter<HomePageHolder>() {
        override fun getItemCount() = 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomePageHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return HomePageHolder(container)
        }

        override fun onBindViewHolder(holder: HomePageHolder, position: Int) {
            val page = if (position == COLLECTIONS_PAGE) collectionBinding.root else binding.root
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.removeAllViews()
            holder.container.addView(page, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private class HomePageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private fun startDownload(
        overrideUrl: String? = null, referer: String = "", cookies: String = "", userAgent: String = "",
        capturedHeadersJson: String = "", browserCaptured: Boolean = false
    ) {
        val url = overrideUrl ?: binding.urlInput.text?.toString()?.trim().orEmpty()
        if (!validUrl(url)) { binding.urlInput.error = "Enter a complete http:// or https:// link."; return }
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
            if (importedCookieFile.isFile) putExtra(DownloadService.EXTRA_COOKIE_FILE, importedCookieFile.absolutePath)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun openBrowserAssist(mode: String) {
        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (!validUrl(url)) { binding.urlInput.error = "Enter the protected webpage link first."; return }
        browserAssist.launch(Intent(this, BrowserSessionActivity::class.java).apply {
            putExtra(BrowserSessionActivity.EXTRA_PAGE_URL, url); putExtra(BrowserSessionActivity.EXTRA_MODE, mode)
        })
    }

    private fun updateEngine(automatic: Boolean) {
        if (updatingEngine || DownloadState.running) return
        updatingEngine = true
        renderState(false, DownloadState.progress, if (automatic) "Checking yt-dlp engine…" else "Checking for a yt-dlp update…", DownloadState.log, DownloadState.operation)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                YoutubeDL.init(applicationContext); FFmpeg.init(applicationContext)
                val status = YoutubeDL.updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                val version = YoutubeDL.versionName(applicationContext) ?: YoutubeDL.version(applicationContext) ?: "unknown"
                status to version
            } }
            updatingEngine = false
            result.onSuccess { (status, version) ->
                binding.versionText.text = "App ${BuildConfig.VERSION_NAME} • yt-dlp $version"
                binding.statusText.text = if (automatic) "Ready • yt-dlp $version" else "yt-dlp update: $status • $version"
                if (!automatic) Toast.makeText(this@MainActivity, "yt-dlp is ready", Toast.LENGTH_SHORT).show()
            }.onFailure {
                binding.versionText.text = "App ${BuildConfig.VERSION_NAME} • yt-dlp offline check unavailable"
                binding.statusText.text = if (automatic) "Update check unavailable • installed engine remains usable" else "Update failed: ${it.message}"
            }
            renderState(DownloadState.running, DownloadState.progress, binding.statusText.text.toString(), DownloadState.log, DownloadState.operation)
        }
    }

    private fun renderState(running: Boolean, progress: Int, status: String, log: String, operation: String,
                            recoveryAvailable: Boolean = DownloadState.recording403RecoveryAvailable) = with(binding) {
        val idle = !running && !updatingEngine
        downloadButton.isEnabled = idle; browserAssistButton.isEnabled = idle; recordStreamButton.isEnabled = idle
        updateButton.isEnabled = idle; importCookiesButton.isEnabled = idle
        removeCookiesButton.isEnabled = idle && importedCookieFile.isFile; speedBoostSwitch.isEnabled = idle
        stopButton.isEnabled = running; stopButton.text = if (operation == DownloadState.OPERATION_RECORD) "Stop & save" else "Stop"
        discardRecordingButton.visibility = if (operation == DownloadState.OPERATION_RECORD && running) View.VISIBLE else View.GONE
        discardRecordingButton.isEnabled = operation == DownloadState.OPERATION_RECORD && running
        reopenStalledStreamButton.visibility = if (running && operation == DownloadState.OPERATION_RECORD &&
            recoveryAvailable && validUrl(DownloadState.recordingPageUrl)) View.VISIBLE else View.GONE
        progressBar.isIndeterminate = operation == DownloadState.OPERATION_RECORD && running && progress <= 0
        progressBar.progress = progress.coerceIn(0, 100)
        statusText.text = status.ifBlank { "Ready • Saves to Downloads/yt-dlp Mobile" }
        logText.text = log.ifBlank { "Waiting for a link…" }
        if (logText.layout != null) logText.scrollTo(0, logText.layout.getLineTop(logText.lineCount))
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
        listOf(binding.root, collectionBinding.root).forEach { root ->
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
        @Volatile private var autoUpdateCheckedThisProcess = false
    }
}
