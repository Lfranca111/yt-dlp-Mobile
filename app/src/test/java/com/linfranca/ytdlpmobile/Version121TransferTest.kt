package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Version121TransferTest {
    private val id="AbCdEfGh_12"
    @Test fun youtubeShareDesktopAndShortsIdentifyTheSameVideo() {
        val key=TransferIdentity.key("https://www.youtube.com/watch?v=$id&si=share-token",false)
        assertEquals(key,TransferIdentity.key("https://youtu.be/$id?t=12",false))
        assertEquals(key,TransferIdentity.key("https://m.youtube.com/shorts/$id",false))
        assertEquals(key,TransferIdentity.key("https://www.youtube.com/live/$id",false))
        assertEquals(key,TransferIdentity.key("https://www.youtube-nocookie.com/embed/$id",false))
    }
    @Test fun differentVideosAndPlaylistRequestsRemainSeparate() {
        val video="https://www.youtube.com/watch?v=$id&list=PL123"
        assertNotEquals(TransferIdentity.key(video,false),TransferIdentity.key(video,true))
        assertNotEquals(TransferIdentity.key(video,false),TransferIdentity.key("https://youtu.be/ZbCdEfGh_12",false))
        assertNotEquals(TransferIdentity.key("https://example.com/list?page=1",true),TransferIdentity.key("https://example.com/list?page=2",true))
    }
    @Test fun unknownSiteSelectorsAndSignedAccessAreNeverDiscarded() {
        assertNotEquals(TransferIdentity.key("https://example.com/player?id=1",false),TransferIdentity.key("https://example.com/player?id=2",false))
        assertNotEquals(TransferIdentity.key("https://example.com/player#one",false),TransferIdentity.key("https://example.com/player#two",false))
        assertNotEquals(TransferIdentity.key("https://cdn.example.com/video?signature=a",false),TransferIdentity.key("https://cdn.example.com/video?signature=b",false))
        assertNotEquals(TransferIdentity.key("https://notyoutube.com/watch?v=$id",false),TransferIdentity.key("https://youtube.com/watch?v=$id",false))
    }
    @Test fun identityIsPrivateAndSurvivesPersistence() {
        val key=TransferIdentity.key("https://example.com/player?password=private",false)
        assertEquals(64,key.length)
        assertFalse(key.contains("private"))
        val saved=JSONObject(JSONObject().put("duplicate_key",key).toString())
        assertEquals(key,saved.getString("duplicate_key"))
        assertEquals("",TransferIdentity.key("",false))
        assertEquals("",TransferIdentity.key("file:///private/media",false))
    }
    @Test fun restartOnlyInterruptsTheActiveLeaseAndRetainsOrderAndSnapshots() {
        val jobs=listOf(
            JSONObject().put("id","running").put("state","running").put("lease","old").put("payload","frozen-settings").put("files",JSONArray().put("saved-uri")),
            JSONObject().put("id","first").put("state","queued").put("arguments",JSONArray().put("--write-subs")),
            JSONObject().put("id","second").put("state","queued"),
            JSONObject().put("id","held").put("state","held").put("retry_at",1234567L),
            JSONObject().put("id","saved").put("state","saved"))
        val saved=JSONArray(jobs).toString()
        val restored=JSONArray(saved).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        TransferRules.recover(restored)
        assertEquals("interrupted",restored[0].getString("state"))
        assertEquals("frozen-settings",restored[0].getString("payload"))
        assertEquals("saved-uri",restored[0].getJSONArray("files").getString(0))
        assertEquals("--write-subs",restored[1].getJSONArray("arguments").getString(0))
        assertEquals("first",TransferRules.next(restored,false)!!.getString("id"))
        assertEquals(1234567L,restored[3].getLong("retry_at"))
        assertEquals("saved",restored[4].getString("state"))
        assertFalse(TransferRules.acceptsCompletion(restored,"running","old"))
    }
    @Test fun spaceBudgetsIncludeTemporaryCopiesAndNeverOverflow() {
        val gib=1024L*1024*1024
        assertEquals(StoragePolicy.UNKNOWN_START,StoragePolicy.required(0,2))
        assertEquals(2*gib+StoragePolicy.RESERVE,StoragePolicy.required(gib,2))
        assertEquals(gib+StoragePolicy.RESERVE,StoragePolicy.required(gib,1))
        assertEquals(Long.MAX_VALUE,StoragePolicy.required(Long.MAX_VALUE,2))
        assertTrue(StoragePolicy.insufficient(2*gib,StoragePolicy.required(gib,2)))
        assertFalse(StoragePolicy.insufficient(2*gib+StoragePolicy.RESERVE,StoragePolicy.required(gib,2)))
        assertFalse(StoragePolicy.insufficient(-1,gib)) // Unobservable space must not be treated as zero.
    }
    @Test fun diskFullIsNotConfusedWithNetworkOrPermissions() {
        assertTrue(StoragePolicy.noSpace("write failed: ENOSPC (No space left on device)"))
        assertTrue(StoragePolicy.noSpace("EDQUOT: disk quota exceeded"))
        assertTrue(StoragePolicy.noSpace("Not enough storage space. Free space, then retry."))
        assertFalse(StoragePolicy.noSpace("DNS lookup failed"))
        assertFalse(StoragePolicy.noSpace("EACCES: Permission denied"))
        assertFalse(StoragePolicy.noSpace("HTTP 403"))
    }
}
