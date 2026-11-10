package com.linfranca.ytdlpmobile

import java.io.File

internal object MonitoringDownloadCookies {
    fun copy(imported: File, directory: File): File {
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create monitoring cookie workspace" }
        val target = File.createTempFile("download-", ".txt", directory)
        return try {
            imported.copyTo(target, overwrite = true)
            target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }
}
