package com.linfranca.ytdlpmobile

import java.io.File

object CompletedMediaValidator {
    fun looksLikeMedia(file: File): Boolean {
        if (file.length() < 1024L || file.name.endsWith(".part") || file.name.endsWith(".ytdl")) return false
        val ext = file.extension.lowercase()
        if (ext !in setOf("mp4", "m4v", "m4a", "webm", "mkv", "mp3", "ogg", "opus", "ts")) return false
        val header = ByteArray(16)
        val count = file.inputStream().use { it.read(header) }
        if (count < 8) return false
        val kind = when (ext) {
            "mp4", "m4v", "m4a" -> 1
            "webm", "mkv" -> 2
            "ogg", "opus" -> 3
            "mp3" -> 4
            "ts" -> 5
            else -> return false
        }
        return NativeMedia.magic(header, count, kind)
    }
}
