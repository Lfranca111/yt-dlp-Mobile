package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class PostprocessRecoveryPolicyTest {
    @Test fun fullFragmentTransferCanReachLocalRecoveryAfterMuxFailure() {
        val audit=DownloadSegmentAudit();audit.observe("[download] 100% (frag 2304/2304)")
        assertTrue(PostprocessRecoveryPolicy.eligible("ERROR: Postprocessing: Conversion failed!",audit.fragmentsComplete(),6806.0,0))
    }
    @Test fun incompleteOrSkippedFragmentsCannotBeCalledComplete() {
        val a=DownloadSegmentAudit();a.observe("(frag 2303/2304)");assertFalse(a.fragmentsComplete())
        a.observe("(frag 2304/2304)");a.observe("Skipping fragment 8");assertFalse(a.fragmentsComplete())
        assertFalse(PostprocessRecoveryPolicy.eligible("ERROR: Postprocessing: Conversion failed!",true,6806.0,1))
    }
    @Test fun networkFailureOrUnknownExpectedLengthDoesNotEnterFullRecovery() {
        assertFalse(PostprocessRecoveryPolicy.eligible("HTTP Error 502",true,6806.0,0))
        assertFalse(PostprocessRecoveryPolicy.eligible("ERROR: Postprocessing: Conversion failed!",true,0.0,0))
        assertFalse(PostprocessRecoveryPolicy.eligible("ERROR: Postprocessing: Conversion failed!",true,Double.NaN,0))
    }
    @Test fun shortOrSilentRecoveryCannotBeExportedAsComplete() {
        assertTrue(PostprocessRecoveryPolicy.complete(6806.0,6805.5,1,1,0))
        assertFalse(PostprocessRecoveryPolicy.complete(6806.0,54.0,1,1,0))
        assertFalse(PostprocessRecoveryPolicy.complete(6806.0,6806.0,1,0,0))
        assertFalse(PostprocessRecoveryPolicy.complete(6806.0,6806.0,0,1,0))
    }
    @Test fun nativeErrorMarkersDoNotLeakPathsOrUrls() {
        val markers=PostprocessRecoveryPolicy.markers("/private/token-secret.mp4 https://example.invalid/session-secret: Could not write header: Invalid argument; Conversion failed!")
        assertEquals(setOf("mux_header_failed","invalid_argument","conversion_failed"),markers)
        assertFalse(markers.toString().contains("secret"))
    }
    @Test fun finalizationHasItsOwnFailureMessageInsteadOfServerAdvice() {
        val f=WebsiteDownloadFailure("h123movies",false,"https://net123movies.com/movie/example-2?ydlp_server=3")
        val e=IllegalStateException("ERROR: Postprocessing: Conversion failed!");f.mediaFailed(e,e.message.orEmpty())
        val message=f.message("default",e,e.message.orEmpty())
        assertTrue(message.contains("FFmpeg"));assertFalse(message.contains("Try another server"))
    }
}
