package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Version117PolicyTest {
    private fun recipe()=JSONObject().put("schema",3).put("id","source").put("name","Creator")
        .put("url","https://example.com/creator/alex").put("mode","uploads").put("linkSelector","a[href]")
        .put("pathPrefix","/video/").put("pathPattern","/video/*")
    @Test fun legacyRecipesStillValidateWithoutBrowserMode() { CustomSources.validate(recipe()) }
    @Test fun modeIsPortableButImportedVerificationIsReset() {
        val r=CustomSources.imported(recipe().put("browserMode","desktop").put("testedAt",10).put("browserOnly",false).toString())
        assertEquals("desktop",r.getString("browserMode"));assertEquals(0,r.getLong("testedAt"));assertTrue(r.getBoolean("browserOnly"))
    }
    @Test(expected=IllegalArgumentException::class) fun unsupportedModeIsRejected() { CustomSources.validate(recipe().put("browserMode","arbitrary-script")) }
    @Test fun desktopAgentDoesNotLeakMobileTokenAndMobileKeepsDefault() {
        assertFalse(MonitoringSession.agent("desktop","default mobile").contains("Mobile"))
        assertEquals("default mobile",MonitoringSession.agent("mobile","default mobile"))
    }
    @Test(expected=IllegalArgumentException::class) fun exportedAuthenticationHintsAreNotAcceptedInRecipes() {
        CustomSources.validate(recipe().put("accountMarker","private account hint"))
    }
    @Test fun browserModeDoesNotChangeDiscoveredItemIdentity() {
        val html="<a href='/video/123'>Example</a>"
        val j=recipe()
        val mobile=CustomSources.parse(j.put("browserMode","mobile"),html,false)
        val desktop=CustomSources.parse(j.put("browserMode","desktop"),html,false)
        assertEquals(mobile.items.map { it.id },desktop.items.map { it.id })
    }
}
