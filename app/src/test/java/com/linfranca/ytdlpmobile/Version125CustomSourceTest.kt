package com.linfranca.ytdlpmobile

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Version125CustomSourceTest {
    private fun source(mode: String="both"): JSONObject {
        val j=JSONObject().put("schema",4).put("id","website").put("name","Website layout")
            .put("url","https://example.com/alice/videos").put("mode",mode).put("testedAt",123L)
            .put("browserMode","mobile").put("linkSelector","a.video").put("pathPrefix","/alice/video/")
            .put("pathPattern","/alice/video/*").put("identityKeys",JSONArray()).put("liveLocation","page")
            .put("examples",JSONArray().put("https://example.com/alice/video/1").put("https://example.com/alice/video/2"))
        j.put("uploadValidated",CustomSetupPolicy.fingerprint(j,"uploads")).put("liveValidated",CustomSetupPolicy.fingerprint(j,"live"))
        return j
    }
    @Test fun bindingNeverReplacesTheRequestedChannelWithTheExample() {
        val j=source();val original=j.toString()
        val bound=CustomSourceBinding.bind(j,"https://example.com/bob/videos","live")
        assertEquals("https://example.com/bob/videos",bound.getString("url"))
        assertEquals("live",bound.getString("mode"))
        assertEquals(original,j.toString())
        assertTrue(CustomSetupPolicy.ready(bound))
    }
    @Test fun uploadPathRulesRebaseToAnotherChannelWithoutChangingMatchingLogic() {
        val bound=CustomSourceBinding.bind(source(),"https://example.com/bob/videos","uploads")
        assertEquals("/bob/video/",bound.getString("pathPrefix"))
        assertEquals("/bob/video/*",bound.getString("pathPattern"))
        val result=CustomSources.parse(bound,"<a class=video href='/bob/video/3'>Bob</a><a class=video href='/alice/video/3'>Alice</a>",false)
        assertEquals(listOf("https://example.com/bob/video/3"),result.items.map { it.url })
        assertTrue(CustomSetupPolicy.ready(bound))
    }
    @Test fun explicitChannelPathsInSelectorsAlsoFollowTheNewChannel() {
        val j=source().put("liveLinkSelector","a[href^='/alice/live/']").put("linkSelector","a[href^='/alice/video/']")
        val bound=CustomSourceBinding.bind(j,"https://example.com/bob/videos","both")
        assertEquals("a[href^='/bob/live/']",bound.getString("liveLinkSelector"))
        assertEquals("a[href^='/bob/video/']",bound.getString("linkSelector"))
        assertEquals("a[href^='/alice/live/']",j.getString("liveLinkSelector"))
    }
    @Test fun originalChannelKeepsExactlyTheSameRecipe() {
        val j=source()
        val bound=CustomSourceBinding.bind(j,j.getString("url"),"both")
        assertEquals(j.toString(),bound.toString())
    }
    @Test fun commonWebsiteVideoPathsAreNotGuessedOrRewritten() {
        val j=source().put("pathPrefix","/video/").put("pathPattern","/video/*")
        assertEquals("/video/",CustomSourceBinding.bind(j,"https://example.com/bob/videos","live").getString("pathPrefix"))
    }
    @Test fun legacyUploadRecipesAlsoUseTheMonitoringChannel() {
        val j=source("uploads").put("schema",3)
        val bound=CustomSourceBinding.bind(j,"https://example.com/bob/videos","uploads")
        assertEquals("https://example.com/bob/videos",bound.getString("url"))
    }
    @Test fun itemTemplatesAndExamplesRemainOnTheNewChannel() {
        val j=source().put("urlTemplate","https://example.com/alice/video/{id}")
        val bound=CustomSourceBinding.bind(j,"https://example.com/bob/videos","uploads")
        assertEquals("https://example.com/bob/video/{id}",bound.getString("urlTemplate"))
        assertEquals("https://example.com/bob/video/1",bound.getJSONArray("examples").getString(0))
    }
    @Test fun importedOrChangedLayoutProofsAreNeverMadeTrustedByBinding() {
        val imported=CustomSources.imported(source().toString())
        val bound=CustomSourceBinding.bind(imported,"https://example.com/bob/videos","both")
        assertEquals(0L,bound.getLong("testedAt"));assertFalse(CustomSetupPolicy.ready(bound))
        val changed=source().put("liveSelector",".changed").put("liveText","LIVE")
        assertFalse(CustomSetupPolicy.ready(CustomSourceBinding.bind(changed,"https://example.com/bob/videos","live")))
    }
    @Test fun uploadsOnlyValidationDoesNotAuthorizeLiveMonitoring() {
        val j=source("uploads").put("liveValidated","")
        assertFalse(CustomSetupPolicy.ready(CustomSourceBinding.bind(j,"https://example.com/bob/videos","live")))
    }
    @Test fun websiteOriginCredentialsAndSignedParametersAreChecked() {
        for(url in listOf("https://elsewhere.com/bob","https://example.com:8443/bob","http://example.com/bob","https://user:secret@example.com/bob","https://example.com/bob?token=secret")) {
            try { CustomSourceBinding.bind(source(),url,"live");fail(url) } catch(_: IllegalArgumentException) {}
        }
    }
    @Test fun sharedSourceEditsDoNotReplaceChannelOrMode() {
        val old=source();val edited=JSONObject(old.toString()).put("url","https://example.com/new-example/videos").put("mode","live")
        val r=MonitoringRule(name="Bob",url="https://example.com/bob/videos",mode="uploads")
        CustomSetupPolicy.applySourceChange(r,old,edited)
        assertEquals("https://example.com/bob/videos",r.url);assertEquals("uploads",r.mode)
    }
    @Test fun changingOneChannelResetsItsStateWithoutTouchingAnother() {
        val first=MonitoringRule(name="First",url="https://example.com/first",baseline=true,seen=linkedSetOf("first-item"),liveSession="old",offlineChecks=2,last=100,failures=3,customState=JSONObject().put("version",2))
        val other=MonitoringRule(name="Other",url="https://example.com/other",baseline=true,seen=linkedSetOf("other-item"),liveSession="ongoing")
        CustomSourceBinding.resetChannel(first)
        assertFalse(first.baseline);assertTrue(first.seen.isEmpty());assertEquals("",first.liveSession)
        assertEquals(0,first.offlineChecks);assertEquals(0L,first.last);assertEquals(0,first.failures)
        assertEquals(setOf("other-item"),other.seen);assertTrue(other.baseline);assertEquals("ongoing",other.liveSession)
    }
    @Test fun browserDeferralsAreTemporaryUnknownRatherThanOfflineOrCaptcha() {
        for(detail in listOf("MonitoringRenderDeferred: Background browser did not settle into a verified channel view", "MonitoringRenderDeferred: Background browser timed out")) {
            assertEquals("browser",MonitoringFailure.kind(detail))
            assertTrue(MonitoringRecoveryPolicy.transientFailure(MonitoringFailure.kind(detail)))
            assertTrue(MonitoringFailure.message(detail).contains("unknown"))
        }
        assertFalse(MonitoringRecoveryPolicy.transientFailure(MonitoringFailure.kind("BrowserCheckRequired: Website interaction needed")))
        assertEquals("verification",MonitoringFailure.kind("MonitoringRenderDeferred: Are you human?"))
        assertEquals("quota",MonitoringFailure.kind("MonitoringRenderDeferred: Time limit already exhausted for foreground service type dataSync"))
    }
    @Test fun browserCheckRetriesAreBoundedAndRemainInsideTheMonitoringWindow() {
        val now=Instant.parse("2026-10-28T17:00:00Z").toEpochMilli();val days=(1..7).toSet()
        assertEquals(now+60_000,MonitoringRecoveryPolicy.nextCheck(now,15,1,"UTC",-1,-1,days,"browser"))
        assertEquals(now+120_000,MonitoringRecoveryPolicy.nextCheck(now,15,2,"UTC",-1,-1,days,"browser"))
        assertEquals(now+300_000,MonitoringRecoveryPolicy.nextCheck(now,15,3,"UTC",-1,-1,days,"browser"))
        assertEquals(MonitoringPolicy.next(now,15,4,"UTC",-1,-1,days),MonitoringRecoveryPolicy.nextCheck(now,15,4,"UTC",-1,-1,days,"browser"))
        val nearEnd=Instant.parse("2026-10-28T21:59:40Z").toEpochMilli()
        assertTrue(MonitoringPolicy.inWindow(MonitoringRecoveryPolicy.nextCheck(nearEnd,15,1,"UTC",480,1320,days,"browser"),"UTC",480,1320,days))
    }
    @Test fun anOldExtractorFailureDoesNotExhaustTheNewBrowserRetryBurst() {
        assertEquals(1,MonitoringRecoveryPolicy.checkFailures(20,"","browser"))
        assertEquals(1,MonitoringRecoveryPolicy.checkFailures(20,"network","browser"))
        assertEquals(4,MonitoringRecoveryPolicy.checkFailures(3,"browser","browser"))
        assertEquals(21,MonitoringRecoveryPolicy.checkFailures(20,"network","network"))
    }
    @Test fun browserRecoveryOnlyReusesAMatchingActiveRecording() {
        val one="https://example.com/one";val two="https://example.com/two"
        assertTrue(MonitoringBrowserRecoveryPolicy.reuse(true,true,true,one,one))
        assertFalse(MonitoringBrowserRecoveryPolicy.reuse(true,true,true,one,two))
        assertFalse(MonitoringBrowserRecoveryPolicy.reuse(true,false,true,one,one))
        assertFalse(MonitoringBrowserRecoveryPolicy.reuse(false,true,true,one,one))
        assertFalse(MonitoringBrowserRecoveryPolicy.reuse(true,true,false,one,one))
    }
}
