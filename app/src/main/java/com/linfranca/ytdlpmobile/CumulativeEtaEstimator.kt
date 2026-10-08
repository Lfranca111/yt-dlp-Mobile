package com.linfranca.ytdlpmobile

import kotlin.math.roundToLong

class CumulativeEtaEstimator {
    private var startProgress = 0f
    private var startTimeMs = 0L
    private var lastProgress = 0f

    fun update(progress: Float, nowMs: Long): Long? {
        val safeProgress = progress.coerceIn(0f, 100f)
        if (safeProgress <= 0f || safeProgress >= 100f) {
            if (safeProgress >= 100f) lastProgress = safeProgress
            return if (safeProgress >= 100f) 0L else null
        }

        if (startTimeMs == 0L || safeProgress < lastProgress - RESET_THRESHOLD_PERCENT) {
            startProgress = safeProgress
            startTimeMs = nowMs
            lastProgress = safeProgress
            return null
        }

        lastProgress = safeProgress
        val elapsedSeconds = (nowMs - startTimeMs) / 1_000.0
        val downloadedPercent = safeProgress - startProgress
        if (elapsedSeconds < MIN_SAMPLE_SECONDS || downloadedPercent < MIN_PROGRESS_SAMPLE) return null

        NativeMedia.eta(safeProgress, startProgress, nowMs - startTimeMs)?.let { return it }
        val averagePercentPerSecond = downloadedPercent / elapsedSeconds
        if (averagePercentPerSecond <= 0.0) return null
        return ((100f - safeProgress) / averagePercentPerSecond).roundToLong().coerceAtLeast(0L)
    }

    companion object {
        private const val MIN_SAMPLE_SECONDS = 2.0
        private const val MIN_PROGRESS_SAMPLE = 0.2f
        private const val RESET_THRESHOLD_PERCENT = 5f
    }
}
