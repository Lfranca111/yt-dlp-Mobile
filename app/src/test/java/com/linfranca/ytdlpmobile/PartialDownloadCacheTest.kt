package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class PartialDownloadCacheTest {
    @Test fun recoveryIncludesAssemblyAndHistoryButNotRawSegmentsOrSessionSecrets() {
        val folder=Files.createTempDirectory("partial-recovery-test").toFile()
        try {
            fun make(name: String)=File(folder,name).apply { parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3)) }
            val current=make("episode.mp4.part")
            val old=make(".download-cache/recovery/old/episode.mp4")
            make("episode.mp4.part-Frag1");make("episode.mp4.part-Frag2.part")
            make(".download-cache/objects/cached.bin");make(".download-cache/config.json")
            make(".media-session-cookies.txt");make("browser-selection.json");make("episode.mp4.ytdl")
            assertEquals(setOf(current,old),PartialDownloadCache.recoverable(folder).toSet())
            assertTrue(PartialDownloadCache.mediaFiles(folder).isEmpty())
            assertTrue(PartialDownloadCache.privateFile(old))
            assertFalse(PartialDownloadCache.privateFile(current))
        } finally { folder.deleteRecursively() }
    }
    @Test fun normalExportDoesNotIncludeOlderPartialCopies() {
        val folder=Files.createTempDirectory("partial-export-test").toFile()
        try {
            val good=File(folder,"episode.mp4").apply { writeBytes(byteArrayOf(1)) }
            File(folder,".download-cache/recovery/a/episode.mp4").apply { parentFile!!.mkdirs();writeBytes(byteArrayOf(2)) }
            File(folder,".download-cache/exports/Partial.mkv").apply { parentFile!!.mkdirs();writeBytes(byteArrayOf(3)) }
            assertEquals(listOf(good),PartialDownloadCache.mediaFiles(folder))
        } finally { folder.deleteRecursively() }
    }
}
