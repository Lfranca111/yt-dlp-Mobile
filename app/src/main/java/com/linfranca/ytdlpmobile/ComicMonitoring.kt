package com.linfranca.ytdlpmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object ComicStore {
    private fun file(c: Context)=AtomicFile(File(c.filesDir,"comic-monitoring/rules.json").apply { parentFile!!.mkdirs() })
    @Synchronized fun rules(c: Context): List<JSONObject> = runCatching { val a=JSONArray(file(c).openRead().bufferedReader().use { it.readText() });(0 until a.length()).map { a.getJSONObject(it) } }.getOrDefault(emptyList())
    @Synchronized fun put(c: Context,r: JSONObject) {
        val list=rules(c).toMutableList()
        // A settings dialog can be older than a completed background check. Keep the latest page journal.
        list.find { it.optString("id")==r.getString("id") }?.let { current ->
            listOf("known","baseline","pending","saved","last_number","last","status","failures","recoveryKind").forEach { key -> if(current.has(key)) r.put(key,current.get(key)) }
        }
        list.removeAll { it.optString("id")==r.getString("id") };list.add(r);write(c,list)
    }
    @Synchronized fun remove(c: Context,id: String) { write(c,rules(c).filterNot { it.optString("id")==id });WorkManager.getInstance(c).cancelUniqueWork("comic-$id") }
    @Synchronized fun mutate(c: Context,id: String,generation: Long,block: (JSONObject)->Unit): Boolean {
        val list=rules(c);val r=list.find { it.optString("id")==id && it.optLong("generation")==generation } ?: return false
        block(r);write(c,list);return true
    }
    private fun write(c: Context,list: List<JSONObject>) { val f=file(c);val out=f.startWrite();try { out.write(JSONArray(list).toString().toByteArray());f.finishWrite(out) } catch(e: Exception) { f.failWrite(out);throw e } }
    fun items(a: JSONArray?) = if(a==null) emptyList() else (0 until a.length()).map { CollectionJournal.decode(a.getJSONObject(it)) }
    fun array(items: List<CollectionItem>)=JSONArray(items.map(CollectionJournal::item))
    fun schedule(c: Context,r: JSONObject,now: Boolean=false,successor: Boolean=false) {
        if(!r.optBoolean("enabled",true) && !now) return
        val time=System.currentTimeMillis();val days=r.optString("days","1,2,3,4,5,6,7").split(',').mapNotNull(String::toIntOrNull).toSet()
        val due=if(now) time else if(!successor && r.optLong("next")>0) r.optLong("next") else MonitoringRecoveryPolicy.nextCheck(time,r.optInt("interval",60),r.optInt("failures"),r.optString("zone",java.time.ZoneId.systemDefault().id),r.optInt("start",-1),r.optInt("end",-1),days,r.optString("recoveryKind"))
        mutate(c,r.getString("id"),r.optLong("generation")) { it.put("next",due) }
        val request=OneTimeWorkRequestBuilder<ComicMonitorWorker>().setInputData(workDataOf("rule" to r.getString("id"),"generation" to r.optLong("generation"),"manual" to now,"due" to due))
            .setInitialDelay((due-time).coerceAtLeast(0),TimeUnit.MILLISECONDS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniqueWork("comic-${r.getString("id")}",if(successor) ExistingWorkPolicy.APPEND_OR_REPLACE else if(now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)
        MonitoringWakeup.refresh(c)
        MonitoringPowerDiagnostics.record(c,"comic scheduled; rule=${r.getString("id")}; due=$due")
    }
    fun initialize(c: Context) { rules(c).forEach { schedule(c,it) } }
}

/** Checks and page downloads use an independent collection lane, never the video queue. */
class ComicMonitorWorker(c: Context,p: WorkerParameters): Worker(c,p) {
    private var connection: HttpURLConnection?=null
    override fun onStopped() {
        connection?.disconnect()
        val reason = if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else -512
        MonitoringPowerDiagnostics.record(applicationContext,"comic check stopped; work=$id",reason)
        super.onStopped()
    }
    override fun doWork(): Result {
        val c=applicationContext;val id=inputData.getString("rule") ?: return Result.failure()
        var r=ComicStore.rules(c).find { it.optString("id")==id && it.optLong("generation")==inputData.getLong("generation",-1) } ?: return Result.success()
        val generation=r.getLong("generation")
        if(!r.optBoolean("enabled",true) && !inputData.getBoolean("manual",false)) return Result.success()
        if(CollectionState.running) return Result.retry()
        if(!CollectionLane.lock.tryLock()) return Result.retry()
        var reschedule=false
        var attempted=false
        var journal="";val log=StringBuilder()
        fun event(s: String) { log.append(MonitoringStore.safe(s)).append('\n');MonitoringStore.log(c,"comic rule=$id; $s") }
        try {
            val current=ComicStore.rules(c).find { it.optString("id")==id && it.optLong("generation")==generation } ?: return Result.success()
            r=current
            val manual=inputData.getBoolean("manual",false)
            if(!manual && (!current.optBoolean("enabled",true) || !MonitoringRecoveryPolicy.currentCheck(inputData.getLong("due",0),current.optLong("next"),System.currentTimeMillis()))) return Result.success()
            reschedule=!manual
            MonitoringPowerDiagnostics.record(c,"comic check started; rule=$id; scheduled=${r.optLong("next")}; actual=${System.currentTimeMillis()}")
            val days=r.optString("days","1,2,3,4,5,6,7").split(',').mapNotNull(String::toIntOrNull).toSet()
            if(!inputData.getBoolean("manual",false) && !MonitoringPolicy.inWindow(System.currentTimeMillis(),r.optString("zone"),r.optInt("start",-1),r.optInt("end",-1),days)) return Result.success()
            check(MonitoringPowerDiagnostics.networkUsable(c)) { "Network is unreachable" }
            val request=Intent(c,CollectionDownloadService::class.java).setAction(CollectionDownloadService.ACTION_START).putExtra(CollectionDownloadService.EXTRA_LINK,r.getString("url")).putExtra("comic_rule",id)
            val credentials=r.optJSONObject("credentials") ?: JSONObject()
            val keys=listOf(CollectionDownloadService.EXTRA_USER,CollectionDownloadService.EXTRA_KEY,CollectionDownloadService.EXTRA_FURBOORU_KEY,CollectionDownloadService.EXTRA_RULE34_USER,CollectionDownloadService.EXTRA_RULE34_KEY)
            keys.forEach { request.putExtra(it,credentials.optString(it)) }
            journal=CollectionJournal.begin(c,request,"Comic updates — ${r.optString("name")}")
            event("check started; known=${r.optJSONArray("known")?.length() ?: 0}; automatic=${r.optBoolean("automatic")}")
            val fresh=mutableListOf<CollectionItem>()
            CollectionSources(CollectionCredentials(keys.map { credentials.optString(it) }[0],credentials.optString(keys[1]),credentials.optString(keys[2]),credentials.optString(keys[3]),credentials.optString(keys[4])),{ isStopped },{ connection=it },{ event(it) }) { _,batch ->
                check(fresh.size+batch.size<=5000) { "Too many pages for a single comic check; baseline retained" };fresh.addAll(batch)
            }.discover(CollectionRoute.from(r.getString("url")))
            check(fresh.isNotEmpty()) { "No pages returned; baseline retained" }
            val old=ComicStore.items(r.optJSONArray("known"))
            val diff=ComicDiff.compare(old,fresh)
            check(diff.review.isBlank()) { diff.review }
            val baseline=r.optBoolean("baseline")
            val additions=if(!baseline && !r.optBoolean("initial_download")) emptyList() else diff.additions
            val pending=ComicStore.items(r.optJSONArray("pending")).associateBy(CollectionJournal::key).toMutableMap()
            additions.forEach { pending[CollectionJournal.key(it)]=it }
            // Persist discovery before downloading. An interruption leaves durable pending pages.
            check(ComicStore.mutate(c,id,generation) { it.put("known",ComicStore.array(fresh)).put("baseline",true).put("pending",ComicStore.array(pending.values.toList())).put("last",System.currentTimeMillis()).put("failures",0).put("recoveryKind","").put("status","${additions.size} new pages; ${pending.size} pending") }) { "Rule changed during check" }
            event("complete source check; ${fresh.size} pages; ${additions.size} new; ${pending.size} pending")
            if(additions.isNotEmpty() && !r.optBoolean("automatic")) MonitoringNotifications.discovery(c,id,"Comic updated: ${r.optString("name")}","${additions.size} new pages. Open Monitoring to download.")
            if(pending.isNotEmpty() && (r.optBoolean("automatic") || r.optBoolean("download_pending") || (!baseline && r.optBoolean("initial_download")))) {
                attempted=true
                val manager=c.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("comic_updates","Comic update downloads",NotificationManager.IMPORTANCE_LOW))
                val notification=NotificationCompat.Builder(c,"comic_updates").setSmallIcon(R.drawable.ic_download).setContentTitle("Downloading comic updates").setContentText(r.optString("name"))
                    .setOngoing(true).setProgress(0,0,true).addAction(0,"Cancel",WorkManager.getInstance(c).createCancelPendingIntent(this.id)).build()
                setForegroundAsync(ForegroundInfo(id.hashCode(),notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)).get()
                CollectionJournal.discovered(c,journal,pending.values.toList())
                val saved=r.optJSONObject("saved") ?: JSONObject()
                var next=r.optInt("last_number",old.size)
                val selected=r.optJSONArray("retry_keys")?.let { keys -> (0 until keys.length()).map { keys.getString(it) }.toSet() }
                pending.values.toList().filter { selected==null || CollectionJournal.key(it) in selected }.forEach { original ->
                    if(isStopped) throw InterruptedException()
                    val key=CollectionJournal.key(original)
                    // Refresh expiring locators by stable identity on every successful source check.
                    val item=fresh.find { CollectionJournal.key(it)==key } ?: return@forEach
                    try {
                        val numbered=item.copy(order=next+1)
                        val uri=CollectionTransferIO.write(c,numbered,r.optString("destination"),"yt-dlp Mobile/Collections/${CollectionTransferIO.safe(r.optString("name"))}",{isStopped}) { connection=it }
                        saved.put(key,uri.toString());pending.remove(key);next++
                        CollectionJournal.result(c,journal,original,"saved",uri=uri.toString())
                        check(ComicStore.mutate(c,id,generation) { it.put("pending",ComicStore.array(pending.values.toList())).put("saved",saved).put("last_number",next) }) { "Rule changed during download" }
                        event("page saved; number=$next; remaining=${pending.size}")
                    } catch(e: InterruptedException) { throw e } catch(e: Exception) {
                        event("page download failed: ${e.javaClass.simpleName}; pending retained")
                        CollectionJournal.result(c,journal,original,"failed",e.javaClass.simpleName)
                    }
                }
            }
            val status="Comic check complete: ${fresh.size} source pages, ${pending.size} pending"
            ComicStore.mutate(c,id,generation) { it.put("download_pending",false).put("status",status);it.remove("retry_keys") }
            CollectionJournal.finished(c,journal,status,log.toString(),attempted);return Result.success()
        } catch(e: Exception) {
            event("check failed; baseline/pending retained: ${e.message}")
            ComicStore.mutate(c,id,generation) { it.put("failures",it.optInt("failures")+1).put("recoveryKind",MonitoringFailure.kind(MonitoringStore.error(e))).put("status","Needs attention: ${MonitoringStore.safe(e.message.orEmpty())}") }
            if(journal.isNotBlank()) CollectionJournal.finished(c,journal,"Comic update needs attention; pending pages retained",log.toString(),false)
            if(!r.optString("status").startsWith("Needs attention:")) MonitoringNotifications.discovery(c,"comic-rule:$id","Comic monitoring needs attention",r.optString("name")+" — open Monitoring")
            return if(isStopped) Result.retry() else Result.success()
        } finally {
            connection?.disconnect();CollectionLane.lock.unlock()
            if(reschedule && !isStopped) ComicStore.rules(c).find { it.optString("id")==id && it.optLong("generation")==generation }?.let { ComicStore.schedule(c,it,successor=true) }
        }
    }
}

internal object CollectionLane { val lock=java.util.concurrent.locks.ReentrantLock() }

internal object CollectionTransferIO {
    fun safe(s: String)=s.replace(Regex("[^a-zA-Z0-9._ -]"),"_").trim('.', ' ').take(90).ifBlank { "comic" }
    fun write(c: Context,item: CollectionItem,tree: String,folder: String,stopped: ()->Boolean,onConnection: (HttpURLConnection?)->Unit): Uri {
        var last: Exception?=null
        repeat(3) { attempt ->
            if(stopped()) throw InterruptedException()
            try { return once(c,item,tree,folder,stopped,onConnection) }
            catch(e: InterruptedException) { throw e }
            catch(e: Exception) { last=e;if(attempt<2) Thread.sleep((attempt+1)*1500L) }
        };throw last ?: IllegalStateException("Download failed")
    }
    private fun once(c: Context,item: CollectionItem,tree: String,folder: String,stopped: ()->Boolean,onConnection: (HttpURLConnection?)->Unit): Uri {
        val u=URL(item.url);require(u.protocol=="https")
        val connection=u.openConnection() as HttpURLConnection;onConnection(connection);var output: Uri?=null
        try {
            connection.connectTimeout=15000;connection.readTimeout=30000;connection.setRequestProperty("User-Agent","yt-dlp Mobile/1.0.14")
            if(item.referer.isNotBlank()) connection.setRequestProperty("Referer",item.referer)
            android.webkit.CookieManager.getInstance().getCookie(item.url)?.let { connection.setRequestProperty("Cookie",it) }
            val code=connection.responseCode
            if(code==404 && item.fallbackUrl.isNotBlank()) return once(c,item.copy(url=item.fallbackUrl,fallbackUrl=""),tree,folder,stopped,onConnection)
            check(code in 200..299) { "Media HTTP $code" }
            val mime=connection.contentType?.substringBefore(';').orEmpty().ifBlank { "application/octet-stream" }
            check(!mime.startsWith("text/") && mime!="application/json") { "Media response was not a file" }
            val ext=item.extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) } ?: "bin"
            val name="${item.order.toString().padStart(5,'0')}-${safe(item.id)}.$ext"
            val values=ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime);put(MediaStore.Downloads.RELATIVE_PATH,"Download/$folder");put(MediaStore.Downloads.IS_PENDING,1) }
            val uri=if(tree.isBlank()) c.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values)
                else { val t=Uri.parse(tree);DocumentsContract.createDocument(c.contentResolver,DocumentsContract.buildDocumentUriUsingTree(t,DocumentsContract.getTreeDocumentId(t)),mime,name) }
            check(uri!=null) { "Could not create destination file" };output=uri
            var total=0L
            connection.inputStream.use { input -> c.contentResolver.openOutputStream(uri,"w")!!.use { out -> val b=ByteArray(65536);while(true) { if(stopped()) throw InterruptedException();val n=input.read(b);if(n<0) break;out.write(b,0,n);total+=n } } }
            check(total>0) { "Empty media file" }
            if(tree.isBlank()) check(c.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)>0)
            output=null;return uri
        } finally {
            output?.let { runCatching { if(tree.isBlank()) c.contentResolver.delete(it,null,null) else DocumentsContract.deleteDocument(c.contentResolver,it) } }
            connection.disconnect();onConnection(null)
        }
    }
}
