package com.linfranca.ytdlpmobile
import org.junit.Test
import org.junit.Assert.*

class Version118EngineTest {
    private fun fresh(): Long {
        val old=EngineCheckState.snapshot()
        if(old.checking) EngineCheckState.complete(old.token,EngineCheckState.Phase.CANCELED,"","")
        return EngineCheckState.begin()!!
    }
    @Test fun checkingRequiresAnActualStartedCheck() {
        val token=fresh();assertTrue(EngineCheckState.snapshot().checking)
        EngineCheckState.complete(token,EngineCheckState.Phase.READY,"test-version","Ready")
        assertFalse(EngineCheckState.snapshot().checking)
    }
    @Test fun overlappingCheckCannotStart() {
        val token=fresh();assertNull(EngineCheckState.begin());EngineCheckState.complete(token,EngineCheckState.Phase.CANCELED,"","")
    }
    @Test fun allTerminalPathsClearChecking() {
        for(phase in listOf(EngineCheckState.Phase.READY,EngineCheckState.Phase.FAILED,EngineCheckState.Phase.TIMED_OUT,EngineCheckState.Phase.CANCELED)) {
            val token=fresh();assertTrue(EngineCheckState.complete(token,phase,"","result"));assertFalse(EngineCheckState.snapshot().checking)
        }
    }
    @Test fun lateCompletionCannotUndoTimeout() {
        val token=fresh();EngineCheckState.complete(token,EngineCheckState.Phase.TIMED_OUT,"","Timed out")
        assertFalse(EngineCheckState.complete(token,EngineCheckState.Phase.READY,"late-version","Late"))
        assertEquals(EngineCheckState.Phase.TIMED_OUT,EngineCheckState.snapshot().phase)
    }
    @Test fun oldCallbackCannotOverwriteNewCheck() {
        val old=fresh();EngineCheckState.complete(old,EngineCheckState.Phase.CANCELED,"","");val newer=EngineCheckState.begin()!!
        assertFalse(EngineCheckState.complete(old,EngineCheckState.Phase.READY,"old","old"))
        assertEquals(newer,EngineCheckState.snapshot().token);assertTrue(EngineCheckState.snapshot().checking)
        EngineCheckState.complete(newer,EngineCheckState.Phase.READY,"new","Ready")
    }
    @Test fun completedOldCheckDoesNotOverwriteNewDownloadStatusOnReopen() {
        assertFalse(EngineCheckState.shouldPresentStatus(false,"Saved 1 file",false))
        assertFalse(EngineCheckState.shouldPresentStatus(false,"Recording saved",false))
        assertTrue(EngineCheckState.shouldPresentStatus(false,"Checking yt-dlp engine…",false))
    }
    @Test fun activeMediaStatusWinsAndTimeoutCleanupCanClearItsOwnStatus() {
        assertFalse(EngineCheckState.shouldPresentStatus(true,"Downloading video",true))
        assertTrue(EngineCheckState.shouldPresentStatus(false,"Update check timed out • updater cleanup still running",false))
    }
    @Test fun recreationReadsSameProcessOwnedSnapshot() {
        val token=fresh();val first=EngineCheckState.snapshot();val recreated=EngineCheckState.snapshot()
        assertEquals(first,recreated);EngineCheckState.complete(token,EngineCheckState.Phase.READY,"same","Ready")
        assertFalse(EngineCheckState.snapshot().checking)
    }
}
