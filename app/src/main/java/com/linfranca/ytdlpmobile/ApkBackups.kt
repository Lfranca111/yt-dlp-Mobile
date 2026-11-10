package com.linfranca.ytdlpmobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import org.json.JSONObject
import java.io.File

/** User-selected SAF documents survive removal of this app. Never writes credentials. */
internal object ApkBackups {
    private fun prefs(c: Context)=c.getSharedPreferences("apk_backups",Context.MODE_PRIVATE)
    fun enabled(c: Context)=prefs(c).getBoolean("enabled",false)
    fun folder(c: Context)=prefs(c).getString("folder",null)?.let(Uri::parse)
    fun setEnabled(c: Context,on: Boolean) { prefs(c).edit().putBoolean("enabled",on).apply() }
    fun choose(c: Context,uri: Uri) {
        val id=Docs.getTreeDocumentId(uri)
        require(!id.contains("Android/data",true) && !id.contains("Android/obb",true)) { "Choose a public Documents or Downloads folder outside Android/data" }
        val old=folder(c)
        require(old==null || old==uri || current(c)==null) { "Delete tracked backups before changing their folder" }
        prefs(c).edit().putString("folder",uri.toString()).putBoolean("enabled",true).commit()
    }
    private fun metadata(c: Context,key: String)=runCatching { JSONObject(prefs(c).getString(key,"")!!) }.getOrNull()
    fun current(c: Context)=metadata(c,"current")
    fun previous(c: Context): JSONObject? {
        val installed=installedCode(c)
        return listOfNotNull(metadata(c,"previous"),current(c)).filter { it.optLong("code")<installed }.maxByOrNull { it.optLong("code") }
    }
    @Suppress("DEPRECATION") private fun installedCode(c: Context)=c.packageManager.getPackageInfo(c.packageName,0).longVersionCode
    private fun root(uri: Uri)=Docs.buildDocumentUriUsingTree(uri,Docs.getTreeDocumentId(uri))
    private fun delete(c: Context,j: JSONObject?) { j ?: return;runCatching { Docs.deleteDocument(c.contentResolver,Uri.parse(j.getString("uri"))) } }
    @Synchronized fun capture(c: Context) {
        if(!enabled(c)) return
        require(c.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "This backup feature requires the universal APK, not a split installation" }
        val tree=folder(c) ?: error("Choose a backup folder first")
        val source=File(c.applicationInfo.sourceDir);val code=installedCode(c)
        val old=current(c)
        if(old?.optLong("code")==code) {
            c.contentResolver.openInputStream(Uri.parse(old.getString("uri"))).use { require(it!=null && AppUpdates.hash(it)==old.getString("sha256")) { "Stored APK backup is missing or damaged; delete backups and capture again" } };return
        }
        val name="yt-dlp-Mobile-${BuildConfig.VERSION_NAME}-$code-${System.currentTimeMillis()}.apk"
        val doc=Docs.createDocument(c.contentResolver,root(tree),"application/vnd.android.package-archive",name) ?: error("Backup folder is not writable")
        var committed=false
        try {
            c.contentResolver.openOutputStream(doc,"w").use { out -> require(out!=null);source.inputStream().use { it.copyTo(out) } }
            val hash=AppUpdates.hash(source)
            c.contentResolver.openInputStream(doc).use { require(it!=null && AppUpdates.hash(it)==hash) { "APK backup verification failed" } }
            val item=JSONObject().put("uri",doc.toString()).put("name",name).put("code",code).put("version",BuildConfig.VERSION_NAME).put("size",source.length()).put("sha256",hash)
            val older=metadata(c,"previous")
            require(prefs(c).edit().putString("current",item.toString()).putString("previous",old?.toString()).commit()) { "Could not commit APK backup record" };committed=true
            if(older?.optString("uri")!=old?.optString("uri")) delete(c,older)
            runCatching {
                val note=Docs.createDocument(c.contentResolver,root(tree),"text/plain","APK-recovery-${BuildConfig.VERSION_NAME}.txt")!!
                c.contentResolver.openOutputStream(note).use { it!!.write("APK recovery: open the saved older .apk in Android Files to install it. Android may reject a downgrade. Only then, if you accept losing app-private data, uninstall the current app in Android Settings and open the saved older APK. APK files do not include cookies, monitoring rules, settings or media. Export settings before uninstalling if the app opens. A newer settings export may not be supported by an older version. Never delete your only backup before recovery is complete.".toByteArray()) }
            }
        } finally { if(!committed) runCatching { Docs.deleteDocument(c.contentResolver,doc) } }
    }
    fun description(c: Context): String {
        val cur=current(c);val prev=previous(c)
        return "Backup: ${if(enabled(c)) "enabled" else "off"}\nCurrent snapshot: ${cur?.optString("name") ?: "none"}\nOlder APK: ${prev?.optString("name") ?: "none yet"}\nFolder: ${folder(c) ?: "not selected"}"
    }
    @Synchronized fun recovery(c: Context): File {
        val j=previous(c) ?: error("No older APK saved yet")
        val f=File(c.filesDir,"app-updates/recovery.apk");f.parentFile!!.mkdirs()
        require(f.parentFile!!.usableSpace>j.getLong("size")+16*1024*1024) { "Not enough storage to prepare recovery" }
        try {
            c.contentResolver.openInputStream(Uri.parse(j.getString("uri"))).use { input -> require(input!=null);f.outputStream().use { input.copyTo(it) } }
            require(AppUpdates.hash(f)==j.getString("sha256")) { "Saved APK is damaged" };AppUpdates.validate(c,f,true);return f
        } catch(e: Exception) { f.delete();throw e }
    }
    @Synchronized fun deleteAll(c: Context) {
        setEnabled(c,false)
        val failed=mutableListOf<String>()
        for(key in listOf("current","previous")) {
            val item=metadata(c,key) ?: continue
            runCatching { require(Docs.deleteDocument(c.contentResolver,Uri.parse(item.getString("uri")))) }
                .onSuccess { prefs(c).edit().remove(key).commit() }
                .onFailure { failed.add(key) }
        }
        require(failed.isEmpty()) { "Some APKs could not be deleted. Their records were kept so you can retry or delete them in Android Files." }
        prefs(c).edit().clear().commit()
    }
}
