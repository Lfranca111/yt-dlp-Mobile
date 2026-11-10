package com.linfranca.ytdlpmobile

import android.content.Intent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.UUID

internal class ComicMonitoringPanel(private val a: AppCompatActivity) {
    val root=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
    private val renderGate=MonitoringRenderGate()
    private var folderResult: ((String)->Unit)?=null
    private val folder=a.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) try {
            a.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            folderResult?.invoke(uri.toString())
        } catch(_: SecurityException) { toast("Folder permission could not be retained") }
        folderResult=null
    }
    init { ComicStore.initialize(a);render() }
    private fun text(s: String,size: Float=16f) { root.addView(TextView(a).apply { text=s;textSize=size;setPadding(0,16,0,8) }) }
    private fun button(label: String,action: ()->Unit) { root.addView(Button(a).apply { text=label;setOnClickListener { action();render() } }) }
    private fun toast(s: String) { Toast.makeText(a,s,Toast.LENGTH_LONG).show() }
    fun render() {
        val rules=ComicStore.rules(a)
        if(!renderGate.changed(org.json.JSONArray(rules).toString())) return
        root.removeAllViews();text("Comic updates",25f)
        text("Monitor a specific supported comic or album. Append new pages without overwriting files or changing your manual order. Failed checks keep the baseline; replacements, inserted pages or reordered pages need review. PDFs are not rebuilt automatically. Schedules are approximate.")
        button("Add comic update rule") { edit(null) }
        rules.forEach { r ->
            val id=r.getString("id")
            text("${r.optString("name")}\n${r.optString("url")}\n${if(r.optBoolean("enabled",true)) "Enabled" else "Paused"} • every ${r.optInt("interval",60)} min • ${if(r.optBoolean("automatic")) "Download updates" else "Notify only"}\n${r.optString("status","Waiting for first check")}\n${r.optJSONArray("pending")?.length() ?: 0} pending pages\nDestination: ${if(r.optString("destination").isBlank()) "Downloads/yt-dlp Mobile/Collections/${CollectionTransferIO.safe(r.optString("name"))}" else "Selected folder"}",18f)
            button("Check now") { ComicStore.schedule(a,r,true);toast("Check scheduled") }
            button("Download pending pages / retry") {
                r.put("download_pending",true);ComicStore.put(a,r);ComicStore.schedule(a,r,true);toast("Pending pages will be refreshed and downloaded")
            }
            button(if(r.optBoolean("enabled",true)) "Pause comic checks" else "Resume comic checks") {
                r.put("enabled",!r.optBoolean("enabled",true)).put("generation",System.currentTimeMillis());ComicStore.put(a,r)
                androidx.work.WorkManager.getInstance(a).cancelUniqueWork("comic-$id");ComicStore.schedule(a,r)
            }
            button("Edit comic rule") { edit(r) }
            button("Remove rule (keep saved files)") { AlertDialog.Builder(a).setMessage("Remove this monitoring rule? Saved files will remain.").setPositiveButton("Remove") { _,_->ComicStore.remove(a,id);render() }.setNegativeButton("Cancel",null).show() }
        }
        VisualPolish.prepareMonitoring(root)
    }
    private fun edit(old: JSONObject?) {
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16) }
        fun input(label: String,value: String="")=EditText(a).apply { hint=label;setText(value);box.addView(this) }
        val name=input("Comic name",old?.optString("name").orEmpty())
        val link=input("Specific comic / album URL",old?.optString("url").orEmpty())
        val interval=input("Check interval: 15 / 30 / 60 / 180 / 360 / 720 / 1440 minutes",old?.optInt("interval",60)?.toString() ?: "60")
        val automatic=CheckBox(a).apply { text="Automatically download new pages";isChecked=old?.optBoolean("automatic") ?: false;box.addView(this) }
        val initial=CheckBox(a).apply { text="Download existing pages on first successful check (off = future pages only)";isChecked=old?.optBoolean("initial_download") ?: false;box.addView(this) }
        val days=input("Days: Monday=1 … Sunday=7, comma-separated",old?.optString("days","1,2,3,4,5,6,7") ?: "1,2,3,4,5,6,7")
        val start=input("Optional window start HH:mm",old?.optString("start_text").orEmpty())
        val end=input("Optional window end HH:mm",old?.optString("end_text").orEmpty())
        val zone=input("Time zone",old?.optString("zone",java.time.ZoneId.systemDefault().id) ?: java.time.ZoneId.systemDefault().id)
        var destination=old?.optString("destination").orEmpty()
        val target=TextView(a).apply { text=if(destination.isBlank()) "Default Downloads folder" else "Selected folder";box.addView(this) }
        box.addView(Button(a).apply { text="Choose existing destination folder";setOnClickListener { folderResult={ destination=it;target.text="Selected folder" };folder.launch(null) } })
        box.addView(Button(a).apply { text="Use default Downloads folder";setOnClickListener { destination="";target.text="Default Downloads folder" } })
        val credentials=old?.optJSONObject("credentials") ?: JSONObject()
        val fields=listOf(CollectionDownloadService.EXTRA_USER to "E621 / E6AI user",CollectionDownloadService.EXTRA_KEY to "E621 / E6AI key",CollectionDownloadService.EXTRA_FURBOORU_KEY to "Furbooru API key",CollectionDownloadService.EXTRA_RULE34_USER to "Rule34 user ID",CollectionDownloadService.EXTRA_RULE34_KEY to "Rule34 API key").associate { (key,label)->key to input(label,credentials.optString(key)).apply { if(label.contains("key")) inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD } }
        val dialog=AlertDialog.Builder(a).setTitle("Comic monitoring rule").setView(ScrollView(a).apply { addView(box) }).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                require(name.text.isNotBlank()) { "Enter a comic name" }
                val url=link.text.toString().trim();val match=CollectionLinkRecognizer.recognize(url) ?: error("Unsupported comic link")
                require(match.pageType in setOf("Comic","Album","Gallery","Manga","Image gallery","Artist post","Picture or album page","Individual post","Individual image","Chapter","Comic / chapter","Gallery / comic","Comic or folder")) { "Use a specific comic, chapter or album, rather than a listing or tag page (${match.pageType})" }
                CollectionRoute.from(url)
                val minutes=interval.text.toString().toInt();require(minutes in setOf(15,30,60,180,360,720,1440)) { "Choose a supported interval (at least 15 minutes)" }
                val d=days.text.toString().split(',').map { it.trim().toInt() }.toSet();require(d.isNotEmpty() && d.all { it in 1..7 })
                java.time.ZoneId.of(zone.text.toString())
                fun minute(s: String): Int { if(s.isBlank()) return -1;val t=java.time.LocalTime.parse(s);return t.hour*60+t.minute }
                val from=minute(start.text.toString());val to=minute(end.text.toString());require((from<0)==(to<0) && (from<0 || from!=to)) { "Enter both window times; they must differ" }
                require(old==null || old.optString("url")==url) { "Create a separate rule for a different comic URL" }
                val r=if(old==null) JSONObject().put("id",UUID.randomUUID().toString()) else JSONObject(old.toString())
                r.put("name",name.text.toString()).put("url",url).put("interval",minutes).put("automatic",automatic.isChecked).put("initial_download",initial.isChecked)
                    .put("days",d.joinToString(",")).put("zone",zone.text.toString()).put("start",from).put("end",to).put("start_text",start.text.toString()).put("end_text",end.text.toString())
                    .put("destination",destination).put("generation",System.currentTimeMillis()).put("enabled",true)
                fields.forEach { (key,v)->credentials.put(key,v.text.toString().trim()) };r.put("credentials",credentials)
                ComicStore.put(a,r);ComicStore.schedule(a,r,true);dialog.dismiss();render()
            } catch(e: Exception) { toast(e.message ?: "Check rule settings") }
        } };dialog.show()
    }
}
