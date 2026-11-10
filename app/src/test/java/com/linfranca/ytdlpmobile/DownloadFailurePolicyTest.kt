package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class DownloadFailurePolicyTest {
    @Test fun unsupportedDoesNotRetryFragments() {
        val error="HTTP Error 403: Forbidden\nRetrying fragment 1\nERROR: Unsupported URL: example"
        assertTrue(DownloadFailurePolicy.unsupported(error))
        assertFalse(DownloadFailurePolicy.parallelRetry(error,true))
    }
    @Test fun pageAccessIsNotAFragmentFailure() {
        assertFalse(DownloadFailurePolicy.parallelRetry("ERROR: Unable to download webpage: HTTP Error 403: Forbidden",true))
    }
    @Test fun actualFragmentsMayRetryOnceWithoutParallelism() {
        val error="[download] Got error: HTTP Error 403: Forbidden. Retrying fragment 3 (1/10)"
        assertTrue(DownloadFailurePolicy.parallelRetry(error,true))
        assertFalse(DownloadFailurePolicy.parallelRetry(error,false))
        assertTrue(DownloadFailurePolicy.parallelRetry("Unable to download fragment 2: connection reset",true))
    }
    @Test fun identifiersAreNotHttpStatus() {
        assertFalse(DownloadFailurePolicy.access("Downloading video id abc403def"))
        assertTrue(DownloadFailurePolicy.access("HTTP Error 403: Forbidden"))
        assertTrue(DownloadFailurePolicy.access("website HTTP; status=403; values omitted"))
    }
    @Test fun attemptBuffersAreIndependentAndBounded() {
        val old=DownloadAttemptLog();old.append("HTTP Error 403: Forbidden")
        val current=DownloadAttemptLog(80);repeat(50) { current.append("diagnostics") }
        current.append("ERROR: Unsupported URL: example")
        val failure=current.failure(IllegalStateException("extractor failed"))
        assertFalse(failure.contains("403"))
        assertTrue(DownloadFailurePolicy.unsupported(failure))
        assertTrue(failure.length<=80+1+"extractor failed".length)
    }
}
