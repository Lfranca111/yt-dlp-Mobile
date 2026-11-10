package com.linfranca.ytdlpmobile

import org.json.JSONObject

/** Live history is never a duplicate; only outstanding work blocks a fresh capture. */
internal object LiveRecordingPolicy {
    val outstanding = setOf("queued", "held", "running", "stopping", "reserved", "finalizing")
    fun samePage(a: String, b: String): Boolean {
        val key=TransferIdentity.key(a,false)
        return key.isNotBlank() && key==TransferIdentity.key(b,false)
    }
    fun blocks(job: JSONObject, rule: String, source: String, page: String): Boolean =
        job.optBoolean("live") && job.optString("state") in outstanding &&
            ((rule.isNotBlank() && rule==job.optString("rule")) ||
                samePage(source,job.optString("source")) || samePage(page,job.optString("page")) ||
                samePage(source,job.optString("page")) || samePage(page,job.optString("source")))
    fun status(state: String): String = when(state) {
        "running", "reserved" -> "Recording already active or starting; existing task retained"
        "stopping", "finalizing" -> "Recording is stopping or saving; existing task retained"
        else -> "Recording already queued or waiting; existing task retained"
    }
}
