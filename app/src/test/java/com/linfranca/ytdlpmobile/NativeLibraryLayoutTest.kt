package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeLibraryLayoutTest {
    @get:Rule val temp = TemporaryFolder()

    private fun elf(file: File, machine: Int = 183) {
        file.parentFile?.mkdirs()
        val header = ByteArray(20)
        header[0] = 0x7f
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2 // 64 bit
        header[5] = 1 // little endian
        header[18] = machine.toByte()
        header[19] = (machine shr 8).toByte()
        file.writeBytes(header)
    }

    @Test fun includesAllCompatiblePackageDirectories() {
        val native = temp.newFolder("native")
        val packages = temp.newFolder("packages")
        val binary = File(native, "libffmpeg.so").also(::elf)
        val ffmpegLib = File(packages, "ffmpeg/usr/lib/libavdevice.so.61").also(::elf)
        val pythonLib = File(packages, "python/usr/lib/libexpat.so.1.9.3").also(::elf)
        val otherAbi = File(packages, "other/usr/lib/libirrelevant.so").also { elf(it, 40) }
        val layout = NativeLibraryLayout.discover(binary, native, listOf(packages))
        assertTrue(layout.directories.contains(ffmpegLib.parentFile.absolutePath))
        assertTrue(layout.directories.contains(pythonLib.parentFile.absolutePath))
        assertEquals(pythonLib, layout.versionedCandidate("libexpat.so.1"))
        assertTrue(otherAbi !in layout.files)
    }
}
