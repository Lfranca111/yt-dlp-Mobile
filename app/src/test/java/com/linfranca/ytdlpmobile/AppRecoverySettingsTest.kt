package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
class AppRecoverySettingsTest {
    private fun rule()=JSONObject().put("name","Channel").put("url","https://example.com/channel").put("mode","live").put("automatic",true).put("interval",15).put("zone","UTC").put("start",-1).put("end",-1).put("days",JSONArray(listOf(1,2,3,4,5,6,7))).put("customSource","")
    private fun file(r: JSONObject=rule())=JSONObject().put("format","yt-dlp-Mobile-recovery").put("schema",1).put("theme","dark").put("orientation","android").put("sources",JSONArray()).put("rules",JSONArray(listOf(r))).toString()
    private fun rejects(raw: String) { try { AppRecoverySettings.plan(raw);fail("Expected rejection") } catch(_: RuntimeException) {} }
    @Test fun restoredRulesCannotStartAndHaveFreshState() { val a=AppRecoverySettings.plan(file()).rules.single();val b=AppRecoverySettings.plan(file()).rules.single();assertFalse(a.enabled);assertFalse(a.baseline);assertTrue(a.seen.isEmpty());assertEquals(0L,a.next);assertEquals("",a.liveSession);assertNotEquals(a.id,b.id);assertTrue(a.automatic) }
    @Test fun rejectCredentialsUnknownFieldsAndMissingTemplates() { rejects(file(rule().put("url","https://example.com/a?token=secret")));rejects(file(rule().put("cookies","secret")));rejects(file(rule().put("customSource","missing")));rejects(file(rule().put("zone","Invalid/Zone"))) }
    @Test fun customSourcesRequireLocalRetestAndRemapIds() {
        val source=JSONObject().put("schema",4).put("id","old").put("name","Custom live").put("url","https://example.com/channel").put("mode","live").put("testedAt",999).put("browserOnly",false).put("liveValidated","yes")
        val raw=JSONObject(file(rule().put("customSource","old"))).put("sources",JSONArray(listOf(source))).toString()
        val p=AppRecoverySettings.plan(raw);val fresh=p.sources.single();assertNotEquals("old",fresh.getString("id"));assertEquals(0L,fresh.getLong("testedAt"));assertEquals("",fresh.getString("liveValidated"));assertTrue(fresh.getBoolean("browserOnly"));assertEquals(fresh.getString("id"),p.rules.single().customSource);assertFalse(p.rules.single().enabled)
    }
    @Test fun boundsAndSchema() { rejects(file(rule().put("interval",0)));rejects(file(rule().put("days",JSONArray(listOf(8)))));rejects(JSONObject(file()).put("schema",99).toString());rejects(JSONObject(file()).put("theme","bogus").toString()) }
}
