package com.linfranca.ytdlpmobile

object DownloadState {
    @Volatile var running = false
    @Volatile var operation = OPERATION_IDLE
    @Volatile var progress = 0
    @Volatile var status = "Ready • Saves to Downloads/yt-dlp Mobile"
    @Volatile var log = "Waiting for a link…"
    @Volatile var recording403RecoveryAvailable = false
    @Volatile var recordingPageUrl = ""
    @Volatile var recordingVideoUrl = ""
    @Volatile var recordingAudioUrl = ""
    @Volatile var browserVisible = false
    @Volatile var browserResumed = false
    @Volatile var browserVisibilityChangedAt = 0L

    const val OPERATION_IDLE = "idle"
    const val OPERATION_DOWNLOAD = "download"
    const val OPERATION_RECORD = "record"
}
