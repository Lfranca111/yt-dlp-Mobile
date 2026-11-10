package com.linfranca.ytdlpmobile

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object CollectionJournal {
    private fun file(c: Context)=AtomicFile(File(c.filesDir,"collections/history.json").apply { parentFile!!.mkdirs() })
    @Synchronized fun jobs(c: Context): MutableList<JSONObject> = runCatching {
        val a=JSONArray(file(c).openRead().bufferedReader().use { it.readText() });(0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    }.getOrDefault(mutableListOf())
    @Synchronized private fun update(c: Context,edit: (MutableList<JSONObject>)->Unit) {
        val list=jobs(c);edit(list);val f=file(c);val out=f.startWrite()
        try { out.write(JSONArray(list).toString().toByteArray());f.finishWrite(out) } catch(e: Exception) { f.failWrite(out);throw e }
    }
    fun item(i: CollectionItem)=JSONObject().put("id",i.id).put("url",i.url).put("extension",i.extension).put("order",i.order).put("folder",i.folder).put("fallback",i.fallbackUrl).put("referer",i.referer)
    fun decode(o: JSONObject)=CollectionItem(o.getString("id"),o.getString("url"),o.getString("extension"),o.optInt("order"),o.optString("folder"),o.optString("fallback"),o.optString("referer"))
    fun key(i: CollectionItem)=i.folder+"\u001f"+i.id
    fun begin(c: Context,request: Intent,title: String): String {
        val id=UUID.randomUUID().toString();update(c) { it.add(JSONObject().put("id",id).put("title",title).put("state","running").put("created",System.currentTimeMillis()).put("request",request.toUri(Intent.URI_INTENT_SCHEME)).put("items",JSONArray()).put("log","")) };return id
    }
    fun discovered(c: Context,id: String,items: List<CollectionItem>)=update(c) { list ->
        list.find { it.optString("id")==id }?.let { j -> val a=j.getJSONArray("items");val known=(0 until a.length()).map { a.getJSONObject(it).optString("key") }.toSet()
            items.filter { key(it) !in known }.forEach { a.put(item(it).put("key",key(it)).put("state","pending")) } }
    }
    fun result(c: Context,id: String,item: CollectionItem,state: String,detail: String="",uri: String="")=update(c) { list ->
        val a=list.find { it.optString("id")==id }?.optJSONArray("items") ?: return@update
        (0 until a.length()).map { a.getJSONObject(it) }.find { it.optString("key")==key(item) }?.put("state",state)?.put("detail",detail)?.put("saved_uri",uri)
    }
    fun finished(c: Context,id: String,status: String,log: String,notify: Boolean=true)=update(c) { list ->list.find { it.optString("id")==id }?.let { j ->
        j.put("state","finished").put("detail",status).put("log",MonitoringStore.safe(log)).put("finished",System.currentTimeMillis())
        if(notify) TransferNotifications.show(c,j,status,false)
    } }
    fun render(c: Context,body: LinearLayout,snapshot: List<JSONObject> = jobs(c)) {
        snapshot.asReversed().take(50).forEach { j ->
            val a=j.getJSONArray("items");val pending=(0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("state") !in setOf("saved","skipped") }
            body.addView(TextView(c).apply { text="${j.optString("title")}\n${j.optString("detail",j.optString("state"))}\n${pending.size} pending / failed";textSize=17f;setPadding(0,18,0,8) })
            body.addView(Button(c).apply { text="Collection log";setOnClickListener { AlertDialog.Builder(c).setMessage(j.optString("log")).setPositiveButton("Close",null).show() } })
            if(a.length()==0 && j.optString("state")!="running") body.addView(Button(c).apply { text="Retry collection discovery";setOnClickListener {
                if(CollectionState.running) { Toast.makeText(c,"Wait for the current collection",Toast.LENGTH_LONG).show() } else {
                    val request=Intent.parseUri(j.getString("request"),Intent.URI_INTENT_SCHEME)
                    val comic=request.getStringExtra("comic_rule").orEmpty()
                    if(comic.isNotBlank()) ComicStore.rules(c).find { it.optString("id")==comic }?.let { ComicStore.schedule(c,it,true) }
                    else runCatching { request.removeExtra("collection_retry_items");ContextCompat.startForegroundService(c,request) }
                }
            } })
            if(pending.isNotEmpty()) body.addView(Button(c).apply { text="Retry selected failed / pending files";setOnClickListener {
                val selected=BooleanArray(pending.size) { true }
                AlertDialog.Builder(c).setTitle("Retry files").setMultiChoiceItems(pending.map { it.optString("id") }.toTypedArray(),selected) { _,n,on->selected[n]=on }
                    .setPositiveButton("Retry") { _,_->
                        if(!selected.any { it }) { Toast.makeText(c,"Select at least one file",Toast.LENGTH_LONG).show();return@setPositiveButton }
                        if(CollectionState.running) { Toast.makeText(c,"Wait for the current collection task",Toast.LENGTH_LONG).show();return@setPositiveButton }
                        val request=Intent.parseUri(j.getString("request"),Intent.URI_INTENT_SCHEME)
                        val comic=request.getStringExtra("comic_rule").orEmpty()
                        if(comic.isNotBlank()) {
                            val rule=ComicStore.rules(c).find { it.optString("id")==comic }
                            if(rule!=null) {
                                rule.put("download_pending",true).put("retry_keys",JSONArray(pending.filterIndexed { n,_->selected[n] }.map { it.getString("key") }))
                                ComicStore.put(c,rule);ComicStore.schedule(c,rule,true)
                            } else Toast.makeText(c,"The comic monitoring rule was removed",Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        request.putExtra("collection_retry_items",JSONArray(pending.filterIndexed { n,_->selected[n] }).toString())
                        runCatching { ContextCompat.startForegroundService(c,request) }.onFailure { Toast.makeText(c,"Open the app and retry",Toast.LENGTH_LONG).show() }
                    }.setNegativeButton("Cancel",null).show()
            } })
        }
    }
}
