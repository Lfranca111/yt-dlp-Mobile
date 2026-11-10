package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONException

class WebsiteDownloadFailureTest {
    @Test fun parserSyntaxFailuresAreDistinctFromRemoteServerFailuresAndDoNotLeakThePattern() {
        val r=WebsiteDownloadFailure.describe(IllegalStateException("wrapper",java.util.regex.PatternSyntaxException("private description","https://private.example/token-secret",4)))
        assertEquals("parser",r.kind);assertTrue(r.detail.contains("index 4"));assertFalse(r.detail.contains("private"));assertFalse(r.detail.contains("token-secret"))
    }

    private fun ani(browser: Boolean=false)=WebsiteDownloadFailure("AniWaves",browser,"https://aniwaves.ru/watch/show/ep-1?audio=dub&server=Vidplay")
    @Test fun serialModeIsLimitedToNormalAniDownloads() {
        assertTrue(ani().serialAni);assertFalse(ani(true).serialAni)
        assertFalse(WebsiteDownloadFailure("h123movies",false,"").serialAni)
        assertFalse(WebsiteDownloadFailure(null,false,"").serialAni)
    }
    @Test fun browserCapturedFailuresKeepTheirExistingMessage() {
        val failure=ani(true)
        failure.preparationFailed(WebsiteDownloadFailure.Reason("access","blocked"))
        failure.mediaFailed(IllegalStateException(),"HTTP Error 429")
        assertEquals("existing browser status",failure.message("existing browser status",Exception(),"HTTP Error 429"))
    }
    @Test fun aniRateFailureDirectsToExistingAssistWithoutUnsupportedWebpageAdvice() {
        val f=ani();f.mediaFailed(IllegalStateException(),"HTTP Error 429: Too Many Requests")
        val message=f.message("Unsupported URL",Exception(),"HTTP Error 429")
        assertTrue(message.contains("HTTP 429"));assertTrue(message.contains("existing Browser-assisted"))
        assertTrue(message.contains("SUB/DUB"));assertFalse(message.contains("Unsupported URL"))
    }
    @Test fun selectedMovieServerAndOriginalCauseAreKept() {
        val f=WebsiteDownloadFailure("h123movies",false,"https://h123movies.com/movie/fixture?ydlp_server=1")
        f.preparationFailed(WebsiteDownloadFailure.Reason("unavailable","The selected server did not report available playback"))
        val message=f.message("Unsupported URL",Exception(),"Unsupported URL")
        assertTrue(message.contains("Server 1"));assertTrue(message.contains("did not report available playback"))
        assertTrue(message.contains("Try another server"));assertFalse(message.contains("Unsupported URL"))
    }
    @Test fun autoSelectionDoesNotPretendASpecificServerWasSelected() {
        val f=WebsiteDownloadFailure("h123movies",false,"https://h123movies.com/movie/fixture?ydlp_server=auto")
        f.preparationFailed(WebsiteDownloadFailure.Reason("media-list","No playable media entry"))
        assertFalse(f.message("default",Exception(),"").contains("Server 1"))
    }
    @Test fun unrelatedDownloadsKeepTheirExistingStatus() {
        assertEquals("existing",WebsiteDownloadFailure(null,false,"https://example.test").message("existing",Exception(),""))
        assertEquals("existing",WebsiteDownloadFailure("WCOStream",false,"https://m.wcostream.tv/episode").message("existing",Exception(),""))
    }
    @Test fun wrappedJsonFailureKeepsItsUsefulClassificationWithoutItsPayload() {
        val reason=WebsiteDownloadFailure.describe(IllegalStateException("handler failed",JSONException("private-token=https://example.test/secret")))
        assertEquals("response",reason.kind);assertFalse(reason.detail.contains("private-token"));assertFalse(reason.detail.contains("https://"))
    }
    @Test fun unknownExceptionMessagesDoNotLeakSignedUrlsOrCredentials() {
        val reason=WebsiteDownloadFailure.describe(IllegalArgumentException("signed=https://example.test/a?key=private-secret"))
        assertFalse(reason.detail.contains("private-secret"));assertFalse(reason.detail.contains("https://"))
        assertTrue(reason.detail.contains("IllegalArgumentException"))
    }
    @Test fun onlyTransientNormalAniMediaFailuresGetTheExtraTransportRetry() {
        assertTrue(ani().shouldRetryTransport("HTTP Error 503"))
        assertTrue(ani().shouldRetryTransport("connection reset"))
        assertFalse(ani().shouldRetryTransport("HTTP Error 429"))
        assertFalse(ani().shouldRetryTransport("HTTP Error 403"))
        assertFalse(ani().shouldRetryTransport("HTTP Error 503; Unsupported URL:"))
        assertFalse(ani(true).shouldRetryTransport("connection reset"))
    }
    @Test fun longServerWaitIsStillExplainedInTheHandoff() {
        val message=ani().message("old",Exception(),"HTTP Error 429",true)
        assertTrue(message.contains("longer wait"));assertTrue(message.contains("no early retry"));assertTrue(message.contains("Browser-assisted"))
    }
    @Test fun invalidServerHintIsNotCopiedIntoTheStatus() {
        val f=WebsiteDownloadFailure("h123movies",false,"https://h123movies.com/movie/x?ydlp_server=private-token")
        f.preparationFailed(WebsiteDownloadFailure.Reason("unavailable","Server unavailable"))
        assertFalse(f.message("old",Exception(),"").contains("private-token"))
    }
    @Test fun mediaFailureSurvivesAnUnsupportedFallback() {
        val f=WebsiteDownloadFailure("h123movies",false,"https://h123movies.com/movie/x?ydlp_server=2")
        f.mediaFailed(IllegalStateException(),"HTTP Error 403: Forbidden")
        val message=f.message("Unsupported URL",Exception(),"Unsupported URL")
        assertTrue(message.contains("Server 2"));assertTrue(message.contains("HTTP 403"));assertFalse(message.contains("Unsupported URL"))
    }
}
