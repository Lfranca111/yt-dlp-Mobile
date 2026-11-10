package com.linfranca.ytdlpmobile

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class Version122CustomLiveTest {
    private fun source(mode: String="live")=JSONObject().put("schema",4).put("id","test-source").put("name","Creator").put("url","https://example.com/creator")
        .put("mode",mode).put("browserMode","mobile").put("liveLocation","page").put("liveLinkSelector","")
        .put("liveSelector","").put("liveText","").put("offlineSelector","").put("offlineText","")
        .put("pathPrefix","/video/").put("linkSelector","a.item").put("pathPattern","").put("identityKeys",JSONArray())
    private fun hls(sequence: Int,tail: String="")="#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$sequence\n#EXTINF:2,\nsegment$sequence.ts\n$tail"
    @Test fun liveSetupDoesNotRequireUploadExamplesOrAnOfflineSample() {
        val j=source().apply { remove("pathPrefix");remove("linkSelector");put("examples",JSONArray()) }
        CustomSources.validate(j)
        val state=CustomLivePolicy.state(j,"<video></video>")
        assertFalse(state.offline);assertFalse(state.online)
        assertEquals("https://example.com/creator",state.target)
    }
    @Test fun missingIndicatorIsUnknownRatherThanOffline() {
        val j=source().put("liveSelector",".status").put("liveText","LIVE")
        val state=CustomLivePolicy.state(j,"<div class=status>Loading</div>")
        assertFalse(state.online);assertFalse(state.offline)
    }
    @Test fun explicitOfflineAndConflictingStatesRemainDistinct() {
        val j=source().put("liveSelector",".on").put("liveText","LIVE").put("offlineSelector",".off").put("offlineText","OFFLINE")
        assertTrue(CustomLivePolicy.state(j,"<span class=off>OFFLINE</span>").offline)
        val conflict=CustomLivePolicy.state(j,"<span class=on>LIVE</span><span class=off>OFFLINE</span>")
        assertFalse(conflict.online);assertFalse(conflict.offline);assertNull(conflict.target)
    }
    @Test fun linkRuleFollowsNewBroadcastAddressesWithoutSavingOneExampleAddress() {
        val j=source().put("liveLocation","linked").put("liveLinkSelector","a.broadcast")
        CustomSources.validate(j)
        assertEquals("https://example.com/live/one",CustomLivePolicy.state(j,"<a class=broadcast href='/live/one'>Watch</a>").target)
        assertEquals("https://example.com/live/two",CustomLivePolicy.state(j,"<a class=broadcast href='/live/two'>Watch</a>").target)
        assertFalse(j.toString().contains("/live/one"))
    }
    @Test fun genericVideoCardsAreNarrowedByTheLearnedLiveBadge() {
        val j=source().put("liveLocation","linked").put("liveLinkSelector","a.broadcast").put("liveSelector",".status").put("liveText","LIVE")
        val html="<article><span class=status>Replay</span><a class=broadcast href='/old'>Video</a></article><article><span class=status>LIVE</span><a class=broadcast href='/now'>Watch</a></article>"
        assertEquals("https://example.com/now",CustomLivePolicy.state(j,html).target)
    }
    @Test fun ambiguousAndMissingLinksNeverPickTheFirstVideo() {
        val j=source().put("liveLocation","linked").put("liveLinkSelector","a.broadcast")
        assertNull(CustomLivePolicy.state(j,"<a class=broadcast href='/one'>1</a><a class=broadcast href='/two'>2</a>").target)
        assertNull(CustomLivePolicy.state(j,"<p>No broadcasts</p>").target)
    }
    @Test fun destinationCannotEscapeItsSourceOrStoreSignedAccess() {
        val base="https://example.com/creator"
        assertNull(CustomLivePolicy.safeTarget(base,"https://other.example/live"))
        assertNull(CustomLivePolicy.safeTarget(base,"http://example.com/live"))
        assertNull(CustomLivePolicy.safeTarget(base,"https://user:pass@example.com/live"))
        assertNull(CustomLivePolicy.safeTarget(base,"/live?token=secret"))
        assertNull(CustomLivePolicy.safeTarget(base,"/live#script"))
        assertEquals("https://example.com/live?id=123",CustomLivePolicy.safeTarget(base,"/live?id=123"))
    }
    @Test fun broadcastIdentityIsStableAndChangesWithTheDestination() {
        assertEquals(CustomLivePolicy.identity("https://example.com/live/one"),CustomLivePolicy.identity("https://example.com/live/one"))
        assertNotEquals(CustomLivePolicy.identity("https://example.com/live/one"),CustomLivePolicy.identity("https://example.com/live/two"))
        assertFalse(CustomLivePolicy.identity("https://example.com/live/one").contains("example.com"))
    }
    @Test fun aManifestNameOrStationaryPlaylistDoesNotProveLivePlayback() {
        assertFalse(CustomLivePolicy.manifestLive("https://cdn.example/master.m3u8","https://cdn.example/master.m3u8",false))
        assertFalse(CustomLivePolicy.manifestLive(hls(10),hls(10),false))
        assertTrue(CustomLivePolicy.manifestLive(hls(10),hls(11),false))
        assertFalse(CustomLivePolicy.manifestLive(hls(11),hls(10),false))
        assertTrue(CustomLivePolicy.manifestLive(hls(10),hls(10),true))
    }
    @Test fun completedAndVodPlaylistsAreRejectedEvenWithAVisibleLiveBadge() {
        assertFalse(CustomLivePolicy.manifestLive(hls(1),hls(2,"#EXT-X-ENDLIST"),true))
        assertFalse(CustomLivePolicy.manifestLive(hls(1,"#EXT-X-PLAYLIST-TYPE:VOD"),hls(2),true))
        assertFalse(CustomLivePolicy.manifestLive("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo.m3u8","#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo.m3u8",true))
    }
    @Test fun dashRequiresDynamicPlaybackAndNeverAcceptsContentProtection() {
        val first="<MPD type='dynamic' publishTime='one'></MPD>"
        val second="<MPD type='dynamic' publishTime='two'></MPD>"
        assertTrue(CustomLivePolicy.manifestLive(first,second,false))
        assertFalse(CustomLivePolicy.manifestLive(first,first,false))
        assertTrue(CustomLivePolicy.manifestLive(first,first,true))
        assertFalse(CustomLivePolicy.manifestLive("<MPD type='static'/>","<MPD type='static'/>",true))
        assertFalse(CustomLivePolicy.manifestLive(first,second.replace("</MPD>","<ContentProtection/></MPD>"),true))
    }
    @Test fun bothRequiresIndependentUploadAndLiveValidation() {
        val j=source("both")
        assertFalse(CustomSetupPolicy.ready(j))
        j.put("uploadValidated",CustomSetupPolicy.fingerprint(j,"uploads"))
        assertFalse(CustomSetupPolicy.ready(j))
        j.put("liveValidated",CustomSetupPolicy.fingerprint(j,"live"))
        assertTrue(CustomSetupPolicy.ready(j))
        j.put("liveLinkSelector","a.new-live")
        assertEquals(j.getString("uploadValidated"),CustomSetupPolicy.fingerprint(j,"uploads"))
        assertFalse(CustomSetupPolicy.ready(j))
    }
    @Test fun uploadEditsNeverInvalidateTheLiveSection() {
        val j=source("both").put("liveValidated",CustomSetupPolicy.fingerprint(source("both"),"live"))
        j.put("pathPrefix","/different/")
        assertEquals(j.getString("liveValidated"),CustomSetupPolicy.fingerprint(j,"live"))
        assertFalse(CustomSetupPolicy.ready(j))
    }
    @Test fun switchingModesRetainsTheTwoSetsOfRulesAndProofs() {
        val j=source("both").put("examples",JSONArray().put("https://example.com/video/1").put("https://example.com/video/2"))
        j.put("uploadValidated",CustomSetupPolicy.fingerprint(j,"uploads")).put("liveValidated",CustomSetupPolicy.fingerprint(j,"live"))
        val examples=j.getJSONArray("examples").toString();val live=j.getString("liveValidated")
        for(mode in listOf("uploads","live","both")) { j.put("mode",mode);assertTrue(CustomSetupPolicy.ready(j));assertEquals(examples,j.getJSONArray("examples").toString());assertEquals(live,j.getString("liveValidated")) }
    }
    @Test fun importedSourcesMustBeRetestedLocally() {
        val j=source("both").put("testedAt",123).put("uploadValidated","trusted").put("liveValidated","trusted")
        val imported=CustomSources.imported(j.toString())
        assertEquals(0,imported.getLong("testedAt"));assertEquals("",imported.getString("uploadValidated"));assertEquals("",imported.getString("liveValidated"))
        assertFalse(CustomSetupPolicy.ready(imported))
    }
    @Test fun uploadParsingIsIdenticalForLegacyAndNewRecipes() {
        val j=source("uploads")
        val html="<a class=item href='/video/1' title='First'>1</a><a class=item href='/video/2' title='Second'>2</a>"
        val modern=CustomSources.parse(j,html,false).items
        j.put("schema",3)
        val legacy=CustomSources.parse(j,html,false).items
        assertEquals(legacy,modern);assertEquals(2,modern.size)
    }
    @Test fun legacyLiveRecipesRetainTheirExplicitOfflineRequirement() {
        val j=source().put("schema",3).put("liveSelector",".status").put("liveText","LIVE")
        try { CustomSources.validate(j);fail("Legacy validation unexpectedly relaxed") } catch(_: IllegalArgumentException) {}
        j.put("offlineText","OFFLINE");CustomSources.validate(j)
        assertTrue(CustomSources.parse(j,"<p class=status>OFFLINE</p>",true).offline)
    }
    @Test fun inactiveUploadSettingsDoNotBlockLiveOnlySetup() {
        val j=source().put("linkSelector",":invalid").put("pathPrefix","").put("examples",JSONArray())
        CustomSources.validate(j)
        j.put("mode","both")
        try { CustomSources.validate(j);fail("Invalid uploads must fail when enabled") } catch(_: Exception) {}
    }
    @Test fun editingLiveRulesKeepsAnEstablishedUploadBaseline() {
        val old=source("both");val edited=JSONObject(old.toString()).put("liveSelector",".live").put("liveText","LIVE")
        val rule=MonitoringRule(name="Creator",url=old.getString("url"),mode="both",baseline=true,seen=linkedSetOf("old-upload"),liveSession="old-broadcast:123",customState=JSONObject().put("version",2).put("initialized",true))
        CustomSetupPolicy.applySourceChange(rule,old,edited)
        assertTrue(rule.baseline);assertEquals(2,rule.customState.getInt("version"));assertEquals(setOf("old-upload"),rule.seen)
        assertEquals("",rule.liveSession)
    }
    @Test fun editingUploadsKeepsPositiveLiveCompletionEvidence() {
        val old=source("both");val edited=JSONObject(old.toString()).put("pathPrefix","/new/")
        val rule=MonitoringRule(name="Creator",url=old.getString("url"),mode="both",baseline=true,customState=JSONObject().put("liveEndConfirmed",true).put("version",2))
        CustomSetupPolicy.applySourceChange(rule,old,edited)
        assertFalse(rule.baseline);assertTrue(rule.customState.getBoolean("liveEndConfirmed"))
        assertFalse(rule.customState.has("version"))
    }

    @Test fun oneLiveButtonCanBeFollowedWithoutAnAnchorUrl() {
        val j=source().put("liveLocation","linked").put("liveLinkSelector","button.go-live")
        CustomSources.validate(j)
        val html="<button class=go-live onclick='openLive()'>Watch</button>"
        assertNull(CustomLivePolicy.state(j,html).target)
        assertTrue(CustomLivePolicy.followRequired(j,html))
        assertFalse(CustomLivePolicy.followRequired(j,html+html))
    }
    @Test fun aRealAnchorUsesItsDestinationRatherThanAnExtraClick() {
        val j=source().put("liveLocation","linked").put("liveLinkSelector","a.watch")
        val html="<a class=watch href='/live/new'>Watch</a>"
        assertFalse(CustomLivePolicy.followRequired(j,html))
        assertEquals("https://example.com/live/new",CustomLivePolicy.state(j,html).target)
    }

    @Test fun uploadOnlyProofCannotAuthorizeAnUntestedLiveRule() {
        val j=source("uploads");j.put("uploadValidated",CustomSetupPolicy.fingerprint(j,"uploads"))
        assertTrue(CustomSetupPolicy.ready(j))
        assertFalse(CustomSetupPolicy.ready(JSONObject(j.toString()).put("mode","live")))
        assertFalse(CustomSetupPolicy.ready(JSONObject(j.toString()).put("mode","both")))
    }

}
