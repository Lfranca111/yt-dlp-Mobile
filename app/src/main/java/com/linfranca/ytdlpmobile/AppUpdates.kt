package com.linfranca.ytdlpmobile

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import androidx.core.content.FileProvider
import androidx.core.app.NotificationCompat
import androidx.work.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

internal object AppUpdatePolicy {
    const val REPO="Lfranca111/yt-dlp-Mobile"
    const val API="https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE="https://github.com/$REPO/releases"
    fun newer(tag: String,current: String): Boolean {
        fun parts(s: String): List<Long>? { val raw=s.removePrefix("v");if(!raw.matches(Regex("[0-9]+(\\.[0-9]+)*"))) return null;return raw.split('.').map { it.toLongOrNull() ?: return null } }
        val a=parts(tag) ?: return false;val b=parts(current) ?: return false
        for(i in 0 until maxOf(a.size,b.size)) { val x=a.getOrElse(i){0};val y=b.getOrElse(i){0};if(x!=y) return x>y }
        return false
    }
    fun allowed(url: String): Boolean = runCatching {
        val u=URL(url);u.protocol=="https" && u.userInfo==null && u.port in listOf(-1,443) && u.host.lowercase() in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com")
    }.getOrDefault(false)
    fun downloadPayload(r: JSONObject): String = JSONObject().apply { listOf("tag","url","size","name","digest").forEach { put(it,r.get(it)) } }.toString().also { require(it.toByteArray().size<=8192) { "APK download metadata is too large" } }
    fun release(j: JSONObject): JSONObject {
        require(!j.optBoolean("draft") && !j.optBoolean("prerelease")) { "No stable release available" }
        val assets=j.getJSONArray("assets")
        val apks=(0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk",true) }
        val preferred=apks.filter { it.optString("name").contains("universal",true) || it.optString("name").equals("ytdlpMobile.apk",true) }
        val asset=(if(preferred.size==1) preferred else apks).singleOrNull() ?: error("Release needs one universal APK; open GitHub to choose a compatible file")
        val url=asset.getString("browser_download_url")
        require(url.startsWith("https://github.com/$REPO/releases/download/") && allowed(url)) { "Unexpected APK address" }
        val size=asset.getLong("size");require(size in 1..2_147_483_648L) { "Invalid APK size" }
        return JSONObject().put("tag",j.getString("tag_name")).put("url",url).put("size",size).put("name",asset.getString("name")).put("digest",asset.optString("digest")).put("notes",j.optString("body").take(12000))
    }
}

internal object AppUpdates {
    val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var startupDone=false
    fun prefs(c: Context)=c.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
    fun status(c: Context,s: String) { prefs(c).edit().putString("status",s.take(500)).apply() }
    fun release(c: Context)=runCatching { JSONObject(prefs(c).getString("release","")!!) }.getOrNull()
    fun busy(c: Context)=TransferQueue.active(c)!=null || DownloadState.running || MonitoringTaskState.running || CollectionState.running || CollectionConvertState.running
    fun ready(c: Context)=File(c.filesDir,"app-updates/update.apk")
    fun connection(address: String): HttpURLConnection {
        var next=address
        repeat(6) {
            require(AppUpdatePolicy.allowed(next)) { "Update redirect is not a trusted GitHub HTTPS address" }
            val con=URL(next).openConnection() as HttpURLConnection
            con.instanceFollowRedirects=false;con.connectTimeout=20000;con.readTimeout=30000
            con.setRequestProperty("User-Agent","yt-dlp-Mobile/${BuildConfig.VERSION_NAME}")
            con.setRequestProperty("Accept","application/vnd.github+json")
            val code=con.responseCode
            if(code in listOf(301,302,303,307,308)) { val location=con.getHeaderField("Location");con.disconnect();require(!location.isNullOrBlank());next=URL(URL(next),location).toString() }
            else { if(code!=200) { con.disconnect();error("GitHub returned HTTP $code. Try again later or open Releases.") };return con }
        }
        error("Too many update redirects")
    }
    fun check(c: Context,done: ((String)->Unit)?=null) {
        val app=c.applicationContext
        executor.execute {
            val message=runCatching {
                val con=connection(AppUpdatePolicy.API)
                val raw=try { con.inputStream.use { input -> val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(true) { val n=input.read(b);if(n<0) break;require(out.size()+n<=1_000_000) { "Release metadata too large" };out.write(b,0,n) };out.toString("UTF-8") } } finally { con.disconnect() }
                val r=AppUpdatePolicy.release(JSONObject(raw));prefs(app).edit().putString("release",r.toString()).putLong("checked",System.currentTimeMillis()).apply()
                if(AppUpdatePolicy.newer(r.getString("tag"),BuildConfig.VERSION_NAME)) "App update available: ${r.getString("tag")}" else "No newer stable app release. Installed ${BuildConfig.VERSION_NAME}; GitHub ${r.getString("tag")}"
            }.getOrElse { "Update check failed: ${it.message ?: it.javaClass.simpleName}" }
            status(app,message);main.post { done?.invoke(message) }
        }
    }
    fun startup(a: MainActivity) {
        if(startupDone) return;startupDone=true
        val app=a.applicationContext
        if(ApkBackups.enabled(app)) executor.execute { runCatching { ApkBackups.capture(app) }.onFailure { status(app,"APK backup needs attention: ${it.message}") } }
        if(!prefs(app).getBoolean("startup",true)) return
        fun prompt() {
            val r=release(app) ?: return
            if(a.isFinishing || a.isDestroyed || !a.hasWindowFocus() || busy(app) || !AppUpdatePolicy.newer(r.optString("tag"),BuildConfig.VERSION_NAME) || prefs(app).getString("dismissed","")==r.optString("tag")) return
            AlertDialog.Builder(a).setTitle("App update available").setMessage("${r.optString("tag")} is available on GitHub. Review and install from Settings when you are ready.")
                .setPositiveButton("Settings") { _,_ -> a.startActivity(Intent(a,AppSettingsActivity::class.java)) }
                .setNegativeButton("Later") { _,_ -> prefs(app).edit().putString("dismissed",r.optString("tag")).apply() }.show()
        }
        if(System.currentTimeMillis()-prefs(app).getLong("checked",0)>6*60*60*1000) check(app) { main.postDelayed({prompt()},800) } else main.postDelayed({prompt()},800)
    }
    fun download(c: Context) {
        val r=release(c) ?: error("Check for an update first")
        require(AppUpdatePolicy.newer(r.optString("tag"),BuildConfig.VERSION_NAME)) { "No newer app release selected" }
        status(c,"App APK queued for download")
        WorkManager.getInstance(c).enqueueUniqueWork("app-apk-update",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>().setInputData(workDataOf("release" to AppUpdatePolicy.downloadPayload(r))).build())
    }
    fun hash(f: File): String=f.inputStream().use { hash(it) }
    fun hash(input: java.io.InputStream): String { val md=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true) { val n=input.read(b);if(n<0) break;md.update(b,0,n) };return md.digest().joinToString("") { "%02x".format(it) } }
    @Suppress("DEPRECATION")
    fun validate(c: Context,f: File,older: Boolean=false): android.content.pm.PackageInfo {
        val flags=PackageManager.GET_SIGNING_CERTIFICATES
        val archive=c.packageManager.getPackageArchiveInfo(f.absolutePath,flags) ?: error("Downloaded file is not a valid APK")
        val installed=c.packageManager.getPackageInfo(c.packageName,flags)
        require(archive.packageName==c.packageName) { "APK belongs to another application" }
        fun signatures(p: android.content.pm.PackageInfo)=p.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList() }?.toSet()
        require(!signatures(archive).isNullOrEmpty() && signatures(archive)==signatures(installed)) { "APK signing key differs from this installation. Use a build signed with your existing key." }
        require(if(older) archive.longVersionCode<installed.longVersionCode else archive.longVersionCode>installed.longVersionCode) { if(older) "Backup is not an older app version" else "APK version code is not newer" }
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)<=Build.VERSION.SDK_INT) { "APK requires a newer Android version" }
        java.util.zip.ZipFile(f).use { z -> val abis=z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }.map { it.split('/').getOrNull(1) }.toSet();require(abis.isEmpty() || Build.SUPPORTED_ABIS.any { it in abis }) { "APK is not compatible with this device CPU" } }
        return archive
    }
    fun install(a: Activity,f: File,older: Boolean=false) {
        require(!a.isFinishing && !a.isDestroyed) { "Settings screen is no longer open" }
        require(!busy(a)) { "Finish active downloads, recordings and saving before installing an app version" }
        validate(a,f,older)
        if(!a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${a.packageName}")));return
        }
        val uri=FileProvider.getUriForFile(a,"${a.packageName}.updates",f)
        a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData=ClipData.newRawUri("App APK",uri) })
    }
}

class AppUpdateDownloadWorker(c: Context,p: WorkerParameters): Worker(c,p) {
    @Volatile private var activeConnection: HttpURLConnection?=null
    override fun onStopped() { activeConnection?.disconnect();super.onStopped() }
    override fun doWork(): Result {
        val c=applicationContext;val target=AppUpdates.ready(c);val part=File(target.parentFile,"update-$id.pending.apk")
        try {
            val r=JSONObject(inputData.getString("release") ?: error("Missing release"));target.parentFile!!.mkdirs()
            val size=r.getLong("size");require(target.parentFile!!.usableSpace>size+32*1024*1024) { "Not enough storage for APK download" }
            val nm=c.getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("app-updates","App update downloads",NotificationManager.IMPORTANCE_LOW))
            val cancel=WorkManager.getInstance(c).createCancelPendingIntent(id)
            val open=PendingIntent.getActivity(c,6501,Intent(c,AppSettingsActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(c,"app-updates").setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(open).setContentTitle("Downloading app update").setContentText("Media tasks continue separately. Open Settings when ready.").setOngoing(true).addAction(0,"Cancel",cancel).build()
            setForegroundAsync(ForegroundInfo(6501,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)).get()
            AppUpdates.status(c,"Downloading app APK…")
            val con=AppUpdates.connection(r.getString("url"));activeConnection=con
            check(!isStopped) { "Download cancelled" }
            try { con.inputStream.use { input -> part.outputStream().use { out -> val b=ByteArray(65536);var count=0L;while(true) { check(!isStopped) { "Download cancelled" };val n=input.read(b);if(n<0) break;count+=n;require(count<=size) { "APK size exceeds release metadata" };out.write(b,0,n) };require(count==size) { "APK download is incomplete" } } } } finally { con.disconnect() }
            val digest=r.optString("digest");if(digest.startsWith("sha256:")) require(AppUpdates.hash(part).equals(digest.substringAfter(':'),true)) { "APK digest does not match GitHub" }
            val info=AppUpdates.validate(c,part)
            check(!isStopped) { "Download cancelled" }
            if(target.exists()) target.delete();require(part.renameTo(target)) { "Could not save verified APK" }
            AppUpdates.status(c,"Verified app APK ready: ${info.versionName}. Tap Install downloaded update.")
            return Result.success()
        } catch(e: Exception) { AppUpdates.status(c,if(isStopped) "App APK download cancelled" else "App APK download failed: ${e.message ?: e.javaClass.simpleName}");return Result.failure() }
        finally { activeConnection?.disconnect();activeConnection=null;part.delete() }
    }
}
