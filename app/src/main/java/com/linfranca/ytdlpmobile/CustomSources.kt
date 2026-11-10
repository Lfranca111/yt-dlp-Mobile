package com.linfranca.ytdlpmobile

import android.content.Context
import android.util.AtomicFile
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/** Declarative, versioned recipes. Imports can never execute JavaScript or supply authentication. */
class BrowserCheckRequired(message: String): IllegalStateException(message)

internal object CustomSources {
    const val LIMIT=262144
    private val fields=setOf("schema","id","name","url","linkSelector","pathPrefix","liveSelector","liveText","offlineText","mode","browserOnly","testedAt","pathPattern","identityKeys","newestFirst","offlineSelector","nextSelector","discovery","idAttribute","urlTemplate","examples","videoOnly","structured","jsonRecordKind","browserMode","liveLocation","liveLinkSelector","liveAllowUnconfirmedAudio","uploadValidated","liveValidated")
    private fun file(c: Context)=AtomicFile(File(c.filesDir,"monitoring/custom-sources.json").apply { parentFile!!.mkdirs() })
    @Synchronized fun all(c: Context): List<JSONObject> = try {
        val a=JSONArray(file(c).openRead().bufferedReader().use { it.readText() });(0 until a.length()).map { a.getJSONObject(it) }
    } catch(_: java.io.FileNotFoundException) { emptyList() }
    @Synchronized fun put(c: Context,j: JSONObject) {
        validate(j)
        val old=all(c).find { it.getString("id")==j.getString("id") }
        val changed=old!=null && listOf("url","mode","linkSelector","pathPrefix","liveSelector","liveText","offlineText","offlineSelector","identityKeys","pathPattern","newestFirst","discovery","idAttribute","urlTemplate","videoOnly","structured","jsonRecordKind","browserMode","liveLocation","liveLinkSelector","liveAllowUnconfirmedAudio").any { old.optString(it)!=j.optString(it) }
        val a=JSONArray(all(c).filter { it.getString("id")!=j.getString("id") }+j)
        val f=file(c);val out=f.startWrite()
        try { out.write(a.toString().toByteArray());f.finishWrite(out) } catch(e: Exception) { f.failWrite(out);throw e }
        if(changed) MonitoringStore.rules(c).filter { it.customSource==j.getString("id") }.forEach { r ->
            CustomSetupPolicy.applySourceChange(r,old,j)
            r.generation=System.currentTimeMillis();r.next=0;r.status="Source changed; waiting for a fresh baseline"
            MonitoringScheduler.cancel(c,r.id);MonitoringStore.put(c,r);if(r.enabled) MonitoringScheduler.ensure(c,r)
        }
        MonitoringStore.log(c,"custom source saved; source=${j.getString("id")}; mode=${j.getString("mode")}; browserOnly=${j.optBoolean("browserOnly")}; credentials excluded")
    }
    fun remove(c: Context,id: String,removeRules: Boolean=false) {
        MonitoringStore.log(c,"source removal requested; source=$id; removeLinkedRules=$removeRules")
        check(MonitoringWorker.gate.tryLock()) { "A monitoring check is still running. Wait for it to finish, then try removal again." }
        try { synchronized(this) {
            val linked=MonitoringStore.rules(c).filter { it.customSource==id }
            require(linked.isEmpty() || removeRules) { "This source is used by ${linked.size} monitoring rule(s). Confirm removal of those rules too." }
            val monitorIds=MonitoringStore.jobs(c).filter { j -> linked.any { it.id==j.optString("rule") } }.map { it.getString("id") }.toSet()
            SourceRemovalTransaction.run(id,read={ all(c) },write={ payload ->
                // All reads finish before startWrite: AtomicFile.openRead can discard a pending .new file.
                val f=file(c);val out=f.startWrite()
                try { out.write(payload.toByteArray(Charsets.UTF_8));f.finishWrite(out) }
                catch(e: Exception) { f.failWrite(out);throw e }
            },cleanup={
                MonitoringStore.log(c,"source removal persisted and verified; source=$id; linked cleanup starting")
                val active=TransferQueue.dismissMonitoring(c,monitorIds)
                linked.forEach { r -> MonitoringScheduler.cancel(c,r.id);MonitoringStore.remove(c,r.id,active) }
                MonitoringStore.log(c,"source removal succeeded; source=$id; removedRules=${linked.size}; activeTasksRetained=${active.size}; downloaded files retained")
            })
            c.sendBroadcast(android.content.Intent(MonitoringStore.ACTION_STATE).setPackage(c.packageName))
        } } catch(e: Exception) { MonitoringStore.log(c,"source removal failed; source=$id; ${MonitoringStore.error(e)}");throw e }
        finally { MonitoringWorker.gate.unlock() }
    }
    fun source(c: Context,r: MonitoringRule): JSONObject {
        val template=all(c).find { it.optString("id")==r.customSource }
            ?: error("Custom source is missing. Edit this rule and select a source.")
        return CustomSourceBinding.bind(template,r.url,r.mode)
    }
    fun needsBrowser(c: Context,r: MonitoringRule)=r.customSource.isNotBlank() && source(c,r).optBoolean("browserOnly")
    fun method(c: Context,r: MonitoringRule)=if(r.customSource.isBlank()) "Built-in / general handler: "+MonitoringPolicy.handler(r.url)
        else runCatching { val j=source(c,r);"Custom rules: "+j.getString("name")+" — Automatic checks; last method: "+r.customState.optString("lastMethod","not checked") }.getOrDefault("Custom source missing")
    fun imported(raw: String): JSONObject {
        require(raw.toByteArray().size<=LIMIT) { "Source file exceeds 256 KB" }
        val j=JSONObject(raw);validate(j)
        // Imported recipes require local testing. Never trust an exported verification flag.
        return j.put("id",UUID.randomUUID().toString()).put("testedAt",0).put("browserOnly",true).put("uploadValidated","").put("liveValidated","")
    }
    fun validate(j: JSONObject) {
        require(j.keys().asSequence().all { it in fields }) { "Unknown fields: only declarative source rules are allowed" }
        require(j.optString("browserMode","mobile") in setOf("mobile","desktop")) { "Invalid browser mode" }
        require(j.getInt("schema") in 1..4) { "Unsupported source format" }
        require(j.getString("id").length in 1..80 && j.getString("name").length in 1..160) { "Invalid source name or ID" }
        val u=URI(j.getString("url"))
        require(u.scheme=="https" && u.host!=null && u.userInfo==null && u.fragment==null) { "Use an HTTPS channel URL without credentials or fragment" }
        require(!Regex("(?i)(token|cookie|password|auth|api.?key|signature)=").containsMatchIn(u.rawQuery.orEmpty())) { "Remove credentials or signed parameters from the channel URL" }
        require(j.getString("mode") in setOf("uploads","live","both")) { "Invalid mode" }
        for(key in listOf("linkSelector","liveSelector","offlineSelector","nextSelector","liveLinkSelector")) {
            if(j.getInt("schema")>=4 && ((j.getString("mode")=="live" && key in setOf("linkSelector","nextSelector")) || (j.getString("mode")=="uploads" && key in setOf("liveSelector","offlineSelector","liveLinkSelector")))) continue
            require(j.optString(key).length<=300) { "Selector too long" }
            require(!j.optString(key).replace(Regex(":nth-of-type\\([0-9]{1,3}\\)"),"").contains(':')) { "Use structural CSS selectors only; regex and executable rules are not supported" }
            if(j.optString(key).isNotBlank()) Jsoup.parse("<html></html>").select(j.getString(key))
        }
        if(j.getInt("schema")<4 || j.getString("mode")!="live") {
        require(j.optString("pathPrefix").startsWith('/') && j.optString("pathPrefix").length<=500) { "Choose a same-site video path prefix, such as /videos/" }
        require(j.optString("pathPattern").length<=500 && !j.optString("pathPattern").contains("**")) { "Invalid video link pattern" }
        require(CustomUploadPolicy.keys(j).size<=8 && CustomUploadPolicy.keys(j).all { it.length in 1..40 && it.matches(Regex("[A-Za-z0-9_-]+")) }) { "Invalid identity fields" }
        require(j.optString("discovery", "links") in setOf("links","content")) { "Invalid discovery method" }
        require(j.optString("idAttribute").isBlank() || j.optString("idAttribute") in CustomContentDiscovery.idAttributes) { "Unsupported item ID attribute" }
        require(j.optString("jsonRecordKind").isBlank() || j.optString("jsonRecordKind") in CustomContentDiscovery.recordKinds) { "Invalid learned JSON record shape" }
        if(j.optString("urlTemplate").isNotBlank()) {
            val template=j.getString("urlTemplate")
            require(template.length<=1000 && template.split("{id}").size==2 && CustomContentDiscovery.resolveId("test-item",template,j.getString("url"))!=null) { "Invalid same-site item address template" }
        }
        j.optJSONArray("examples")?.let { a ->
            require(a.length()<=20) { "Too many examples" }
            (0 until a.length()).forEach { require(CustomContentDiscovery.stable(a.getString(it),j.getString("url"))!=null) { "Invalid item example" } }
        }
        }
        if(j.getString("mode")!="live") require(j.optString("linkSelector").isNotBlank() && (j.getString("pathPrefix")!="/" || (j.optString("pathPattern").isNotBlank() && j.optString("pathPattern")!="/*"))) { "Choose a specific upload link pattern" }
        if(j.getString("mode")!="uploads") {
            if(j.getInt("schema")<4) {
                require(j.optString("liveSelector").isNotBlank() && j.optString("liveText").isNotBlank() && j.optString("offlineText").isNotBlank()) { "Live setup needs a selector and distinct online/offline text" }
            } else {
                require(j.optString("liveLocation","page") in setOf("page","linked")) { "Choose where the live stream plays" }
                require(j.optString("liveLocation","page")!="linked" || j.optString("liveLinkSelector").isNotBlank()) { "Select the card or link that opens the live broadcast" }
                require(j.optString("liveText").isBlank() || j.optString("liveSelector").isNotBlank()) { "Live text needs a selector" }
                require(j.optString("offlineText").isBlank() || j.optString("offlineSelector").isNotBlank() || j.optString("liveSelector").isNotBlank()) { "Offline text needs a selector" }
            }
            if(j.optString("offlineText").isNotBlank()) require(!j.optString("liveText").equals(j.optString("offlineText"),true)) { "Online and offline indicators must differ" }
        }
        if(j.getInt("schema")<4 || j.getString("mode")!="uploads") require(j.optString("liveText").length<=160 && j.optString("offlineText").length<=160) { "Live indicator text too long" }
    }
    fun parse(j: JSONObject,html: String,live: Boolean,pageUrl: String=j.getString("url")): MonitoringResult {
        val base=j.getString("url");val doc=Jsoup.parse(html,pageUrl)
        if(live && j.getInt("schema")>=4) {
            val state=CustomLivePolicy.state(j,html,pageUrl)
            return MonitoringResult(offline=state.offline,detail=state.detail)
        }
        if(live) {
            val visible=doc.select(j.getString("liveSelector")).text()
            val offVisible=doc.select(j.optString("offlineSelector").ifBlank { j.getString("liveSelector") }).text()
            val on=visible.contains(j.getString("liveText"),true)
            val off=offVisible.contains(j.getString("offlineText"),true)
            check(on!=off) { "Live indicator is ambiguous or missing; Browser Assist required" }
            return if(on) MonitoringResult(live=MonitoringItem("custom:"+j.getString("id"),base,j.getString("name")),detail="Custom source reports live; playback still requires fresh verification")
                else MonitoringResult(offline=true,detail="Explicit custom offline indicator matched")
        }
        if(j.optString("discovery")=="content") return CustomContentDiscovery.items(j,html,pageUrl)
        val origin=URI(base)
        val items=doc.select(j.getString("linkSelector")).mapNotNull { e ->
            val raw=if(e.hasAttr("href")) e.absUrl("href") else e.selectFirst("a[href]")?.absUrl("href").orEmpty()
            val uri=runCatching { URI(raw) }.getOrNull() ?: return@mapNotNull null
            if(uri.scheme!="https" || uri.host!=origin.host || uri.port!=origin.port || uri.userInfo!=null || !uri.path.startsWith(j.getString("pathPrefix")) || raw==base) return@mapNotNull null
            if(Regex("(?i)(token|auth|cookie|signature|key)=").containsMatchIn(uri.rawQuery.orEmpty())) return@mapNotNull null
            if(!CustomUploadPolicy.matchesPath(uri.path,j.optString("pathPattern"))) return@mapNotNull null
            val keys=CustomUploadPolicy.keys(j)
            if(keys.any { it !in CustomUploadPolicy.query(uri) }) return@mapNotNull null
            val url=CustomUploadPolicy.canonical(raw,keys)
            val card=e.closest("article,li,[data-video-id]") ?: e.parent() ?: e
            val title=e.attr("title").ifBlank { e.attr("aria-label") }.ifBlank { e.selectFirst("img[alt]")?.attr("alt").orEmpty() }
                .ifBlank { card.selectFirst("h1,h2,h3,h4")?.text().orEmpty() }.ifBlank { e.text() }.take(240)
            val date=card.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
            val published=runCatching { java.time.Instant.parse(date).toEpochMilli() }.getOrElse { runCatching { java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0L) }
            val image=e.selectFirst("img")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }.orEmpty()
            MonitoringItem("custom:"+url,url,title,published,image)
        }.distinctBy { it.id }.take(200)
        check(items.isNotEmpty()) { "No matching uploads; baseline retained. Test the source or sign in." }
        return MonitoringResult(items=items,detail="${items.size} custom upload links; first 200 visible matches")
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(2048)
        while(out.size()<limit) { val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0) break;out.write(buffer,0,n) }
        return out.toByteArray()
    }
    fun fetch(c: Context,j: JSONObject,stopped: ()->Boolean): String {
        check(!stopped()) { "Check canceled" }
        val url=j.getString("url");val conn=URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout=15000;conn.readTimeout=15000;conn.instanceFollowRedirects=false
        conn.setRequestProperty("User-Agent",MonitoringSession.requestAgent(c,j.optString("browserMode","mobile")))
        MonitoringAccess.observe(c,url,"custom-http",j.optString("browserMode","mobile"))
        MonitoringAccess.requestCookie(c,url).takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie",it) }
        try {
            val status=conn.responseCode
            // Only accept cookies from this exact HTTPS origin; redirects are never followed here.
            conn.headerFields.entries.filter { it.key?.equals("Set-Cookie",true)==true }.flatMap { it.value.orEmpty() }
                .forEach { CookieManager.getInstance().setCookie(url,it) }
            MonitoringSession.flush(c,"direct monitoring response")
            MonitoringStore.log(c,"custom source HTTP; source=${j.getString("id")}; status=$status; type=${conn.contentType}; browserCookies=${!CookieManager.getInstance().getCookie(url).isNullOrBlank()}")
            if(status in setOf(401,403)) {
                val prefix=conn.errorStream?.use { it.readBytesBounded(8192).toString(Charsets.UTF_8) }.orEmpty()
                MonitoringInteractionPolicy.responseKind(url,prefix,status)?.let { throw BrowserCheckRequired(MonitoringInteractionPolicy.message(it)) }
            }
            check(status==200) { "Channel returned HTTP $status; open Browser Assist (redirects require updating source URL)" }
            check(conn.contentType.orEmpty().contains("html",true)) { "Channel response is not HTML" }
            val out=java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf=ByteArray(8192)
                while(true) { check(!stopped()) { "Check canceled" };val n=input.read(buf);if(n<0) break;out.write(buf,0,n);check(out.size()<=3_000_000) { "Channel page exceeds 3 MB" } }
            }
            val html=out.toByteArray().toString(Charsets.UTF_8)
            MonitoringInteractionPolicy.responseKind(url,html,status)?.let { throw BrowserCheckRequired(MonitoringInteractionPolicy.message(it)) }
            return html
        } finally { conn.disconnect() }
    }
    fun check(c: Context,r: MonitoringRule,live: Boolean,stopped: ()->Boolean): MonitoringResult {
        val result=CustomMonitoringEngine.check(c,r,stopped)
        return (if(live) result.live else result.uploads) ?: error("This custom source does not support that check mode")
    }
    fun applyUploads(c: Context,r: MonitoringRule,items: List<MonitoringItem>,coverage: Boolean=false,headValidated: Boolean=false): String {
        val j=source(c,r)
        val known=r.seen.map { old -> runCatching { "custom:"+CustomUploadPolicy.canonical(old,CustomUploadPolicy.keys(j)) }.getOrDefault(old) }.toSet()
        val outcome=CustomUploadPolicy.decide(r.customState,known,items,System.currentTimeMillis(),coverage,j.optBoolean("newestFirst"),headValidated)
        r.customState=outcome.state;r.seen=outcome.seen.toMutableSet();r.baseline=true
        var queued=0
        outcome.decisions.forEach { d ->
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(d.item.id.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
            MonitoringStore.log(c,"custom item classified; rule=${r.id}; item=$hash; kind=${d.kind}; reason=${d.reason}")
            if(d.kind=="new") {
                val id=MonitoringStore.enqueue(c,r,d.item,false);queued++
                MonitoringNotifications.discovery(c,id,"New upload detected",r.name)
            }
        }
        return outcome.summary()+" "+if(r.automatic) "Downloads queued=$queued." else "Notifications=$queued; automatic download disabled."
    }
    /** Both scheduled and visible-browser checks share identity, baseline, review and queue state. */
    fun observe(c: Context,j: JSONObject,items: List<MonitoringItem>,html: String,coverage: Boolean=false,onlyRule: String=""): String {
        check(MonitoringWorker.gate.tryLock()) { "A scheduled check is running; try the browser check again shortly" }
        try {
            val rules=MonitoringStore.rules(c).filter { it.customSource==j.getString("id") &&
                LiveRecordingPolicy.samePage(it.url,j.getString("url")) && (onlyRule.isBlank() || it.id==onlyRule) }
            check(rules.isNotEmpty()) { "No monitoring rule uses this channel view. Open the desired rule's Open browser to check, or add a monitoring link for this channel." }
            val summaries=rules.map { r ->
                val bound=source(c,r)
                val details=mutableListOf<String>()
                val liveAttempt=if(r.mode in setOf("live","both")) runCatching { if(j.getInt("schema")>=4) CustomLiveResolver.resolve(c,bound,html,{ false },requireAdvancing=r.customState.optBoolean("liveEndConfirmed")) else parse(bound,html,true) } else null
                if(j.getInt("schema")<4) liveAttempt?.getOrThrow() // Legacy behavior is unchanged.
                val liveResult=liveAttempt?.getOrNull()
                if(r.mode in setOf("uploads","both")) details.add(applyUploads(c,r,items,coverage))
                if(liveResult!=null) {
                    val result=liveResult
                    if(result.offline) { r.offlineChecks++;if(r.offlineChecks>=2) r.liveSession="" }
                    else if(result.live!=null) {
                        r.offlineChecks=0
                        details.add(MonitoringCoordinator.acceptLive(c,r,result.live))
                    }
                    details.add(result.detail)
                }
                liveAttempt?.exceptionOrNull()?.let { details.add("Live state unknown; session retained: "+MonitoringStore.error(it)) }
                r.last=System.currentTimeMillis();r.status="Browser check complete. "+details.joinToString(" ");r.failures=if(liveAttempt?.isFailure==true) r.failures+1 else 0;r.notified=""
                check(MonitoringStore.commitCheck(c,r)) { "Rule changed during this check; check again" }
                MonitoringStore.log(c,"browser check completed; rule=${r.id}; ${r.status}")
                r.name+": "+r.status
            }
            MonitoringCoordinator.dispatch(c)
            return summaries.joinToString("\n")
        } finally { MonitoringWorker.gate.unlock() }
    }
    fun observe(c: Context,j: JSONObject,html: String) = observe(c,j,if(j.getString("mode")=="live") emptyList() else parse(j,html,false).items,html)
}
