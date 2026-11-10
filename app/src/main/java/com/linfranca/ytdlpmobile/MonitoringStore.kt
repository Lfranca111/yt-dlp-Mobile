package com.linfranca.ytdlpmobile

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class MonitoringRule(
    val id: String = UUID.randomUUID().toString(),
    var name: String, var url: String, var mode: String = "uploads", var automatic: Boolean = false,
    var interval: Int = 15, var enabled: Boolean = true,
    var zone: String = java.time.ZoneId.systemDefault().id,
    var start: Int = -1, var end: Int = -1, var days: Set<Int> = (1..7).toSet(),
    var generation: Long = System.currentTimeMillis(), var next: Long = 0, var last: Long = 0,
    var status: String = "Waiting for first check", var failures: Int = 0,
    var baseline: Boolean = false, var seen: MutableSet<String> = linkedSetOf(),
    var liveSession: String = "", var offlineChecks: Int = 0, var notified: String = "",
    var customSource: String = "", var customState: JSONObject = JSONObject()
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("url", url).put("mode", mode)
        .put("automatic", automatic).put("interval", interval).put("enabled", enabled).put("zone", zone)
        .put("start", start).put("end", end).put("days", JSONArray(days.toList())).put("generation", generation)
        .put("next", next).put("last", last).put("status", status).put("failures", failures)
        .put("baseline", baseline).put("seen", JSONArray(seen.toList())).put("liveSession", liveSession)
        .put("offlineChecks", offlineChecks).put("notified", notified).put("customSource",customSource).put("customState",customState)
    companion object {
        fun from(j: JSONObject) = MonitoringRule(j.getString("id"), j.getString("name"), j.getString("url"),
            j.optString("mode", "uploads"), j.optBoolean("automatic"), j.optInt("interval",15),
            j.optBoolean("enabled",true), j.optString("zone",java.time.ZoneId.systemDefault().id),
            j.optInt("start",-1), j.optInt("end",-1), ints(j.optJSONArray("days")),
            j.optLong("generation"), j.optLong("next"), j.optLong("last"), j.optString("status"),
            j.optInt("failures"), j.optBoolean("baseline"), strings(j.optJSONArray("seen")).toMutableSet(),
            j.optString("liveSession"), j.optInt("offlineChecks"), j.optString("notified"), j.optString("customSource"),j.optJSONObject("customState") ?: JSONObject())
        private fun ints(a: JSONArray?) = if(a == null) (1..7).toSet() else (0 until a.length()).map { a.getInt(it) }.toSet()
        private fun strings(a: JSONArray?) = if(a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    }
}

object MonitoringStore {
    const val ACTION_STATE = "com.linfranca.ytdlpmobile.MONITORING_RULE_STATE"
    private fun file(c: Context) = AtomicFile(File(c.filesDir,"monitoring/state.json").also { it.parentFile?.mkdirs() })
    private fun load(c: Context): JSONObject = try { JSONObject(file(c).openRead().bufferedReader().use { it.readText() }) }
        catch(e: java.io.FileNotFoundException) { JSONObject() }
    private fun save(c: Context,j: JSONObject) {
        val a=file(c); val out=a.startWrite()
        try { out.write(j.toString().toByteArray()); a.finishWrite(out) }
        catch(e: Exception) { a.failWrite(out); throw e }
        c.sendBroadcast(android.content.Intent(ACTION_STATE).setPackage(c.packageName))
    }
    @Synchronized fun rules(c: Context): List<MonitoringRule> {
        val a=load(c).optJSONArray("rules") ?: return emptyList()
        return (0 until a.length()).map { MonitoringRule.from(a.getJSONObject(it)) }
    }
    @Synchronized fun get(c: Context,id: String) = rules(c).find { it.id==id }
    @Synchronized fun put(c: Context,r: MonitoringRule) {
        val j=load(c); val current=rules(c)
        // Existing legacy rules keep their route and baseline. If a new rule
        // already opted in, retain that choice when its settings are saved.
        val previous=current.firstOrNull { it.id==r.id }
        val binding=CaptureMonitoringPolicy.bindingOnSave(previous==null,
            previous?.customState?.optString(CaptureMonitoringPolicy.RULE_BINDING),previous?.url,previous?.customSource,r.url,r.customSource)
        if(binding!=null) r.customState.put(CaptureMonitoringPolicy.RULE_BINDING,binding)
        val all=current.filter { it.id!=r.id }+r
        j.put("rules",JSONArray(all.map { it.json() })); save(c,j)
    }
    @Synchronized fun commitCheck(c: Context,r: MonitoringRule): Boolean {
        if(get(c,r.id)?.generation!=r.generation) return false
        put(c,r);return true
    }
    @Synchronized fun mutate(c: Context,id: String, action: (MonitoringRule)->Unit): MonitoringRule? {
        val r=get(c,id) ?: return null; action(r); put(c,r); return r
    }
    @Synchronized fun remove(c: Context,id: String,preserveJobs: Set<String> = emptySet()) {
        val j=load(c); j.put("rules",JSONArray(rules(c).filter { it.id!=id }.map { it.json() }))
        // Active captures survive removing a rule; only waiting work is removed.
        val jobs=j.optJSONArray("jobs") ?: JSONArray()
        j.put("jobs",JSONArray((0 until jobs.length()).map { jobs.getJSONObject(it) }
            .filter { it.optString("rule")!=id || (it.optString("state")=="running" || it.optString("id") in preserveJobs) }))
        save(c,j)
    }
    @Synchronized fun jobs(c: Context): List<JSONObject> {
        val a=load(c).optJSONArray("jobs") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    @Synchronized fun enqueue(c: Context,r: MonitoringRule,item: MonitoringItem,live: Boolean): String {
        val j=load(c); val list=jobs(c).toMutableList(); val key=r.id+":"+item.id+":"+live
        list.find { it.optString("key")==key }?.let { return it.getString("id") }
        val job=JSONObject().put("id",UUID.randomUUID().toString()).put("key",key).put("rule",r.id)
            .put("name",r.name).put("title",item.title).put("url",item.url).put("item",item.id).put("live",live)
            .put("state",if(r.automatic) "pending" else "notify").put("created",System.currentTimeMillis())
            .put("detail",if(r.automatic) "Waiting for automatic attempt" else "Notification only; tap Start")
        list.add(job); j.put("jobs",JSONArray(list));save(c,j)
        log(c,"discovery persisted; kind=${if(live) "live" else "upload"}; rule=${r.id}; job=${job.getString("id")}")
        return job.getString("id")
    }
    @Synchronized fun updateJob(c: Context,id: String, state: String, detail: String) {
        val j=load(c);val list=jobs(c)
        list.find { it.optString("id")==id }?.put("state",state)?.put("detail",safe(detail))
        j.put("jobs",JSONArray(list));save(c,j)
    }
    @Synchronized fun recover(c: Context) {
        if(MonitoringTaskState.running || MonitoringCoordinator.hasActive()) return
        jobs(c).filter { it.optString("state")=="running" }.forEach {
            updateJob(c,it.getString("id"),"interrupted","Interrupted by app/process restart; tap Retry")
            log(c,"interrupted task identified; job=${it.getString("id")}")
        }
    }
    fun safe(s: String): String = s.lineSequence().mapNotNull { RecorderErrorDiagnostic.safeLine(it) }.joinToString("\n")
    fun error(e: Throwable): String {
        val chain=generateSequence(e) { it.cause }.take(4).map { it.javaClass.simpleName+": "+it.message.orEmpty() }.joinToString("; ")
        return safe(chain).ifBlank { e.javaClass.simpleName+": see detailed diagnostics" }
    }
    private fun journals(c: Context) = ((9 downTo 1).map { File(c.filesDir,"monitoring/diagnostic.$it.log") }+File(c.filesDir,"monitoring/diagnostic.log")).filter { it.exists() }
    @Synchronized fun recentLog(c: Context): String {
        val f=journals(c).lastOrNull() ?: return "No monitoring events yet."
        java.io.RandomAccessFile(f,"r").use { r ->
            r.seek((r.length()-60_000).coerceAtLeast(0));val bytes=ByteArray((r.length()-r.filePointer).toInt());r.readFully(bytes)
            return "Recent monitoring events (use Save full log for retained history)\n"+bytes.toString(Charsets.UTF_8).substringAfter('\n',"")
        }
    }
    fun copyLogTo(c: Context,out: java.io.OutputStream) {
        val snapshots=synchronized(this) {
            val opened=mutableListOf<Pair<java.io.FileInputStream,Long>>()
            try { journals(c).forEach { f -> opened.add(f.inputStream() to f.length()) };opened }
            catch(e: Exception) { opened.forEach { it.first.close() };throw e }
        }
        try {
            val buffer=ByteArray(16384)
            snapshots.forEach { (input,length) ->
                var remaining=length
                while(remaining>0) { val n=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());if(n<0) break;out.write(buffer,0,n);remaining-=n }
                out.write(10)
            }
        } finally { snapshots.forEach { it.first.close() } }
    }
    @Synchronized fun log(c: Context,message: String) {
        val f=File(c.filesDir,"monitoring/diagnostic.log");f.parentFile?.mkdirs()
        // Rotate without truncating an individual event; retain ten complete journals.
        if(f.exists() && f.length()>2_000_000) {
            File(f.parentFile,"diagnostic.9.log").delete()
            for(i in 8 downTo 1) File(f.parentFile,"diagnostic.$i.log").renameTo(File(f.parentFile,"diagnostic.${i+1}.log"))
            f.renameTo(File(f.parentFile,"diagnostic.1.log"))
        }
        val correlated=Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").replace(message) { "ref-"+it.value.take(8) }
        val clean=correlated.lineSequence().mapNotNull { RecorderErrorDiagnostic.safeLine(it,2000) }.joinToString("\n")
        f.appendText("${Instant.now()} $clean\n")
    }
    @Synchronized fun logText(c: Context): String {
        val folder=File(c.filesDir,"monitoring")
        return ((9 downTo 1).map { File(folder,"diagnostic.$it.log") }+File(folder,"diagnostic.log"))
            .filter { it.exists() }.joinToString("\n") { it.readText() }
    }
}
