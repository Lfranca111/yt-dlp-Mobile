package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Version114PolicyTest {
    private fun source(id: String)=JSONObject().put("id",id).put("name","Source $id")
    @Test fun removalFinishesWriteBeforeVerificationAndCleanup() {
        var saved=listOf(source("a"),source("b"));var open=false
        val events=mutableListOf<String>()
        SourceRemovalTransaction.run("a",read={
            check(!open) { "AtomicFile read while pending write was open" }
            events.add("read");saved
        },write={ payload ->
            open=true;events.add("startWrite")
            val a=JSONArray(payload);saved=(0 until a.length()).map { a.getJSONObject(it) }
            events.add("finishWrite");open=false
        },cleanup={ events.add("cleanup");assertEquals("b",saved.single().getString("id")) })
        assertEquals(listOf("read","startWrite","finishWrite","read","cleanup"),events)
    }
    @Test fun uncommittedWriteDoesNotRemoveLinkedRules() {
        val saved=listOf(source("a"));var cleaned=false
        val result=runCatching { SourceRemovalTransaction.run("a",{saved},{ /* Simulate a failed rename or stale disk contents. */ },{cleaned=true}) }
        assertTrue(result.isFailure);assertFalse(cleaned);assertTrue(result.exceptionOrNull()!!.message!!.contains("linked rules were retained"))
    }
    @Test fun writeFailureDoesNotCleanUp() {
        var cleaned=false;var reads=0
        val result=runCatching { SourceRemovalTransaction.run("a",{reads++;listOf(source("a"))},{throw java.io.IOException("disk full")},{cleaned=true}) }
        assertTrue(result.exceptionOrNull() is java.io.IOException);assertFalse(cleaned);assertEquals(1,reads)
    }
    @Test fun emptyListAndRetryStillAllowLinkedCleanup() {
        var cleaned=0;var payload=""
        SourceRemovalTransaction.run("already-removed",{emptyList()},{payload=it},{cleaned++})
        assertEquals("[]",payload);assertEquals(1,cleaned)
    }
    @Test fun removingOneSourcePreservesOtherRecipes() {
        var saved=listOf(source("a"),source("b").put("url","https://example.com/channel").put("browserOnly",true))
        SourceRemovalTransaction.run("a",{saved},{p->val a=JSONArray(p);saved=(0 until a.length()).map { a.getJSONObject(it) }},{})
        assertEquals(1,saved.size);assertEquals("https://example.com/channel",saved.single().getString("url"));assertTrue(saved.single().getBoolean("browserOnly"))
    }
    @Test fun browserOnlyCustomCheckSelectsRenderingWithoutOpeningUi() {
        assertEquals(MonitoringCheckPolicy.Route.BROWSER_RENDERED,MonitoringCheckPolicy.route(true,true))
    }
    @Test fun capableCustomCheckUsesBackground() {
        assertEquals(MonitoringCheckPolicy.Route.BACKGROUND,MonitoringCheckPolicy.route(true,false))
    }
    @Test fun builtInCheckUsesBackgroundRegardlessOfCustomFlag() {
        assertEquals(MonitoringCheckPolicy.Route.BACKGROUND,MonitoringCheckPolicy.route(false,true))
        assertEquals(MonitoringCheckPolicy.Route.BACKGROUND,MonitoringCheckPolicy.route(false,false))
    }
}
