package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.CancellationException

/** VK's Records tab exposes its own listing, separate from the live player. */
internal object VkReplayListing {
    const val BASELINE_SCOPE = "vkReplayBaselineScope"
    private val uuid=Regex("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    data class Listing(val items: List<MonitoringItem>)

    fun pages(url: String): List<String> {
        val root=CaptureSiteRoutes.vkLiveRoot(url) ?: return emptyList()
        val records="$root/records"
        return if(CaptureSiteRoutes.path(URI(url)).endsWith("/records")) listOf(records)
            else listOf(root,records)
    }

    fun discover(url: String,stopped: ()->Boolean,read: (String)->String,log: (String)->Unit): Listing {
        var lastFailure: Exception?=null
        for(page in pages(url)) {
            if(stopped()) throw CancellationException("Replay check canceled")
            val records=page.endsWith("/records")
            val label=if(records) "Records tab" else "channel page"
            try {
                val result=parse(page,read(page))
                if(stopped()) throw CancellationException("Replay check canceled")
                if(result!=null && (records || result.items.isNotEmpty())) {
                    log("VK Video Live: verified ${result.items.size} finished replays from $label")
                    return result
                }
                log("VK Video Live: no verified finished replays on $label" +
                    if(records) "; keeping the existing baseline" else "; trying the Records tab")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(stopped()) throw CancellationException("Replay check canceled")
                lastFailure=e
                log("VK Video Live: $label unavailable; type=${e.javaClass.simpleName}; values omitted")
            }
        }
        throw IllegalStateException("VK Video Live replay listing could not be verified; existing baseline retained",lastFailure)
    }

    fun parse(page: String,html: String): Listing? {
        val route=CaptureSiteRoutes.vkLiveChannel(page) ?: return null
        val root=CaptureSiteRoutes.vkLiveRoot(page) ?: return null
        val script=Jsoup.parse(html).select("script#initial-state").singleOrNull() ?: return null
        val state=runCatching { JSONObject(script.data().ifBlank { script.text() }) }.getOrNull() ?: return null
        val listing=state.optJSONObject("record")?.optJSONObject("records") ?: return null
        val status=listing.optJSONObject("meta")?.optJSONObject("networkStatus") ?: return null
        if(status.opt("isFetched")!=true || status.opt("isFetching")!=false ||
            status.has("error") && !status.isNull("error")) return null
        val data=listing.optJSONObject("data") ?: return null
        val rows=data.optJSONArray("data") ?: return null
        val stream=state.optJSONObject("stream")?.optJSONObject("stream")?.optJSONObject("data")?.optJSONObject("stream")
        val ownerHints=listOf(
            stream?.optString("blogUrl").orEmpty(),
            state.optJSONObject("channel")?.optJSONObject("channel")?.optJSONObject("data")?.optString("blogUrl").orEmpty()
        ).filter(String::isNotBlank)
        if(ownerHints.any { !it.equals(route.id,true) }) return null
        val activeId=stream?.takeIf { it.opt("isOnline")==true }?.optString("id").orEmpty()
        val items=mutableListOf<MonitoringItem>()
        var ownedRows=0
        for(i in 0 until minOf(rows.length(),200)) {
            val row=rows.optJSONObject(i) ?: continue
            if(!row.optJSONObject("blog")?.optString("blogUrl").equals(route.id,true)) continue
            val id=row.optString("id").takeIf(uuid::matches)?.lowercase() ?: continue
            ownedRows++
            if(id.equals(activeId,true) || row.opt("isOnline")==true ||
                row.optString("processState")!="done" || row.optDouble("duration",0.0).let { !it.isFinite() || it<=0 } ||
                row.opt("isHidden")!=false || row.opt("hasAccess")!=true ||
                row.optJSONObject("accessRestrictions")?.optJSONObject("view")?.opt("allowed")!=true) continue
            val timestamp=row.optLong("createdAt").coerceIn(0,100_000_000_000L)*1_000L
            items+=MonitoringItem("Capture:VK:replay:$id","$root/record/$id",
                row.optString("title").take(160).ifBlank { "VK Live replay" },timestamp)
        }
        // No rows from this creator means no trustworthy listing unless VK
        // explicitly finished loading an empty listing for the same channel.
        if(ownedRows==0 && !(rows.length()==0 && ownerHints.isNotEmpty() &&
                data.optJSONObject("extra")?.opt("isLast")==true)) return null
        return Listing(items.distinctBy { it.id }.sortedByDescending { it.publishedAt }.take(40))
    }
}
