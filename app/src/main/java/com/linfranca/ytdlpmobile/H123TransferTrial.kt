package com.linfranca.ytdlpmobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

/** Limited to already verified h123 VOD plans. Never runs for visible Browser Assist or recordings. */
internal object H123TransferTrial {
    fun choose(c: Context,page: String,normal: WebsiteHandlerBridge.Prepared,stopped: ()->Boolean,log: (String)->Unit,retain: (File)->Unit = {},
        probe: (String,String,File)->H123TrialPolicy.Measurement): WebsiteHandlerBridge.Prepared {
        if(WebsiteSourceResolver.site(page)!="h123movies") return normal
        fun competing()=TransferQueue.jobs(c).count { it.optString("state") in TransferRules.activeStates }>1
        if(competing()) { log("h123 route trial skipped; another media transfer is active; normal selection retained");return normal }
        val info=JSONObject(normal.info)
        if(info.optBoolean("_app_browser_prepared")) { log("h123 route choice=background-player recovery; normal preparation unavailable or website provider uses browser; duplicate speed trial skipped");return normal }
        if(info.optString("_app_trial_player").isBlank() || info.optString("_app_trial_source").isBlank()) return normal
        if(c.cacheDir.usableSpace<512L*1024*1024) { log("h123 route trial skipped; insufficient spare cache space; normal download retained");return normal }
        val folder=File(c.cacheDir,"h123-trials/${UUID.randomUUID()}").apply { mkdirs() }
        var candidateJar: File?=null
        var stage="normal sample"
        fun keepNormal(): WebsiteHandlerBridge.Prepared {
            runCatching { retain(File(folder,"normal")) }.onFailure { log("h123 trial cache promotion unavailable; original retained media unchanged; normal download can fetch fresh fragments") }
            return normal
        }
        try {
            if(stopped()) throw CancellationException("Download canceled")
            log("h123 route trial started; sequential yt-dlp samples; each limited to 8s / 48 MiB after 2s; selected server=${info.optInt("_app_trial_server")}; values omitted")
            val normalResult=probe(normal.info,normal.jar.path,File(folder,"normal").apply { mkdirs() })
            log("h123 route trial normal; bytes=${normalResult.bytes}; elapsedMs=${normalResult.elapsedMs}; usable=${normalResult.valid}; bytesPerSecond=${normalResult.bytesPerSecond.toLong()}")
            if(stopped()) throw CancellationException("Download canceled")
            stage="background capture"
            val captured=H123BackgroundCapture.capture(c,info,normal.jar.readLines(),stopped,log)
            stage="candidate validation"
            val browserInfo=VerifiedMediaAccess(captured.referer,captured.agent,{ captured.headers },stopped,MediaCookieJar().apply { load(normal.jar.readText()) },budgetMs=12000)
                .plan(captured.url,page,info.optString("title"))
            listOf("id","_app_variant","_app_trial_server","_app_trial_source","_app_trial_player","_app_trial_agent","subtitles").forEach { key -> if(info.has(key)) browserInfo.put(key,info.get(key)) }
            if(info.optString("_app_audio_language")=="en") H123AudioLanguage.plan(browserInfo,log)
            if(info.optBoolean("_app_english_rendition_verified") && !browserInfo.optBoolean("_app_english_rendition_verified")) {
                log("h123 route trial choice=normal; reason=browser candidate did not establish the selected English rendition")
                return keepNormal()
            }
            val identity=H123TrialPolicy.identityMatches(info,browserInfo)
            val quality=H123TrialPolicy.equivalentQuality(info,browserInfo)
            log("h123 route trial validation; titleEpisodeServerAudioIdentity=$identity; equivalentQuality=$quality; complete VOD=true; URL/header values omitted")
            if(!identity || !quality) { log("h123 route trial choice=normal; reason=browser candidate identity or equivalent quality not established");return keepNormal() }
            candidateJar=File(c.cacheDir,"website-jars/trial-${UUID.randomUUID()}.txt").apply { parentFile?.mkdirs();writeText(browserInfo.optString("_app_cookie_jar")) }
            stage="browser sample"
            val browserResult=probe(browserInfo.toString(),candidateJar!!.path,File(folder,"browser").apply { mkdirs() })
            log("h123 route trial browser; bytes=${browserResult.bytes}; elapsedMs=${browserResult.elapsedMs}; usable=${browserResult.valid}; bytesPerSecond=${browserResult.bytesPerSecond.toLong()}")
            if(stopped()) throw CancellationException("Download canceled")
            if(competing()) { log("h123 route trial choice=normal; reason=competing transfer started; speed comparison inconclusive");return keepNormal() }
            if(H123TrialPolicy.browserWins(normalResult,browserResult)) {
                log("h123 route trial choice=browser-detected; reason=verified equivalent selection and measured faster (20% margin), or normal sample unavailable; final download uses existing yt-dlp/cache path")
                runCatching { retain(File(folder,"browser")) }.onFailure { log("h123 browser sample cache promotion unavailable; final download will fetch fresh fragments") }
                val result=WebsiteHandlerBridge.Prepared(browserInfo.toString(),candidateJar!!);candidateJar=null;normal.close();return result
            }
            log("h123 route trial choice=normal; reason=no reliable browser speed advantage")
            return keepNormal()
        } catch(e: CancellationException) { runCatching { retain(File(folder,"normal")) };throw e }
        catch(e: Exception) {
            if(stopped()) throw CancellationException("Download canceled")
            log("h123 route trial choice=normal; reason=trial unavailable; stage=$stage; category=${e.javaClass.simpleName}; existing fallback/Browser Assist retained")
            return keepNormal()
        } finally { candidateJar?.delete();folder.deleteRecursively();log("h123 route trial cleanup; unselected samples removed; verified selected fragments retained when compatible; existing retained downloads unchanged") }
    }
}
