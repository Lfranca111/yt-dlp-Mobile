package com.linfranca.ytdlpmobile
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
class H123TrialPolicyTest {
    private fun plan()=JSONObject().put("id","h123-movie-123").put("_app_trial_server",3).put("_app_trial_source","selected").put("_app_variant",":server 3").put("_app_expected_duration",1200.0)
        .put("formats",JSONArray().put(JSONObject().put("url","https://media.example/video/master.m3u8?token=one").put("height",1080).put("width",1920).put("vcodec","avc1").put("acodec","mp4a")))
    @Test fun matchingTitleEpisodeServerDurationAndVariantPass() { assertTrue(H123TrialPolicy.identityMatches(plan(),plan())) }
    @Test fun differentServerTitleEpisodeAudioOrSourceCannotWin() {
        for((key,value) in listOf("id" to "h123-tv-456-1-2","_app_trial_server" to 2,"_app_variant" to "dub:server 3","_app_trial_source" to "other")) assertFalse(H123TrialPolicy.identityMatches(plan(),plan().put(key,value)))
    }
    @Test fun trailerAndUnknownDurationAndLiveAreRejected() { for(d in listOf(0.0,30.0,600.0)) assertFalse(H123TrialPolicy.identityMatches(plan(),plan().put("_app_expected_duration",d)));assertFalse(H123TrialPolicy.identityMatches(plan(),plan().put("is_live",true))) }
    @Test fun equivalentRenditionMetadataAllowsFreshRoute() {
        val other=plan();other.getJSONArray("formats").getJSONObject(0).put("url","https://other.example/new.m3u8")
        assertTrue(H123TrialPolicy.equivalentQuality(plan(),other))
    }
    @Test fun differentResolutionAndMissingAudioAreRejected() {
        val other=plan();other.getJSONArray("formats").getJSONObject(0).put("height",720)
        assertFalse(H123TrialPolicy.equivalentQuality(plan(),other));other.getJSONArray("formats").getJSONObject(0).put("height",1080).put("acodec","none");assertFalse(H123TrialPolicy.equivalentQuality(plan(),other))
    }
    @Test fun unknownQualityNeedsSameResourceRoutes() {
        val a=plan();val b=plan();for(x in listOf(a,b)) x.getJSONArray("formats").getJSONObject(0).remove("height")
        b.getJSONArray("formats").getJSONObject(0).put("url","https://media.example/video/master.m3u8?token=refreshed")
        assertTrue(H123TrialPolicy.equivalentQuality(a,b));b.getJSONArray("formats").getJSONObject(0).put("url","https://media.example/other/master.m3u8");assertFalse(H123TrialPolicy.equivalentQuality(a,b))
    }
    @Test fun browserNeedsReliableTwentyPercentAdvantage() {
        val a=H123TrialPolicy.Measurement(1000000,2000,true)
        assertFalse(H123TrialPolicy.browserWins(a,H123TrialPolicy.Measurement(1100000,2000,true)))
        assertTrue(H123TrialPolicy.browserWins(a,H123TrialPolicy.Measurement(2000000,2000,true)))
        assertFalse(H123TrialPolicy.browserWins(a,H123TrialPolicy.Measurement(2000000,500,true)))
        assertFalse(H123TrialPolicy.browserWins(a,H123TrialPolicy.Measurement(2000000,2000,false)))
    }
    @Test fun verifiedBrowserCanRecoverFromFailedNormalSample() { assertTrue(H123TrialPolicy.browserWins(H123TrialPolicy.Measurement(0,8000,false),H123TrialPolicy.Measurement(1000000,2000,true))) }
}
