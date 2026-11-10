package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal object AppRecoverySettings {
    private val ruleFields=listOf("name","url","mode","automatic","interval","zone","start","end","days","customSource")
    private fun safeUrl(s: String) {
        val u=URI(s);require(u.scheme in setOf("http","https") && !u.host.isNullOrBlank() && u.userInfo==null && u.fragment==null) { "Recovery supports ordinary website URLs only" }
        require(!Regex("(?i)(token|cookie|password|auth|api.?key|signature)=").containsMatchIn(u.rawQuery.orEmpty())) { "Remove credential or signed parameters from source URLs before exporting" }
    }
    fun export(c: Context): String {
        val sources=CustomSources.all(c).map { CustomSources.validate(it);safeUrl(it.getString("url"));if(it.optString("urlTemplate").isNotBlank()) safeUrl(it.getString("urlTemplate").replace("{id}","item"));it.optJSONArray("examples")?.let { a -> for(i in 0 until a.length()) safeUrl(a.getString(i)) };JSONObject(it.toString()) }
        val rules=MonitoringStore.rules(c).map { r -> safeUrl(r.url);val raw=r.json();JSONObject().apply { ruleFields.forEach { put(it,raw.get(it)) } } }
        return JSONObject().put("format","yt-dlp-Mobile-recovery").put("schema",1).put("app",BuildConfig.VERSION_NAME)
            .put("theme",DisplaySettings.theme(c)).put("orientation",DisplaySettings.orientation(c))
            .put("sources",JSONArray(sources)).put("rules",JSONArray(rules)).toString(2)
    }
    data class Plan(val theme: String,val orientation: String,val sources: List<JSONObject>,val rules: List<MonitoringRule>)
    fun plan(raw: String): Plan {
        require(raw.toByteArray().size<=1_048_576) { "Settings file exceeds 1 MB" }
        val j=JSONObject(raw);require(j.getString("format")=="yt-dlp-Mobile-recovery" && j.getInt("schema")==1) { "Unsupported recovery file" }
        val theme=j.getString("theme");val orientation=j.getString("orientation")
        require(theme in setOf("dark","light","device") && orientation in setOf("android","portrait","landscape")) { "Invalid display settings" }
        val sourceArray=j.getJSONArray("sources");val ruleArray=j.getJSONArray("rules");require(sourceArray.length()<=100 && ruleArray.length()<=500) { "Too many settings entries" }
        val ids=mutableMapOf<String,String>()
        val sources=(0 until sourceArray.length()).map { val old=sourceArray.getJSONObject(it);safeUrl(old.getString("url"));if(old.optString("urlTemplate").isNotBlank()) safeUrl(old.getString("urlTemplate").replace("{id}","item"));old.optJSONArray("examples")?.let { a -> for(k in 0 until a.length()) safeUrl(a.getString(k)) };require(!ids.containsKey(old.getString("id"))) { "Duplicate source IDs" };val fresh=CustomSources.imported(old.toString());ids[old.getString("id")]=fresh.getString("id");fresh }
        val rules=(0 until ruleArray.length()).map {
            val r=ruleArray.getJSONObject(it);require(r.keys().asSequence().all { key -> key in ruleFields }) { "Unexpected rule settings" }
            val url=r.getString("url");safeUrl(url);val mode=r.getString("mode");require(mode in setOf("uploads","live","both"))
            val name=r.getString("name");require(name.length in 1..160)
            val zone=r.getString("zone");java.time.ZoneId.of(zone)
            val interval=r.getInt("interval");require(interval in 1..10080)
            val start=r.optInt("start",-1);val end=r.optInt("end",-1);require(start in -1..1439 && end in -1..1439)
            val days=r.getJSONArray("days");val daySet=(0 until days.length()).map { days.getInt(it).also { d -> require(d in 1..7) } }.toSet();require(daySet.isNotEmpty())
            val source=r.optString("customSource");require(source.isBlank() || ids.containsKey(source)) { "Missing custom source template" }
            MonitoringRule(id=UUID.randomUUID().toString(),name=name,url=url,mode=mode,automatic=r.optBoolean("automatic"),interval=interval,enabled=false,zone=zone,start=start,end=end,days=daySet,customSource=ids[source].orEmpty(),status="Imported disabled; review settings and establish a fresh baseline")
        }
        return Plan(theme,orientation,sources,rules)
    }
    fun apply(c: Context,p: Plan) {
        // Parsing and validation finish before any persistent change. Copies have fresh IDs and cannot schedule captures.
        p.sources.forEach { CustomSources.put(c,it) };p.rules.forEach { MonitoringStore.put(c,it) }
        android.os.Handler(android.os.Looper.getMainLooper()).post { DisplaySettings.setTheme(c,p.theme);DisplaySettings.setOrientation(c,p.orientation) }
    }
}
