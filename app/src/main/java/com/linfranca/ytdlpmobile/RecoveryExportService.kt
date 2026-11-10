package com.linfranca.ytdlpmobile

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Exports retained bytes under the existing media lease; it never reconnects to a live stream. */
class RecoveryExportService : Service() {
    companion object {
        const val SAVE="com.linfranca.ytdlpmobile.SAVE_RECOVERY"
        const val CANCEL="com.linfranca.ytdlpmobile.CANCEL_RECOVERY"
    }
    private val canceled=AtomicBoolean(false)
    private val saving=AtomicBoolean(false)
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==CANCEL) { canceled.set(true);return START_NOT_STICKY }
        val repair=intent?.getBooleanExtra("repair_wrapped_video",false)==true
        val samples=intent?.getBooleanExtra("fragment_samples",false)==true
        val diagnostics=intent?.getBooleanExtra("fragment_diagnostics",false)==true
        val partialDownload=intent?.getBooleanExtra("partial_download",false)==true
        val audioOnly=intent?.getBooleanExtra("partial_audio_only",false)==true
        val monitoring=intent?.getBooleanExtra("partial_monitor",false)==true
        val id=intent?.getStringExtra(TransferQueue.JOB).orEmpty()
        val lease=intent?.getStringExtra(TransferQueue.LEASE).orEmpty()
        if(lease.isBlank() || !TransferQueue.owns(this,id,lease)) { stopSelfResult(startId);return START_NOT_STICKY }
        if(!saving.compareAndSet(false,true)) return START_NOT_STICKY
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("recovery_exports","Saving recovered recordings",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,21021,Intent(this,TransferQueueActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startForeground(21021,NotificationCompat.Builder(this,"recovery_exports").setSmallIcon(R.drawable.ic_download)
                .setContentTitle(if(samples) "Exporting sample fragments" else if(repair) "Recovering retained video" else if(diagnostics) "Exporting fragment diagnostics" else if(partialDownload) "Saving partial video" else "Saving recovered recording").setContentText("Original capture may be partial")
                .setContentIntent(open).setOngoing(true).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            TransferQueue.started(this,id,lease)
        } catch(e: Exception) {
            TransferQueue.complete(this,id,"Recovery save could not start: ${e.javaClass.simpleName}","Retained capture kept",false,lease)
            stopSelfResult(startId);return START_NOT_STICKY
        }
        Thread {
            var status="Recovery save interrupted; original capture kept"
            var uri: android.net.Uri?=null
            var wake: android.os.PowerManager.WakeLock?=null
            var rebuilt: PartialMediaRecovery.Output?=null
            var repaired: WrappedTsRecovery.Result?=null
            val repairLog=StringBuilder()
            try {
                wake=getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"ytdlpmobile:recovery-export").apply { acquire(600_000) }
                if(repair) {
                    val retainedFolder=TransferQueue.workDirectory(this,id,"download")
                    val sources=PartialDownloadCache.recoverable(retainedFolder)
                    if(sources.none { PngTsNormalizer.looksWrapped(it) }) {
                        val logger: (String)->Unit={ message -> repairLog.append(message).append('\n');TransferQueue.progress(this,id,"Recovering retained media locally…",repairLog.toString(),lease) }
                        logger("General retained-media recovery; ordinary input; networkRequests=0; originals retained")
                        rebuilt=PartialMediaRecovery.buildFinalized(this,retainedFolder,false,{ canceled.get() },log=logger)
                        val result=rebuilt!!
                        val expected=runCatching {
                            val selection=java.io.File(retainedFolder,"browser-selection.json")
                            check(selection.length() in 1..8_000_000)
                            org.json.JSONObject(selection.readText()).let { it.optDouble("_app_expected_duration",it.optDouble("duration",0.0)) }
                        }.getOrDefault(0.0)
                        val complete=expected.isFinite() && expected>0 && PostprocessRecoveryPolicy.complete(expected,result.media.seconds,result.media.video,result.media.audio,result.missing)
                        val prefix=if(complete) "Recovered-video" else "Partial-recovered-video"
                        logger("General retained-media result; expectedSeconds=$expected; savedSeconds=${result.media.seconds}; videoTracks=${result.media.video}; audioTracks=${result.media.audio}; knownMissing=${result.missing}; verifiedComplete=$complete")
                        val saved=exportRepairCopy(result.file,"$prefix-${System.currentTimeMillis()}.mkv","video/x-matroska","Download/yt-dlp Mobile"+(if(monitoring) "/Monitoring" else ""))
                        TransferQueue.exported(this,id,saved,lease)
                        status="$prefix saved: ${result.media.seconds.toInt()} seconds; video tracks=${result.media.video}; audio tracks=${result.media.audio}; known missing fragments=${result.missing}. Verified complete=$complete. Original media and cache kept."
                        return@Thread
                    }
                    repaired=WrappedTsRecovery.build(this,TransferQueue.workDirectory(this,id,"download"),{ canceled.get() }) { message ->
                        repairLog.append(message).append('\n')
                        TransferQueue.progress(this,id,message,repairLog.toString(),lease)
                    }
                    val result=repaired!!
                    val prefix=if(result.partial) "Partial-recovered-video" else "Recovered-video"
                    val folder="Download/yt-dlp Mobile"+(if(monitoring) "/Monitoring" else "")
                    val saved=exportRepairCopy(result.file,"$prefix-${System.currentTimeMillis()}.mp4","video/mp4",folder)
                    TransferQueue.exported(this,id,saved,lease)
                    status="$prefix saved: ${result.media.seconds.toInt()} seconds; video tracks=${result.media.video}; audio tracks=${result.media.audio}; image-only placeholders=${result.stats.emptyWrappers}. Original media and cached fragments kept."
                    try {
                        val report=java.io.File(result.file.parentFile,"recovery-report.json")
                        val reportUri=exportRepairCopy(report,"$prefix-${System.currentTimeMillis()}-report.json","application/json",folder)
                        TransferQueue.exported(this,id,reportUri,lease)
                    } catch(e: Exception) {
                        status+=" Video copy is saved; the separate recovery report could not be exported (${e.javaClass.simpleName})."
                    }
                    return@Thread
                }
                if(samples) {
                    val exported=FragmentSamples.export(this,TransferQueue.workDirectory(this,id,"download"),{ canceled.get() }) { message ->
                        TransferQueue.progress(this,id,message,"Sample export only; original media unchanged",lease)
                    }
                    TransferQueue.exported(this,id,exported,lease)
                    status="Sample fragments saved to Downloads/yt-dlp Mobile/Diagnostics. Original media and cache kept."
                    return@Thread
                }
                if(diagnostics) {
                    val report=FragmentDiagnostics.export(this,TransferQueue.workDirectory(this,id,"download"),{ canceled.get() }) { message ->
                        TransferQueue.progress(this,id,message,"Diagnostic inspection only; retained files unchanged",lease)
                    }
                    TransferQueue.exported(this,id,report,lease)
                    status="Fragment diagnostics saved to Downloads/yt-dlp Mobile/Diagnostics. Original task, fragments and media retained unchanged."
                    return@Thread
                }
                val folder=if(partialDownload) TransferQueue.workDirectory(this,id,"download") else TransferQueue.recoveryRoot(this,id)
                val candidate=if(partialDownload) {
                    rebuilt=PartialMediaRecovery.buildFinalized(this,folder,audioOnly,{ canceled.get() },log={ message ->
                        repairLog.append(message).append('\n')
                        TransferQueue.progress(this,id,"Finalizing retained media locally…",repairLog.toString(),lease)
                    })
                    RecordingMedia.Candidate(rebuilt!!.file,"mkv")
                } else RecordingMedia.choose(folder,true,true) ?: RecordingMedia.choose(folder,false,true)
                    ?: error("No recognizable recording could be recovered. Retained files were kept.")
                StorageGuard.destination(this,candidate.file.length())
                val values=ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME,"${if(partialDownload) "Partial" else "recovered"}-${System.currentTimeMillis()}.${candidate.extension}")
                    put(MediaStore.Downloads.MIME_TYPE,when(candidate.extension) { "mp4"->"video/mp4";"webm"->"video/webm";"ts"->"video/mp2t";else->"video/x-matroska" })
                    put(MediaStore.Downloads.RELATIVE_PATH,if(partialDownload) "Download/yt-dlp Mobile"+(if(monitoring) "/Monitoring" else "") else "Download/yt-dlp Mobile/Recordings")
                    put(MediaStore.Downloads.IS_PENDING,1)
                }
                uri=contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values) ?: error("Android could not create the recovered output")
                val watch=StorageGuard.Watch(this)
                contentResolver.openOutputStream(uri!!,"w")!!.use { out -> candidate.file.inputStream().use { input ->
                    val buffer=ByteArray(262144)
                    while(true) {
                        if(canceled.get()) throw InterruptedException("Recovery save canceled; original kept")
                        watch.poll();val count=input.read(buffer);if(count<0) break;out.write(buffer,0,count)
                    }
                } }
                check(contentResolver.update(uri!!,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)>0) { "Android could not publish the recovered recording" }
                TransferQueue.exported(this,id,uri!!,lease)
                uri=null
                // Keep other fragments/attempts for review. Only the successfully exported source is removed.
                candidate.file.delete()
                status=if(partialDownload) rebuilt!!.let { "Partial video saved: ${it.media.seconds.toInt()} seconds; video tracks=${it.media.video}, audio tracks=${it.media.audio}; known missing fragments=${it.missing}. Playback may contain gaps. Original cache retained for retry." } else "Partial recording saved: recovered capture; verify playback and audio"
            } catch(e: Exception) {
                status=if(StorageGuard.failure(e)) StorageGuard.MESSAGE else if(repair) "Local video recovery failed: ${e.message}; original media and cache kept" else if(samples) "Sample fragment export failed: ${e.javaClass.simpleName}; original media and cache kept" else if(diagnostics) "Fragment diagnostics failed: ${e.javaClass.simpleName}; retained media unchanged" else "Recovery save failed: ${e.message}. Original capture kept."
            } finally {
                repaired?.file?.parentFile?.deleteRecursively() // New staging copy only; originals stay in the download workspace.
                rebuilt?.file?.delete()
                uri?.let { runCatching { contentResolver.delete(it,null,null) } }
                try { TransferQueue.complete(this,id,status,if(repair || partialDownload) repairLog.toString()+"\n"+status else status,lease=lease) }
                finally {
                    if(wake?.isHeld==true) wake?.release()
                    stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                }
            }
        }.start()
        return START_NOT_STICKY
    }
    private fun exportRepairCopy(source: java.io.File,name: String,mime: String,folder: String): android.net.Uri {
        StorageGuard.destination(this,source.length())
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime)
            put(MediaStore.Downloads.RELATIVE_PATH,folder);put(MediaStore.Downloads.IS_PENDING,1)
        }
        val uri=contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values) ?: error("Android could not create the recovered copy")
        try {
            val watch=StorageGuard.Watch(this)
            contentResolver.openOutputStream(uri,"w")!!.use { output -> source.inputStream().use { input ->
                val bytes=ByteArray(262144)
                while(true) {
                    if(canceled.get()) throw InterruptedException("Local recovery export canceled")
                    watch.poll();val n=input.read(bytes);if(n<0) break;output.write(bytes,0,n)
                }
            } }
            check(contentResolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)>0) { "Android could not publish the recovered copy" }
            return uri
        } catch(e: Exception) { runCatching { contentResolver.delete(uri,null,null) };throw e }
    }
    override fun onDestroy() { canceled.set(true);super.onDestroy() }
    override fun onTimeout(startId: Int,fgsType: Int) { canceled.set(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
}
