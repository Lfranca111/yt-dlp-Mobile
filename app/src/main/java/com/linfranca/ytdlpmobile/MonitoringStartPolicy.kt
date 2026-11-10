package com.linfranca.ytdlpmobile

import org.json.JSONObject

/** Media startup policy is separate from upload/check backoff. A saved cooldown is not a rejection. */
internal object MonitoringStartPolicy {
    fun delay(live: Boolean,kind: String,failures: Int): Long = when {
        kind=="quota" -> MonitoringRecoveryPolicy.quotaDelay(failures)
        !live -> MonitoringRecoveryPolicy.delay(failures)
        else -> when(failures) { 1 -> 15_000L;2 -> 30_000L;3 -> 60_000L;4 -> 300_000L;5 -> 900_000L;else -> 0L }
    }
    fun shortWait(due: Long,now: Long): Long = (due-now).takeIf { it in 1..60_000L } ?: 0
    fun legacySkippedAttempt(job: JSONObject): Boolean = job.optBoolean("live") &&
        job.optString("monitor").isNotBlank() && !job.optBoolean("engine_started") &&
        job.optString("state")=="held" && job.optString("detail").contains("foreground retry held until cooldown") &&
        !job.optBoolean("startup_recovered_124")
}
