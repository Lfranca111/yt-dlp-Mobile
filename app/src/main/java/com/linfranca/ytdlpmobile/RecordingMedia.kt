package com.linfranca.ytdlpmobile

import java.io.File

/** Locate playable-looking stream output even if an interrupted downloader kept a temporary name. */
object RecordingMedia {
    data class Candidate(val file: File, val extension: String)

    fun choose(folder: File, interrupted: Boolean, allowFragmentedMp4: Boolean = false): Candidate? = folder.walkTopDown()
        .filter { it.isFile && it.name != "paired-stream.m3u8" && it.length() >= 65_536L }
        .mapNotNull { file -> detect(file, interrupted, allowFragmentedMp4)?.let { Candidate(file, it) } }
        .maxByOrNull { it.file.length() }

    private fun detect(file: File, interrupted: Boolean, allowFragmentedMp4: Boolean): String? {
        val header = ByteArray(564)
        val size = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (size < 376) return null
        // MPEG transport streams have a sync byte at the start of each 188-byte packet.
        // This remains playable if FFmpeg is interrupted, including with a .mp4 or .part name.
        if (NativeMedia.magic(header, size, 6)) return "ts"
        val ext = file.extension.lowercase()
        if (allowFragmentedMp4 && ext == "mp4" && CompletedMediaValidator.looksLikeMedia(file) &&
            runCatching { FragmentedMp4Recovery.completeEnd(file) > 0L }.getOrDefault(false)) return "mp4"
        if (!interrupted && ext in setOf("mp4", "m4v", "webm", "mkv") &&
            !file.name.endsWith(".part") && CompletedMediaValidator.looksLikeMedia(file)) {
            return ext
        }
        return null
    }
}
