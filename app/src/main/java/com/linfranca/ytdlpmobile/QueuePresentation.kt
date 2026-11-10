package com.linfranca.ytdlpmobile

import org.json.JSONObject

/** Structural changes rebuild history; active progress only updates its existing rows. */
internal object QueuePresentation {
    fun label(j: JSONObject,now: Long=System.currentTimeMillis()): String {
        val title=j.optString("title")
        if(j.optString("state")=="held" && j.optString("monitor").isNotBlank() && j.optInt("start_failures")>0 && j.optLong("retry_at")>0) {
            val seconds=((j.optLong("retry_at")-now).coerceAtLeast(0)+999)/1000
            val wait=if(seconds>0) "Waiting to retry preparation in ${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
                else "Preparation retry due; waiting to start"
            return "$title\n$wait\nReason: ${j.optString("detail")}"
        }
        return "$title\n${j.optString("state")} — ${j.optString("detail")}"
    }
    fun signature(jobs: List<JSONObject>): String = jobs.joinToString("\u001e") { j ->
        val active=j.optString("state") in TransferRules.activeStates
        listOf("id","state","title","live","payload","monitor","files","fragment_sample_status","retained_recovery_status","fragment_diagnostic_status","fragment_diagnostic_snapshot").joinToString("\u001f") { key -> j.opt(key)?.toString().orEmpty() }+
            if(active) "" else "\u001f"+j.optString("detail")
    }
}
