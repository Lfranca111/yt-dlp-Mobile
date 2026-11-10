package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class AppUpdatePolicyTest {
    private fun asset(name: String="universal.apk",url: String="https://github.com/Lfranca111/yt-dlp-Mobile/releases/download/v1.0.66/universal.apk",size: Long=200)=JSONObject().put("name",name).put("browser_download_url",url).put("size",size)
    private fun release(vararg assets: JSONObject)=JSONObject().put("tag_name","v1.0.66").put("assets",JSONArray(assets.toList()))
    private fun rejects(block: ()->Unit) { try { block();fail("Expected rejection") } catch(_: IllegalArgumentException) {} catch(_: IllegalStateException) {} }
    @Test fun numericVersionsAndDevelopmentBuilds() {
        assertTrue(AppUpdatePolicy.newer("v1.0.100","1.0.65"));assertFalse(AppUpdatePolicy.newer("1.0.63","1.0.65"));assertFalse(AppUpdatePolicy.newer("v1.0.65","1.0.65"));assertFalse(AppUpdatePolicy.newer("v1.0.66-rc1","1.0.65"));assertFalse(AppUpdatePolicy.newer("bad","1.0.65"));assertFalse(AppUpdatePolicy.newer("999999999999999999999999","1.0.65"));assertFalse(AppUpdatePolicy.newer("1.0.65.0","1.0.65"))
    }
    @Test fun trustedRedirectsOnly() {
        assertTrue(AppUpdatePolicy.allowed("https://release-assets.githubusercontent.com/path?sig=value"))
        listOf("http://github.com/path","https://github.com.evil.test/a","https://evil.test/github.com","https://user@github.com/a","https://github.com:444/a","file:///tmp/a").forEach { assertFalse(it,AppUpdatePolicy.allowed(it)) }
    }
    @Test fun stableApkAndUniversalSelection() {
        assertEquals("universal.apk",AppUpdatePolicy.release(release(asset(),asset("arm64.apk"))).getString("name"))
        rejects { AppUpdatePolicy.release(release(asset()).put("draft",true)) };rejects { AppUpdatePolicy.release(release(asset()).put("prerelease",true)) }
        rejects { AppUpdatePolicy.release(release(asset("source.zip"))) };rejects { AppUpdatePolicy.release(release(asset("arm64.apk"),asset("x86.apk"))) }
        rejects { AppUpdatePolicy.release(release(asset(url="https://github.com/other/repo/releases/download/v1/a.apk"))) };rejects { AppUpdatePolicy.release(release(asset(size=0))) }
    }
    @Test fun releaseNotesCannotOverflowBackgroundWorkData() {
        val r=AppUpdatePolicy.release(release(asset()).put("body","x".repeat(20000)))
        val payload=AppUpdatePolicy.downloadPayload(r);assertTrue(payload.toByteArray().size<8192);assertFalse(JSONObject(payload).has("notes"));assertEquals(r.getString("url"),JSONObject(payload).getString("url"))
    }
    @Test fun panelRequiresRealLandscapeWidth() {
        assertEquals(0,LandscapePanelPolicy.panelWidth(1080,2200,3f,false));assertEquals(0,LandscapePanelPolicy.panelWidth(800,600,2f,true))
        assertEquals(1080,LandscapePanelPolicy.panelWidth(2400,1080,3f,true));assertEquals(0,LandscapePanelPolicy.panelWidth(1080,2400,3f,true))
    }
}
