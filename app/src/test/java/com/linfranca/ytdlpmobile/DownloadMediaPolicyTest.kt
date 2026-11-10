package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
class DownloadMediaPolicyTest {
    @Test fun pairsOnlyTheSamePostNotJustTheCdnHost() {
        val v="https://video.twimg.com/amplify_video/123/pl/avc1/720/a.m3u8"
        val a="https://video.twimg.com/amplify_video/123/pl/mp4a/128000/b.m3u8"
        assertTrue(DownloadMediaPolicy.matching(v,a))
        assertFalse(DownloadMediaPolicy.matching(v,a.replace("/123/","/999/")))
        assertTrue(DownloadMediaPolicy.audioOnly(a))
        assertTrue(DownloadMediaPolicy.matching(v,"https://pbs.twimg.com/amplify_video_thumb/123/img/a.jpg"))
    }
    @Test fun rangeChunksDoNotQualifyAsCompleteMediaAndUnknownHostsDoNotPair() {
        assertTrue(DownloadMediaPolicy.partial("https://cdn.example/v.mp4?bytestart=1&byteend=99"))
        assertFalse(DownloadMediaPolicy.partial("https://cdn.example/v.mp4?token=example"))
        assertFalse(DownloadMediaPolicy.matching("https://cdn.example/a.m3u8","https://cdn.example/b.m3u8"))
    }
}
