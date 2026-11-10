package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONObject

/** Each method remains inside Monitoring. A saved HTML-only flag is a hint, not a permanent manual gate. */
internal object CustomMonitoringEngine {
    data class Check(val uploads: MonitoringResult?,val live: MonitoringResult?,val method: String,val headValidated: Boolean=false,val uploadsError: Exception?=null,val liveError: Exception?=null)
    fun check(c: Context,r: MonitoringRule,stopped: ()->Boolean,beforeRender: ()->Unit = {},sourceOverride: JSONObject?=null,allowRender: Boolean=true): Check {
        if(sourceOverride==null) {
            val originalSetup=CustomSources.source(c,r)
            check(originalSetup.optLong("testedAt")>0) { "Custom source needs local testing" }
            if(originalSetup.getInt("schema")>=4) check(CustomSetupPolicy.ready(JSONObject(originalSetup.toString()).put("mode",r.mode))) { "This monitoring mode needs its own validated custom setup" }
            val resolved=PageRedirects.resolve(c,r.url,stopped,{ MonitoringStore.log(c,it) },allowRender=allowRender).target
            if(resolved!=r.url) {
                val working=r.copy(url=resolved)
                val template=CustomSources.source(c,working)
                val checked=check(c,working,stopped,beforeRender,template,allowRender)
                fun stable(result: MonitoringResult?)=result?.copy(
                    items=result.items.map { it.copy(id=PageRedirects.stableId(it.id,r.url,resolved,it.url)) },
                    live=result.live?.let { it.copy(id=PageRedirects.stableId(it.id,r.url,resolved,it.url)) })
                return checked.copy(uploads=stable(checked.uploads),live=stable(checked.live))
            }
        }
        val j=sourceOverride ?: CustomSources.source(c,r)
        check(sourceOverride!=null || j.optLong("testedAt")>0) { "Custom source needs local testing" }
        if(j.getInt("schema")>=4) {
            val required=JSONObject(j.toString()).put("mode",r.mode)
            CustomSources.validate(required)
            check(sourceOverride!=null || CustomSetupPolicy.ready(required)) { "This monitoring mode needs its own validated custom setup. Edit the source and test uploads, live, or both." }
        }
        val upload=r.mode in setOf("uploads","both");val live=r.mode in setOf("live","both")
        if(j.getInt("schema")>=4 && live) {
            val uploads=if(upload) runCatching { check(c,r.copy(mode="uploads"),stopped,beforeRender,JSONObject(j.toString()).put("mode","uploads"),allowRender) } else null
            val state=runCatching {
                CustomLiveResolver.resolve(c,j,null,stopped,beforeRender={
                    if(!allowRender) throw MonitoringRenderDeferred("A browser playback test is required for this custom live source")
                    beforeRender()
                },requireAdvancing=r.customState.optBoolean("liveEndConfirmed"))
            }
            if(stopped()) throw java.util.concurrent.CancellationException("Monitoring check canceled")
            return Check(uploads?.getOrNull()?.uploads,state.getOrNull(),"Independent uploads / fresh live playback",uploads?.getOrNull()?.headValidated ?: false,
                uploads?.exceptionOrNull() as? Exception,state.exceptionOrNull() as? Exception)
        }
        var extracted: MonitoringResult?=null
        fun active() { if(stopped()) throw java.util.concurrent.CancellationException("Monitoring check canceled") }
        fun log(s: String)=MonitoringStore.log(c,"custom background; rule=${r.id}; $s")
        active()
        // An extractor understands creator ownership, unlike an arbitrary page-wide CSS selector.
        if(upload && CustomBackgroundPolicy.extractorCompatible(r.url)) {
            val result=runCatching {
                MonitoringSession.save(c,r.url,j.optString("browserMode","mobile"),MonitoringSession.marker(c,r.url))
                val raw=MonitoringProbe(c,stopped).check(r.copy(customSource=""),false)
                val items=CustomBackgroundPolicy.scoped(j,raw.items)
                check(items.isNotEmpty()) { "Extractor had no entries matching saved video identity rules" }
                MonitoringResult(items=items,detail="Compatible channel extractor/feed; ${items.size} matching recent entries; saved video identity filters applied")
            }
            active()
            result.onSuccess { extracted=it;log("method=extractor/feed; accepted=${it.items.size}") }
                .onFailure { log("method=extractor/feed; fallback reason=${MonitoringStore.error(it)}") }
            if(extracted!=null && !live) return Check(extracted,null,"extractor/feed",true)
        }
        if(upload && !CustomBackgroundPolicy.extractorCompatible(r.url)) log("method=extractor/feed skipped; built-in URL normalization would alter the custom page scope")
        fun fromHtml(html: String,method: String): Check {
            val uploads=if(upload) extracted ?: CustomSources.parse(j,html,false) else null
            val state=if(live) CustomSources.parse(j,html,true) else null
            return Check(uploads,state,if(extracted!=null) "extractor + $method" else method,true)
        }
        val direct=runCatching { active();fromHtml(CustomSources.fetch(c,j,stopped),"HTML") }
        active()
        direct.onSuccess { log("method=${it.method}; matched=${it.uploads?.items?.size ?: 0}") }
        if(direct.isSuccess) return direct.getOrThrow()
        log("method=HTML; fallback reason=${MonitoringStore.error(direct.exceptionOrNull()!!)}")
        if(!allowRender) throw BrowserCheckRequired("Direct methods did not validate this source; automatic browser rendering is the fallback")
        log("method=browser-rendered; requesting foreground execution")
        beforeRender();active()
        return try {
            val rendered=MonitoringPageRenderer.render(c,j,stopped)
            fromHtml(rendered.html,"browser-rendered").also { log("method=${it.method}; matched=${it.uploads?.items?.size ?: 0}; no window launched") }
        } catch(e: Exception) { log("method=browser-rendered; outcome=${MonitoringStore.error(e)}");throw e }
    }
}
