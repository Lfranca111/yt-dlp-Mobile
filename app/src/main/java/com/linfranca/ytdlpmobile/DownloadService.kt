package com.linfranca.ytdlpmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class DownloadEngine(owner: MediaTaskService, taskId: String) : MediaTaskEngine(owner,taskId,false) {
    private val executor = Executors.newSingleThreadExecutor()
    private val presentation=ProgressPresentation()
    private val progressLogs=ProgressLogPresentation()
    private var processId: String? = null
    private val networkChanged = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private var transferLease = ""
    private var transferId = ""
    private var transferStatus = "Interrupted before completion"
    private var transferLog = ""
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action in setOf(ACTION_DOWNLOAD)) {
            val id=intent?.getStringExtra(TransferQueue.JOB).orEmpty()
            val lease=intent?.getStringExtra(TransferQueue.LEASE).orEmpty()
            if(lease.isBlank() || !TransferQueue.owns(this,id,lease)) { stopSelfResult(startId);return START_NOT_STICKY }
            transferId=id;transferLease=lease
        }
        when (intent?.action) {
            ACTION_STOP -> cancelDownload()
            ACTION_DOWNLOAD -> if (!taskState.running) startDownload(intent)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int,fgsType: Int) {
        MonitoringStore.log(this,"media task timeout; task=$taskId; ${DeviceDiagnostics.snapshot(this)}")
        cancelDownload()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if(taskState.running) executor.shutdownNow() else executor.shutdown()
        super.onDestroy()
    }

    private fun startDownload(intent: Intent) {
        val originalUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        var url = originalUrl
        val preset = intent.getStringExtra(EXTRA_PRESET).orEmpty()
        val customArgs = intent.getStringArrayListExtra(EXTRA_ARGS).orEmpty()
        val playlist = intent.getBooleanExtra(EXTRA_PLAYLIST, false)
        val speedBoost = intent.getBooleanExtra(EXTRA_SPEED_BOOST, false)
        val subtitles = intent.getBooleanExtra(EXTRA_SUBTITLES, false)
        val autoSubtitles = intent.getBooleanExtra(EXTRA_AUTO_SUBTITLES, false)
        val subtitleLanguages = intent.getStringExtra(EXTRA_SUBTITLE_LANGUAGES).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        var cookies = intent.getStringExtra(EXTRA_COOKIES).orEmpty()
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        var capturedHeaders = parseCapturedHeaders(intent.getStringExtra(EXTRA_HEADERS_JSON).orEmpty())
        val browserCaptured = intent.getBooleanExtra(EXTRA_BROWSER_CAPTURED, false)
        val cookieFile = intent.getStringExtra(EXTRA_COOKIE_FILE).orEmpty()
        val downloadInfo = intent.getStringExtra(EXTRA_DOWNLOAD_INFO_JSON).orEmpty()
        val outputTemplate=intent.getStringExtra("normal_output_template").orEmpty()

        taskState.running = true
        taskState.operation = taskState.OPERATION_DOWNLOAD
        taskState.progress = 0
        taskState.log = "Starting yt-dlp…" + intent.getStringExtra(EXTRA_DOWNLOAD_DIAGNOSTICS)?.take(1000)?.let { "\n$it" }.orEmpty()
        updateState("Preparing downloader…", 0, taskState.log)
        startForeground(notificationId(NOTIFICATION_ID), buildNotification("Preparing downloader…", 0))
        TransferQueue.started(this,transferId,transferLease)
        watchNetwork()

        executor.execute {
            val sessionDir = TransferQueue.workDirectory(this,transferId,"download")
            var completed=false
            var dedicated: WebsiteHandlerBridge.Prepared?=null
            var extractorOnly=false
            var storageError: Exception?=null
            var lastAttemptFailure = ""
            var mediaTransferStarted = false
            var receivedMediaProgress=false
            var firstFragmentRateLimitExhausted=false
            var lastEngineError=""
            PageRedirects.load(this)
            var websiteFailure=WebsiteDownloadFailure(WebsiteSourceResolver.site(url),browserCaptured,url)
            val segmentAudit=DownloadSegmentAudit()
            val rateRecovery=DownloadRateLimitRecovery()
            var segmentDiagnosticDone=false
            val storageWatch=StorageGuard.Watch(this,sessionDir)
            try {
                StorageGuard.working(this,StorageGuard.expected(intent))
                check(sessionDir.mkdirs() || sessionDir.isDirectory) { "Cannot create the download workspace" }
                if(!browserCaptured && downloadInfo.isBlank()) {
                    val chosen=PlaybackChoice.apply(this,originalUrl)
                    val resolution=PageRedirects.resolve(this,chosen,{ !taskState.running },::appendLog,cookieFile,cookies,userAgent,extractorFallback=true)
                    extractorOnly=resolution.extractorOnly
                    url=if(extractorOnly) originalUrl else resolution.target
                    websiteFailure=WebsiteDownloadFailure(if(extractorOnly) null else WebsiteSourceResolver.site(url),browserCaptured,url)
                    if(extractorOnly) appendLog("Download route=normal yt-dlp; original submitted URL retained; dedicated preparation and website-specific policy bypassed")
                    if(java.net.URI(url).rawAuthority!=java.net.URI(originalUrl).rawAuthority) {
                        cookies="";capturedHeaders=emptyMap()
                        appendLog("domain redirect: raw cookies/captured headers not forwarded to another authority; target-scoped cookie jar remains available")
                    }
                }
                YoutubeDL.init(applicationContext)
                FFmpeg.init(applicationContext)
                if(!browserCaptured && !extractorOnly && (!playlist || WebsiteSourceResolver.individual(url))) dedicated=WebsiteHandlerBridge.prepare(this,url,cookieFile,cookies,userAgent,
                    stopped={ !taskState.running },process={ processId=it },log={ appendLog(it) },onFailure=websiteFailure::preparationFailed)
                val captureCompatible=!browserCaptured && dedicated==null && downloadInfo.isBlank() &&
                    url==originalUrl && !playlist && customArgs.isEmpty() && !subtitles && !autoSubtitles &&
                    cookies.isBlank() && capturedHeaders.isEmpty() &&
                    preset !in setOf(PRESET_MP3,PRESET_M4A,DownloadService.PRESET_SUBTITLES)
                val capturePrepared=if(captureCompatible && CaptureSiteRoutes.video(originalUrl)!=null) {
                    dedicated=CaptureSiteHandlers.prepare(this,sessionDir,originalUrl,cookieFile,userAgent,{ !taskState.running },::appendLog)
                    dedicated!=null
                } else false
                val captureSoop=captureCompatible && CaptureSiteHandlers.useSoopParts(sessionDir,originalUrl)
                if(!browserCaptured && dedicated!=null && WebsiteSourceResolver.site(url)=="h123movies" && preset!=DownloadService.PRESET_SUBTITLES) {
                    val retained=File(sessionDir,PartialDownloadCache.DIRECTORY+"/objects").listFiles().orEmpty().isNotEmpty()
                    if(customArgs.isNotEmpty() || playlist || retained) appendLog("h123 route trial skipped; custom arguments, playlist, or retained fragments require the original transfer selection")
                    else dedicated=H123TransferTrial.choose(this,url,dedicated!!,{ !taskState.running },::appendLog,retain={ PartialDownloadCache.retainTrial(it,sessionDir,::appendLog) }) { info,jar,trialFolder ->
                        appendLog("h123 route sample policy; fragmentConnections=${if(speedBoost) 8 else 1}; verified fragment cache active; trial workspace isolated")
                        val trialRequest=createRequest(url,preset,emptyList(),false,speedBoost,false,false,subtitleLanguages,trialFolder,referer,cookies,userAgent,capturedHeaders,false,jar,info)
                        PartialDownloadCache.configure(this,trialFolder,originalUrl,preset,emptyList(),trialRequest,allowParallel=speedBoost)
                        H123Probe.measure(trialRequest,trialFolder,{ !taskState.running }) { processId=it }
                    }
                }
                if(websiteFailure.serialAni && dedicated==null) throw IllegalStateException("AniWaves automatic preparation failed")

                val socialRoute = if (!browserCaptured && dedicated == null && downloadInfo.isBlank() &&
                    !playlist && customArgs.isEmpty() && !subtitles && !autoSubtitles &&
                    preset !in setOf(PRESET_MP3, PRESET_M4A, PRESET_SUBTITLES) &&
                    sessionDir.listFiles().orEmpty().isEmpty()) runCatching { SocialPostDownload.routeWithShare(originalUrl) { !taskState.running } }.getOrNull() else null
                var socialMedia = socialRoute?.let { route ->
                    runCatching { SocialPostDownload.resolve(route) { !taskState.running } }.getOrNull().also {
                        appendLog(if (it != null) "${route.name}: resolved a public post video" else
                            "${route.name}: no verified public post video; using normal yt-dlp")
                    }
                }
                var socialInfo=""
                if(socialRoute?.name=="Snapchat") {
                    val media=socialMedia
                    if(media!=null) {
                        socialInfo=runCatching { SocialPostDownload.snapchatDownloadInfo(socialRoute,media,userAgent,
                            { !taskState.running },::appendLog) }.getOrNull().orEmpty()
                        if(socialInfo.isBlank()) {
                            socialMedia=null
                            appendLog("Snapchat: could not verify the media format; using the original link")
                        }
                    }
                }
                if (socialRoute != null) check(taskState.running) { "Download stopped" }

                var responseOutput = ""
                var attempt = 0
                var useParallel = speedBoost && !websiteFailure.serialAni
                if(websiteFailure.serialAni) appendLog("AniWaves normal download uses one fragment connection from the first attempt; existing Browser Assist is available if automatic recovery fails.")
                while (true) {
                    val attemptLog = DownloadAttemptLog()
                    var firstFragment429=false
                    lastEngineError=""
                    segmentAudit.newAttempt()
                    rateRecovery.beginAttempt()
                    try {
                        processId = "download-${UUID.randomUUID()}"
                        val request = createRequest(
                            socialMedia ?: url, preset, customArgs, playlist, useParallel, subtitles,
                            autoSubtitles, subtitleLanguages, sessionDir,
                            if (socialMedia == null) referer else "", if (socialMedia == null) cookies else "",
                            userAgent, if (socialMedia == null) capturedHeaders else emptyMap(), browserCaptured,
                            if (socialMedia == null) dedicated?.jar?.absolutePath ?: cookieFile else "",
                            dedicated?.info ?: socialInfo.ifBlank { downloadInfo }, outputTemplate
                        )
                        if(captureSoop) request.addOption("--concat-playlist","multi_video").addOption("--abort-on-error")
                        if(!browserCaptured && preset != DownloadService.PRESET_SUBTITLES) PartialDownloadCache.configure(this,sessionDir,originalUrl,preset,customArgs,request,allowParallel=useParallel && !websiteFailure.serialAni)
                        if(rateRecovery.retries>0 || websiteFailure.serialAni) request.addCommands(listOf("--concurrent-fragments", "1"))
                        appendLog("Download fragment policy; connections=${if(rateRecovery.retries>0 || websiteFailure.serialAni || !useParallel) 1 else 8}; userParallelEnabled=$speedBoost; effectiveParallel=$useParallel; AniWavesSerial=${websiteFailure.serialAni}; rateRecovery=${rateRecovery.retries>0}; retained-fragment cache=${!browserCaptured}")
                        val fatalStopped=java.util.concurrent.atomic.AtomicBoolean(false)
                        val etaEstimator = CumulativeEtaEstimator()
                        val response = YoutubeDL.execute(request, processId, true) { progress, _, line ->
                            if(progress>0f) receivedMediaProgress=true
                            if(line.contains("429") && Regex("""(?i)\bfragment\s+1\b""").containsMatchIn(line)) firstFragment429=true
                            if(line.trimStart().startsWith("ERROR:")) lastEngineError=line.trim()
                            if (progress > 0f || line.startsWith("[download] Destination:") ||
                                line.startsWith("[download] Resuming download")) mediaTransferStarted = true
                            val retainProgressLine=progressLogs.allow(line,SystemClock.elapsedRealtime())
                            if(retainProgressLine) attemptLog.append(line)
                            segmentAudit.observe(line)
                            val limited=rateRecovery.observe(line)
                            val fatalFragment=line.startsWith("ERROR:") && line.contains("fragment",true) && line.contains("unable to continue",true)
                            if((limited || fatalFragment) && fatalStopped.compareAndSet(false,true)) {
                                appendLog(line)
                                appendLog(if(limited) "HTTP 429 observed; stopping this downloader process to cancel outstanding fragment requests." else "Required fragment unavailable; stopping outstanding fragment requests.")
                                processId?.let(YoutubeDL::destroyProcessById)
                            }
                            if(storageError==null) try { storageWatch.poll(line) } catch(e: InsufficientStorage) {
                                storageError=e;processId?.let(YoutubeDL::destroyProcessById)
                            }
                            val safeProgress = progress.coerceIn(0f, 100f).toInt()
                            val stableEta = etaEstimator.update(progress, SystemClock.elapsedRealtime())
                            val etaText = stableEta?.let { " • ETA ${formatEta(it)}" } ?: " • Calculating ETA…"
                            if(retainProgressLine) appendLog(replaceInstantEta(line, stableEta))
                            updateState("Downloading $safeProgress%$etaText", safeProgress, taskState.log,line.startsWith("ERROR:"))
                        }
                        storageError?.let { throw it }
                        rateRecovery.requireNotLimited()
                        check(!fatalStopped.get()) { "Required fragment unavailable; incomplete download retained" }
                        responseOutput = response.out
                        break
                    } catch (error: Exception) {
                        lastAttemptFailure = attemptLog.failure(error)
                        appendLog(segmentAudit.summary())
                        storageError?.let { throw it }
                        if(StorageGuard.failure(error)) throw error
                        if(dedicated!=null && !browserCaptured) websiteFailure.mediaFailed(error,lastAttemptFailure)
                        if(!browserCaptured && taskState.running && preset !in setOf(PRESET_MP3,PRESET_M4A,DownloadService.PRESET_SUBTITLES) && PostprocessRecoveryPolicy.failure(lastAttemptFailure)) {
                            val selected=dedicated?.info ?: downloadInfo
                            val expected=runCatching { JSONObject(selected).let { it.optDouble("_app_expected_duration",it.optDouble("duration",0.0)) } }.getOrDefault(0.0)
                            val knownMissing=PartialDownloadCache.missing(sessionDir)
                            val eligible=PostprocessRecoveryPolicy.eligible(lastAttemptFailure,segmentAudit.fragmentsComplete(),expected,knownMissing)
                            appendLog("Postprocessing failure; network transfer complete=${segmentAudit.fragmentsComplete()}; knownMissing=$knownMissing; localRecoveryEligible=$eligible; original assembly/cache retained")
                            if(eligible) {
                                updateState("Finalizing downloaded media locally…",99,taskState.log)
                                if(PostprocessRecovery.attempt(this,sessionDir,selected,preset==PRESET_MP4,{ !taskState.running },::appendLog)) {
                                    break
                                }
                            }
                            // A local mux failure is not a reason to redownload a completed transfer.
                            throw error
                        }

                        if(rateRecovery.wasLimited(lastAttemptFailure) && taskState.running) {
                            if(!segmentDiagnosticDone) {
                                segmentDiagnosticDone=true
                                val selectedInfo=dedicated?.info ?: downloadInfo
                                val selectedCookies=DownloadSessionFiles.cookieFile(selectedInfo,dedicated?.jar?.absolutePath ?: cookieFile,sessionDir)
                                rateRecovery.respectRetryAfter(MediaSegmentDiagnostic.inspect(selectedInfo,selectedCookies,lastAttemptFailure,{ !taskState.running },::appendLog))
                            }
                            val delay=rateRecovery.nextDelay(lastAttemptFailure)
                            if(delay==null) {
                                firstFragmentRateLimitExhausted=firstFragment429 && !receivedMediaProgress
                                throw IllegalStateException(rateRecovery.message(), error)
                            }
                            useParallel=false
                            val reason="HTTP 429 recovery ${rateRecovery.retries}/2: waiting ${delay/1000}s, then retrying the same selected media with one fragment connection. Any Retry-After observed by the diagnostic request is respected; the downloader itself does not expose response headers."
                            appendLog(reason)
                            updateState(reason,taskState.progress,taskState.log)
                            DownloadRateLimitRecovery.await(delay,{ taskState.running })
                            continue
                        }
                        val parallelFailure = DownloadFailurePolicy.parallelRetry(lastAttemptFailure,useParallel)
                        val changedNetwork = networkChanged.get() && !browserCaptured && !DownloadFailurePolicy.unsupported(lastAttemptFailure)
                        if (attempt == 0 && taskState.running && (parallelFailure || changedNetwork || websiteFailure.shouldRetryTransport(lastAttemptFailure))) {
                            attempt++
                            useParallel = false
                            networkChanged.set(false)
                            val reason = if (changedNetwork) {
                                "Network changed — waiting briefly, then refreshing the media link…"
                            } else if(websiteFailure.shouldRetryTransport(lastAttemptFailure)) {
                                "Temporary AniWaves media request failure — retrying once with one connection…"
                            } else {
                                "Server rejected parallel fragments — retrying once with one connection…"
                            }
                            appendLog(reason)
                            updateState(reason, taskState.progress, taskState.log)
                            DownloadRateLimitRecovery.await(2_000,{ taskState.running })
                        } else {
                            if(!browserCaptured && File(sessionDir,PartialDownloadCache.DIRECTORY+"/objects").listFiles().orEmpty().any { it.extension=="bin" }) throw IllegalStateException("Media download interrupted; partial media and completed fragments retained for Retry or Save partial video. ${lastAttemptFailure.takeLast(500)}",error)
                            if(websiteFailure.serialAni) throw error
                            if (socialMedia != null && taskState.running && sessionDir.listFiles().orEmpty().none {
                                    it.isFile && (it.name.endsWith(".part") || CompletedMediaValidator.looksLikeMedia(it))
                                }) {
                                socialMedia = null
                                socialInfo = ""
                                File(sessionDir,"browser-selection.json").delete()
                                url = originalUrl
                                appendLog("Public post video request failed; retrying the original link with yt-dlp.")
                                continue
                            }
                            if(dedicated!=null && taskState.running) {
                                if(capturePrepared && CaptureSiteHandlers.hasRetainedMedia(sessionDir)) throw error
                                dedicated?.close();dedicated=null
                                File(sessionDir,"browser-selection.json").delete()
                                appendLog("Dedicated website media attempt failed; retrying the original webpage with yt-dlp fallback.")
                                continue
                            }
                            throw error
                        }
                    }
                }

                appendLog(segmentAudit.summary())
                val recoveredMissing=if(!browserCaptured && preset !in setOf(PRESET_MP3,PRESET_M4A,DownloadService.PRESET_SUBTITLES))
                    WrappedTsRecovery.prepareCompleted(this,sessionDir,{ !taskState.running },::appendLog) else 0
                val missing=if(browserCaptured) 0 else maxOf(PartialDownloadCache.missing(sessionDir),segmentAudit.skippedCount())+recoveredMissing
                if(browserCaptured && (dedicated?.info ?: downloadInfo).isNotBlank()) segmentAudit.requireNoSkipped()
                updateState("Saving files to Downloads…", 99, taskState.log)
                if (browserCaptured) {
                    check(sessionDir.walkTopDown().any { it.isFile && CompletedMediaValidator.looksLikeMedia(it) }) {
                        "The browser-selected URL did not produce a playable media file. Reopen the page and detect the actual video."
                    }
                }
                var audioLanguageUnverified=false
                if(!browserCaptured && !extractorOnly && preset!=DownloadService.PRESET_SUBTITLES && WebsiteSourceResolver.site(url)=="h123movies" && PlaybackChoice.get(this,url).audio=="en") {
                    updateState("Selecting English audio…",99,taskState.log)
                    audioLanguageUnverified=H123AudioLanguage.finalize(this,sessionDir,dedicated?.info ?: downloadInfo,{ !taskState.running },::appendLog)
                }
                if(preset != DownloadService.PRESET_SUBTITLES && missing==0) MediaCompletion.verify(sessionDir,dedicated?.info ?: downloadInfo,(preset in setOf(PRESET_MP3,PRESET_M4A) || runCatching { org.json.JSONObject(downloadInfo).optBoolean("_app_audio_only") }.getOrDefault(false)),::appendLog)
                if(missing>0) {
                    val media=PartialDownloadCache.mediaFiles(sessionDir)
                    check(media.isNotEmpty()) { "No assembled partial media; retained fragments kept" }
                    media.forEach { PartialMediaRecovery.validate(it,preset in setOf(PRESET_MP3,PRESET_M4A)) }
                    File(sessionDir,"Partial-download-report.txt").writeText(PartialDownloadCache.gapReport(sessionDir))
                }
                File(sessionDir, "browser-selection.json").delete()
                if (browserCaptured) {
                    sessionDir.walkTopDown().filter { it.isFile && CompletedMediaValidator.looksLikeMedia(it) }.forEach { file ->
                        val tracks = runCatching {
                            val extractor = android.media.MediaExtractor()
                            try {
                                extractor.setDataSource(file.absolutePath)
                                (0 until extractor.trackCount).mapNotNull { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) }
                            } finally { extractor.release() }
                        }.getOrNull()
                        appendLog(if (tracks == null) "Saved media track verification unavailable." else
                            "Saved media tracks: video=${tracks.count { it.startsWith("video/") }}, audio=${tracks.count { it.startsWith("audio/") }}.")
                        if (downloadInfo.isNotBlank() && tracks != null && preset !in setOf(PRESET_MP3, PRESET_M4A) && !runCatching { org.json.JSONObject(downloadInfo).optBoolean("_app_audio_only") }.getOrDefault(false))
                            check(tracks.any { it.startsWith("video/") } && tracks.any { it.startsWith("audio/") }) {
                                "The matched media did not produce both video and audio; no incomplete pair was exported. Detect again."
                            }
                    }
                }
                if(preset == PRESET_SUBTITLES) {
                    check(sessionDir.walkTopDown().any { it.isFile && it.extension.lowercase() in setOf("vtt","srt","ass","ssa","ttml","srv1","srv2","srv3","json3","lrc") }) {
                        "No subtitles were available for the selected languages. Enable automatic captions if appropriate, or choose another language."
                    }
                    sessionDir.walkTopDown().filter { it.isFile && it.extension.equals("vtt",true) }.forEach { file ->
                        runCatching { RollingCaptionCleaner.clean(file) }
                            .onSuccess { removed -> if(removed > 0) appendLog("Cleaned rolling captions in ${file.name}; removed $removed redundant cues and carried-over text.") }
                            .onFailure { appendLog("Caption cleanup unavailable for ${file.name}; original subtitle file retained: ${it.javaClass.simpleName}.") }
                    }
                }
                val exported = exportCompletedFiles(sessionDir,missing>0,preset==PRESET_SUBTITLES)
                check(exported.isNotEmpty()) { "yt-dlp finished but no completed media file was found." }
                val savedSummary = if(missing>0) "Saved with missing segments ($missing): partial files exported; cached media retained for retry" else "Saved ${exported.size} file${if (exported.size == 1) "" else "s"} to Downloads/yt-dlp Mobile"
                val summary = if(audioLanguageUnverified) "$savedSummary. ${H123AudioLanguage.LANGUAGE_WARNING}" else savedSummary
                val finalLog = listOf(taskState.log, responseOutput.takeLast(6_000), summary)
                    .filter { it.isNotBlank() }.joinToString("\n").takeLast(MAX_LOG_CHARS)
                finish(summary, 100, finalLog)
                completed=missing==0
            } catch (e: Exception) {
                val canceled = !taskState.running
                appendLog(segmentAudit.summary())
                val details = "${taskState.log}\n${e.message.orEmpty()}"
                val failure = listOf(lastAttemptFailure,lastEngineError,e.message.orEmpty()).joinToString("\n")
                val fileNameTooLong = failure.contains("File name too long", true) || failure.contains("Errno 36", true)
                val redditBlocked = failure.contains("unable to access the Reddit API", true)
                val authentication = failure.contains("Account authentication is required", true) ||
                    failure.contains("empty media response", true)
                val rumbleHelp=if(!browserCaptured) RumbleMonitoring.accessHelp(originalUrl,failure,monitored=false) else null
                val defaultMessage = when {
                    storageError!=null || StorageGuard.failure(e) || StorageGuard.failure(failure) -> StorageGuard.MESSAGE
                    canceled -> "Download stopped"
                    rumbleHelp!=null -> rumbleHelp
                    failure.contains("extracted extension",true) && failure.contains("skipped for safety",true) ->
                        "yt-dlp rejected the media's unusual filename extension. No video was downloaded."
                    fileNameTooLong -> "Filename too long. Clear any custom output template or shorten its title field."
                    redditBlocked -> "Reddit rejected the metadata API request. Try Browser Assist if the post plays there."
                    authentication -> "The site did not provide media. Try cookies.txt from your signed-in session, or sign in through Browser Assist."
                    rateRecovery.encountered && (DownloadFailurePolicy.rateLimited(lastAttemptFailure) || e.message==rateRecovery.message()) -> rateRecovery.message()
                    failure.contains("no impersonate target", true) ->
                        "This site requires browser protection. Use Browser-assisted download."
                    failure.contains("confirm you’re not a bot", true) || failure.contains("confirm you're not a bot", true) ->
                        "YouTube does not trust this network/IP. Import cookies.txt from your own session or change networks, then retry."
                    failure.contains("Usage: yt-dlp", true) || failure.contains("optparse", true) ->
                        "Invalid Extra arguments. Correct or clear them, then retry."
                    rateRecovery.encountered && DownloadFailurePolicy.unsupported(failure) ->
                        "Media requests were rate-limited (HTTP 429); subsequent webpage fallback was also unsupported. Wait before retrying the selected media, or choose another server in Browser Assist."
                    DownloadFailurePolicy.unsupported(failure) && WebsiteSourceResolver.site(url)=="WCOStream" ->
                        "WCOStream playback needs Browser Assist. Open the episode there, wait for the countdown, tap Close when enabled, play the episode, then tap Detect and download."
                    DownloadFailurePolicy.unsupported(failure) ->
                        "This webpage is not supported by the fallback extractor. Try Browser-assisted download and detect the actual video."
                    browserCaptured && DownloadFailurePolicy.access(failure) ->
                        "The captured stream was still protected or expired. Reopen Browser Assist and play the video again."
                    browserCaptured && failure.contains("410") ->
                        "The captured stream expired or rejected its browser session. Reopen Browser Assist and play the video again."
                    DownloadFailurePolicy.access(failure) ->
                        "Website rejected the request (403). Try Browser-assisted download."
                    else -> "Download failed: ${(lastEngineError.takeIf(String::isNotBlank)
                        ?: lastAttemptFailure.lineSequence().lastOrNull { it.startsWith("ERROR:") })
                            ?.removePrefix("ERROR:")?.trim()?.replace(Regex("https?://[^\\s]+"), "[URL]")?.take(240)
                        ?: e.message?.takeIf(String::isNotBlank)
                        ?: "see the detailed task log"}"
                }
                val invalidArguments=failure.contains("Usage: yt-dlp",true) || failure.contains("optparse",true)
                val primaryMessage=if(canceled || storageError!=null || StorageGuard.failure(e) || StorageGuard.failure(failure) || invalidArguments) defaultMessage
                    else websiteFailure.message(defaultMessage,e,lastAttemptFailure,rateRecovery.serverWaitTooLong)
                val message=if(!browserCaptured && PartialDownloadCache.recoverable(sessionDir).isNotEmpty()) "$primaryMessage Partial media retained: use Retry with fresh detection or Save partial video in Queue & history." else primaryMessage
                val finalLog=if(browserCaptured) "$details\n$message" else listOf(taskState.log,e.message.orEmpty().takeIf { it!=message && message==defaultMessage }.orEmpty(),message).filter(String::isNotBlank).joinToString("\n")
                val browserFallback = !browserCaptured && !mediaTransferStarted && !canceled && !fileNameTooLong && !invalidArguments &&
                    !playlist && preset !in setOf(PRESET_MP3, PRESET_M4A, PRESET_SUBTITLES) &&
                    storageError == null && !StorageGuard.failure(e) && !StorageGuard.failure(failure) &&
                    !rateRecovery.encountered && !PostprocessRecoveryPolicy.failure(failure) &&
                    PartialDownloadCache.recoverable(sessionDir).isEmpty() &&
                    (redditBlocked || authentication || DownloadFailurePolicy.unsupported(failure) ||
                        DownloadFailurePolicy.access(failure) || failure.contains("no impersonate target", true) ||
                        websiteFailure.serialAni && dedicated == null)
                val rateLimitHandoff = !browserCaptured && !canceled && firstFragmentRateLimitExhausted &&
                    !playlist && preset !in setOf(PRESET_MP3, PRESET_M4A, PRESET_SUBTITLES) &&
                    WebsiteSourceResolver.site(url)=="h123movies" && dedicated!=null &&
                    PartialDownloadCache.recoverable(sessionDir).none { it.isFile && it.length()>0 }
                if(rateLimitHandoff) appendLog("h123 first-fragment rate limit: automatic retries exhausted; Browser Assist offered; no server substituted")
                finish(message, taskState.progress, (if(rateLimitHandoff) "$finalLog\n${taskState.log.lineSequence().last()}" else finalLog).takeLast(MAX_LOG_CHARS),
                    if(rateLimitHandoff) PlaybackChoice.apply(this,url) else originalUrl.takeIf { browserFallback },rateLimitHandoff)
            } finally {
                dedicated?.close()
                processId = null
                stopWatchingNetwork()
                if(completed) sessionDir.deleteRecursively()
                else MonitoringStore.log(this,"download workspace retained; task=$transferId; retry may resume supported partial downloads")
                TransferQueue.complete(this,transferId,transferStatus,transferLog,lease=transferLease)
            }
        }
    }

    private fun createRequest(
        url: String,
        preset: String,
        customArgs: List<String>,
        playlist: Boolean,
        speedBoost: Boolean,
        subtitles: Boolean,
        autoSubtitles: Boolean,
        subtitleLanguages: String,
        sessionDir: File,
        referer: String,
        cookies: String,
        userAgent: String,
        capturedHeaders: Map<String, String>,
        browserCaptured: Boolean,
        cookieFile: String,
        downloadInfo: String = "",
        outputTemplate: String = ""
    ): YoutubeDLRequest {
        require(preset != PRESET_SUBTITLES || !browserCaptured) {
            "Subtitles file only needs the original webpage URL. Return to the main page and use that link instead of a captured media URL."
        }
        val infoFile = if (downloadInfo.isNotBlank()) File(sessionDir, "browser-selection.json").apply {
            require(downloadInfo.length < 1_000_000) { "Selected media metadata was too large." }
            writeText(DownloadSessionFiles.downloaderInfo(downloadInfo))
        } else null
        val request = (if (infoFile != null) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(url))
            .addOption("--newline")
            .addOption("--no-mtime")
            .addOption("--progress")
            .addOption("--continue")
            .addOption("--remote-components", "ejs:github")
            .addOption("--retries", 10)
            .addOption("--fragment-retries", 10)
            .addOption("--retry-sleep", "fragment:exp=1:20")
            .addOption("--abort-on-unavailable-fragments")
            .addOption("-o", File(sessionDir, if(outputTemplate.isNotBlank() && !browserCaptured && !outputTemplate.contains('/') && !outputTemplate.contains('\\')) outputTemplate else if (playlist) "%(playlist_index)03d - %(title).120B.%(ext)s" else "%(title).120B.%(ext)s").absolutePath)

        if (infoFile != null) request.addOption("--load-info-json", infoFile.absolutePath)

        request.addOption("--concurrent-fragments", if(speedBoost) MAX_CONCURRENT_FRAGMENTS else 1)

        if (referer.isNotBlank()) request.addOption("--referer", referer)
        if (userAgent.isNotBlank()) request.addOption("--user-agent", userAgent)
        if (infoFile == null && cookies.isNotBlank()) request.addOption("--add-headers", "Cookie:$cookies")
        val sessionCookies=DownloadSessionFiles.cookieFile(downloadInfo,cookieFile,sessionDir)
        if (sessionCookies.isNotBlank() && File(sessionCookies).isFile) request.addOption("--cookies", sessionCookies)
        (if (infoFile == null) capturedHeaders else emptyMap())
            .filterKeys { it.lowercase() !in setOf("cookie", "referer", "user-agent") }
            .forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) {
                    request.addOption("--add-headers", "$name:$value")
                }
            }
        if (browserCaptured) request.addOption("--force-generic-extractor")

        if (playlist) request.addOption("--yes-playlist") else request.addOption("--no-playlist")

        when (preset) {
            PRESET_MP4 -> request
                .addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                .addOption("--merge-output-format", "mp4")
            PRESET_MP3 -> request.addOption("-x").addOption("--audio-format", "mp3").addOption("--audio-quality", "0")
            PRESET_M4A -> request.addOption("-x").addOption("--audio-format", "m4a").addOption("--audio-quality", "0")
            PRESET_SUBTITLES -> Unit
            PRESET_ORIGINAL -> Unit
            else -> request.addOption("-f", "bestvideo*+bestaudio/best")
        }

        if (subtitles || preset == PRESET_SUBTITLES) {
            request.addOption("--write-subs")
            if (autoSubtitles) request.addOption("--write-auto-subs")
            if (subtitleLanguages.isNotBlank()) request.addOption("--sub-langs", subtitleLanguages)
            request.addOption("--convert-subs", "vtt")
        }

        if(!browserCaptured && preset !in setOf(PRESET_MP3,PRESET_M4A,DownloadService.PRESET_SUBTITLES))
            request.addCommands(listOf("--postprocessor-args",MediaProbePolicy.postprocessorArgument()))
        request.addCommands(customArgs)
        if(preset == PRESET_SUBTITLES) {
            request.addOption("--skip-download")
            request.addOption("--write-subs")
        }
        return request
    }

    private fun parseCapturedHeaders(json: String): Map<String, String> = runCatching {
        val objectValue = JSONObject(json)
        buildMap {
            objectValue.keys().forEach { key ->
                objectValue.optString(key).takeIf(String::isNotBlank)?.let { value -> put(key, value) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun exportCompletedFiles(sessionDir: File,partial: Boolean=false,subtitlesOnly: Boolean=false): List<Uri> {
        val files = sessionDir.walkTopDown()
            .filter { it.isFile && !DownloadSessionFiles.privateFile(it) && !PartialDownloadCache.privateFile(it) && !it.name.contains(Regex("-Frag\\d+")) && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && (!subtitlesOnly || it.extension.lowercase() in setOf("vtt","srt","ass","ssa","ttml","srv1","srv2","srv3","json3","lrc")) }
            .toList()
        return files.map { exportToDownloads(it,partial) }
    }

    private fun exportToDownloads(source: File,partial: Boolean=false): Uri {
        val signature=if(File(source.parentFile,PartialDownloadCache.DIRECTORY).isDirectory) "$partial:"+PartialDownloadCache.fingerprint(source) else ""
        TransferQueue.previousExport(this,transferId,source,signature)?.let { return it }
        StorageGuard.destination(this,source.length())
        val displayName = sanitizeFilename(if(partial) "Partial - "+source.nameWithoutExtension.take(180)+"."+source.extension else source.name)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(displayName))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/yt-dlp Mobile")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: error("Android could not create $displayName in Downloads.")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            TransferQueue.exported(this, transferId, uri,transferLease,source,signature)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun sanitizeFilename(name: String): String = name
        .replace(Regex("[\\u0000-\\u001f\\u007f/\\\\]"), "_")
        .take(220)
        .ifBlank { "download-${System.currentTimeMillis()}" }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/opus"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "json", "info.json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun cancelDownload() {
        taskState.running = false
        processId?.let(YoutubeDL::destroyProcessById)
        updateState("Stopping…", taskState.progress, taskState.log)
    }

    private fun watchNetwork() {
        networkChanged.set(false)
        val manager = getSystemService(ConnectivityManager::class.java)
        var firstAvailable = true
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (firstAvailable) firstAvailable = false else networkChanged.set(true)
            }
            override fun onLost(network: Network) { networkChanged.set(true) }
        }.also { runCatching { manager.registerDefaultNetworkCallback(it) } }
    }

    private fun stopWatchingNetwork() {
        val callback = networkCallback ?: return
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        taskState.log = (taskState.log + "\n" + line).takeLast(MAX_LOG_CHARS)
    }

    private fun finish(status: String, progress: Int, log: String, browserFallbackUrl: String? = null, offerBrowserFallback: Boolean = false) {
        transferStatus=status;transferLog=log
        TransferQueue.finalizing(this,transferId,status,transferLease)
        taskState.running = false
        taskState.operation = taskState.OPERATION_IDLE
        taskState.status = status
        taskState.progress = progress
        taskState.log = log
        sendState(browserFallbackUrl,offerBrowserFallback)
        val manager = getSystemService(NotificationManager::class.java)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateState(status: String, progress: Int, log: String,force: Boolean=false) {
        taskState.status = status
        taskState.progress = progress
        taskState.log = log
        if(!presentation.allow(SystemClock.elapsedRealtime(),force || !status.startsWith("Downloading "))) return
        sendState()
        updateTaskNotification(notificationId(NOTIFICATION_ID), buildNotification(status, progress))
    }

    private fun sendState(browserFallbackUrl: String? = null, offerBrowserFallback: Boolean = false) {
        TransferQueue.progress(this,transferId,taskState.status,taskState.log,transferLease,taskState.progress)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            if (browserFallbackUrl != null) {
                putExtra("browser_fallback_page", browserFallbackUrl)
                putExtra("browser_fallback_offer", offerBrowserFallback)
            }
            putExtra(EXTRA_RUNNING, taskState.running)
            putExtra(EXTRA_OPERATION, taskState.operation)
            putExtra(EXTRA_PROGRESS, taskState.progress)
            putExtra(EXTRA_STATUS, taskState.status)
            putExtra(EXTRA_LOG, taskState.log)
        })
    }

    private fun buildNotification(status: String, progress: Int, finished: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("yt-dlp Mobile")
            .setContentText(status)
            .setOnlyAlertOnce(!finished)
            .setOngoing(!finished)
            .setContentIntent(PendingIntent.getActivity(
                this, notificationId(0), Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .apply {
                if (!finished) {
                    setProgress(100, progress, progress == 0)
                    addAction(0, "Stop", PendingIntent.getService(
                        this@DownloadEngine, notificationId(1),
                        Intent(this@DownloadEngine, DownloadService::class.java).setAction(ACTION_STOP).putExtra(TransferQueue.JOB,taskId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ))
                }
            }
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun formatEta(seconds: Long): String {
        val minutes = seconds / 60
        val remaining = seconds % 60
        return if (minutes > 0) "${minutes}m ${remaining}s" else "${remaining}s"
    }

    private fun replaceInstantEta(line: String, stableEtaSeconds: Long?): String {
        if (!line.contains("ETA", ignoreCase = false)) return line
        val replacement = stableEtaSeconds?.let { "ETA ${formatEta(it)}" } ?: "ETA calculating…"
        return line.replace(Regex("\\bETA\\s+\\S+"), replacement)
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.linfranca.ytdlpmobile.DOWNLOAD"
        const val ACTION_STOP = "com.linfranca.ytdlpmobile.STOP"
        const val ACTION_STATE = "com.linfranca.ytdlpmobile.STATE"
        const val EXTRA_URL = "url"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_ARGS = "args"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_SPEED_BOOST = "speed_boost"
        const val EXTRA_SUBTITLES = "subtitles"
        const val EXTRA_AUTO_SUBTITLES = "auto_subtitles"
        const val EXTRA_SUBTITLE_LANGUAGES = "subtitle_languages"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_HEADERS_JSON = "headers_json"
        const val EXTRA_DOWNLOAD_INFO_JSON = "download_info_json"
        const val EXTRA_DOWNLOAD_DIAGNOSTICS = "download_diagnostics"
        const val EXTRA_BROWSER_CAPTURED = "browser_captured"
        const val EXTRA_COOKIE_FILE = "cookie_file"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log"
        const val EXTRA_OPERATION = "operation"

        const val PRESET_BEST = "Best available quality"
        const val PRESET_MP4 = "MP4 compatibility"
        const val PRESET_MP3 = "MP3 audio"
        const val PRESET_M4A = "M4A audio"
        const val PRESET_ORIGINAL = "Original/site-selected format"
        const val PRESET_SUBTITLES = "Subtitles file only"
        val PRESETS = listOf(PRESET_BEST, PRESET_MP4, PRESET_MP3, PRESET_M4A, PRESET_ORIGINAL, PRESET_SUBTITLES)

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 84
        private const val MAX_LOG_CHARS = 16_000
        private const val MAX_CONCURRENT_FRAGMENTS = 8
    }
}

class DownloadService : MediaTaskService() {
    override fun createEngine(id: String): MediaTaskEngine = DownloadEngine(this,id)
    override fun isStart(action: String?) = action in setOf(ACTION_DOWNLOAD)
    companion object {
        const val ACTION_DOWNLOAD = "com.linfranca.ytdlpmobile.DOWNLOAD"
        const val ACTION_STOP = "com.linfranca.ytdlpmobile.STOP"
        const val ACTION_STATE = "com.linfranca.ytdlpmobile.STATE"
        const val EXTRA_URL = "url"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_ARGS = "args"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_SPEED_BOOST = "speed_boost"
        const val EXTRA_SUBTITLES = "subtitles"
        const val EXTRA_AUTO_SUBTITLES = "auto_subtitles"
        const val EXTRA_SUBTITLE_LANGUAGES = "subtitle_languages"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_HEADERS_JSON = "headers_json"
        const val EXTRA_DOWNLOAD_INFO_JSON = "download_info_json"
        const val EXTRA_DOWNLOAD_DIAGNOSTICS = "download_diagnostics"
        const val EXTRA_BROWSER_CAPTURED = "browser_captured"
        const val EXTRA_COOKIE_FILE = "cookie_file"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log"
        const val EXTRA_OPERATION = "operation"
        const val PRESET_BEST = "Best available quality"
        const val PRESET_MP4 = "MP4 compatibility"
        const val PRESET_MP3 = "MP3 audio"
        const val PRESET_M4A = "M4A audio"
        const val PRESET_ORIGINAL = "Original/site-selected format"
        const val PRESET_SUBTITLES = "Subtitles file only"
        val PRESETS = listOf(PRESET_BEST, PRESET_MP4, PRESET_MP3, PRESET_M4A, PRESET_ORIGINAL, PRESET_SUBTITLES)
    }
}
