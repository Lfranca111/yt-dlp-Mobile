package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONObject
import java.net.URI

/** Bounded reuse of the existing shared-profile monitoring browser, after direct methods fail. */
internal object MonitoringBuiltinBrowser {
    fun eligible(page: String): Boolean = MonitoringPolicy.handler(page) in setOf("YouTube","Twitch","Chaturbate")
    fun samePage(saved: String,current: String): Boolean = runCatching {
        val a=URI(saved);val b=URI(current)
        fun host(u: URI)=u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
        val supported=host(a) in setOf("youtube.com","twitch.tv","chaturbate.com")
        supported && a.scheme=="https" && b.scheme=="https" && host(a)==host(b) &&
            a.userInfo==null && b.userInfo==null && (if(a.port<0) 443 else a.port)==(if(b.port<0) 443 else b.port) &&
            a.path.trimEnd('/')==b.path.trimEnd('/') && CustomUploadPolicy.query(a).all { (k,v)->CustomUploadPolicy.query(b)[k]==v }
    }.getOrDefault(false)
    private fun recipe(page: String,mode: String,ruleId: String): JSONObject {
        check(eligible(page)) { "This built-in route does not have a verified browser recovery path" }
        val j=JSONObject().put("id","builtin-browser").put("name",MonitoringPolicy.handler(page))
            .put("url",page).put("mode","live").put("browserMode",mode).put("liveLocation","page")
            .put("builtinBrowser",true).put("requireAdvancing",true).put("diagnosticRule",ruleId)
        if(MonitoringPolicy.handler(page)=="Chaturbate") {
            val slug=URI(page).path.trim('/').substringBefore('/')
            check(Regex("[A-Za-z0-9_]{1,120}").matches(slug)) { "Use a valid performer page" }
            j.put("builtinRoomSlug",slug)
        }
        return j
    }
    fun roomState(c: Context,page: String,mode: String,stopped: ()->Boolean,ruleId: String="none"): String {
        val j=recipe(page,mode,ruleId)
        val trace=MonitoringTrace(c,"browser-room-status").apply { rule(ruleId) }
        var lastEvidence=""
        val snapshot=MonitoringPageRenderer.render(c,j,stopped,validation={ s ->
            val data=runCatching { JSONObject(s.room) }.getOrDefault(JSONObject())
            if(data.optBoolean("pending",true)) emptyList()
            else {
                val evidence="${data.optInt("status")}:${data.optString("state")}:${data.optString("gate")}"
                if(evidence!=lastEvidence) {
                    lastEvidence=evidence
                    trace.event("api-response","status=${data.optInt("status")}; state=${data.optString("state").ifBlank { "unknown" }}; gate=${data.optString("gate").ifBlank { "not-identified" }}")
                }
                if(data.optString("gate").isNotBlank()) throw BrowserCheckRequired(MonitoringInteractionPolicy.message(data.getString("gate")))
                if(data.optInt("status") in setOf(401,403))
                    throw BrowserCheckRequired("Browser room status request denied (HTTP ${data.optInt("status")}); cause unconfirmed; live state unknown. Review the session or website prompt in Browser Assist.")
                check(data.optInt("status")==200) { "Browser room status request failed; HTTP ${data.optInt("status")}; live state unknown" }
                val state=data.optString("state")
                check(state in setOf("public","offline","private","group","away","hidden")) { "Browser room status was not explicit; live state unknown" }
                listOf(state)
            }
        })
        return JSONObject(snapshot.room).getString("state")
    }
    fun verify(c: Context,item: MonitoringItem,mode: String,stopped: ()->Boolean,ruleId: String="none",requireYouTubeIdentity: Boolean=false): MonitoringResult {
        val j=recipe(item.url,mode,ruleId)
        if(requireYouTubeIdentity) j.put("requireYouTubeIdentity",true)
        if(j.has("builtinRoomSlug")) j.put("builtinRequirePublic",true)
        // Skip the extractor that already failed; require an advancing manifest and matching audio.
        val result=CustomLiveResolver.resolve(c,j,"",stopped,requireAdvancing=true,skipExtractor=true)
        check(result.live!=null && result.video.isNotBlank()) { "Browser recovery did not verify fresh live playback; state unknown" }
        return result.copy(live=item,detail="Fresh built-in live playback verified using saved browser session")
    }
}
