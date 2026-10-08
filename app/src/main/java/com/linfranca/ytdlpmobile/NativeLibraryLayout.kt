package com.linfranca.ytdlpmobile

import java.io.File
import java.io.FileInputStream

/** Finds every unpacked native dependency directory for the selected FFmpeg ABI. */
object NativeLibraryLayout {
    data class Layout(val files: List<File>) {
        val directories: List<String> = files.mapNotNull { it.parentFile?.absolutePath }.distinct()

        fun versionedCandidate(name: String): File? = files.firstOrNull {
            it.name.startsWith("$name.")
        }
    }

    fun discover(binary: File, nativeDirectory: File, packageRoots: List<File>): Layout {
        val signature = elfSignature(binary) ?: return Layout(emptyList())
        val roots = listOf(nativeDirectory) + packageRoots
        val files = roots.asSequence().filter(File::exists)
            .flatMap { it.walkTopDown().maxDepth(12).asSequence() }
            .filter { it.isFile && it.name.contains(".so") && elfSignature(it) == signature }
            .distinctBy(File::getAbsolutePath).toList()
        return Layout(files)
    }

    private fun elfSignature(file: File): List<Byte>? = runCatching {
        val header = ByteArray(20)
        FileInputStream(file).use { if (it.read(header) != header.size) return@runCatching null }
        if (!NativeMedia.magic(header, header.size, 7)) null
        else listOf(header[4], header[5], header[18], header[19])
    }.getOrNull()
}
