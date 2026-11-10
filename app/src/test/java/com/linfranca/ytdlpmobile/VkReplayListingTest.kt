package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class VkReplayListingTest {
    private val root="https://live.vkvideo.ru/krylyaryadom_"
    private val records="$root/records"
    private val id="10000000-2000-3000-4000-500000000001"
    private val newer="10000000-2000-3000-4000-500000000002"

    private fun row(id: String, owner: String="krylyaryadom_", seconds: Int=123, created: Long=1794304240)=JSONObject()
        .put("id",id).put("title","Replay").put("createdAt",created).put("duration",seconds)
        .put("blog",JSONObject().put("blogUrl",owner)).put("processState","done").put("hasAccess",true).put("isHidden",false)
        .put("accessRestrictions",JSONObject().put("view",JSONObject().put("allowed",true)))

    private fun html(rows: JSONArray?, fetched: Boolean=true, owner: String="krylyaryadom_", liveId: String="") : String {
        val state=JSONObject()
        state.put("record",JSONObject().put("records",JSONObject()
            .put("data",JSONObject().put("data",rows ?: JSONObject.NULL).put("extra",JSONObject().put("isLast",true)))
            .put("meta",JSONObject().put("networkStatus",JSONObject().put("isFetched",fetched)
                .put("isFetching",false).put("error",JSONObject.NULL)))))
        val stream=JSONObject().put("blogUrl",owner).put("id",liveId).put("isOnline",liveId.isNotBlank())
        state.put("stream",JSONObject().put("stream",JSONObject().put("data",JSONObject().put("stream",stream))))
        return "<script id='initial-state' type='text/plain'>$state</script>"
    }

    @Test fun liveAlwaysUsesRootAndRecordsIsNotAnIndividualReplay() {
        assertEquals(root,CaptureSiteRoutes.livePage(records+"?tab=records#top"))
        assertEquals(root,CaptureSiteRoutes.livePage(root))
        assertEquals("VK Video Live",MonitoringPolicy.handler(records))
        for (bad in listOf("https://live.vkvideo.ru.evil.invalid/krylyaryadom_/records",
            "$root/record/$id", "$root/videos", "https://live.vkvideo.ru/records")) {
            assertNull(CaptureSiteRoutes.vkLiveRoot(bad))
            assertTrue(VkReplayListing.pages(bad).isEmpty())
        }
    }

    @Test fun rootTriesRecordsOnlyWhenNecessaryAndExplicitRecordsNeverTriesRoot() {
        val good=html(JSONArray().put(row(id)))
        val unloaded=html(null,fetched=false)
        fun check(input: String,first: String,expected: List<String>) {
            val requested=mutableListOf<String>()
            val list=VkReplayListing.discover(input,{false},{
                requested+=it
                if(it==root) first else good
            },{})
            assertEquals(expected,requested)
            assertEquals("$root/record/$id",list.items.single().url)
        }
        check(root,unloaded,listOf(root,records))
        check(root,good,listOf(root))
        check(records,unloaded,listOf(records))
    }

    @Test fun onlyFinishedOwnedAccessibleReplaysBecomeStableItems() {
        val live="10000000-2000-3000-4000-500000000003"
        val rows=JSONArray().put(row(id)).put(row(newer,created=1794305000))
            .put(row(live)).put(row("10000000-2000-3000-4000-500000000004",owner="other"))
            .put(row("10000000-2000-3000-4000-500000000005").put("processState","wait"))
            .put(row("10000000-2000-3000-4000-500000000006").put("isHidden",true))
            .put(row("10000000-2000-3000-4000-500000000007").put("hasAccess",false))
            .put(row("10000000-2000-3000-4000-500000000008",seconds=0))
            .put(row(id)).put(row("../escape"))
        val items=requireNotNull(VkReplayListing.parse(records,html(rows,liveId=live))).items
        assertEquals(listOf("Capture:VK:replay:$newer","Capture:VK:replay:$id"),items.map { it.id })
        assertEquals(1794305000000L,items.first().publishedAt)
    }

    @Test fun rejectsUnknownOrWrongCreatorStateAndDoesNotSwallowCancellation() {
        assertNull(VkReplayListing.parse(records,html(null,false)))
        assertNull(VkReplayListing.parse(records,html(JSONArray().put(row(id)),owner="other")))
        assertNull(VkReplayListing.parse(records,html(JSONArray().put(row(id,owner="other")))))
        assertTrue(requireNotNull(VkReplayListing.parse(records,html(JSONArray()))).items.isEmpty())
        val visited=mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            VkReplayListing.discover(root,{false},{ visited+=it; "<html>Sign in</html>" },{})
        }
        assertEquals(listOf(root,records),visited)
        assertThrows(CancellationException::class.java) {
            VkReplayListing.discover(root,{false},{ throw CancellationException() },{})
        }
    }
}
