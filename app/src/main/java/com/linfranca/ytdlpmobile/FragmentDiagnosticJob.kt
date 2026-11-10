package com.linfranca.ytdlpmobile

import org.json.JSONObject

/** An inspection borrows the existing lease, without replacing the original task history. */
internal object FragmentDiagnosticJob {
    private const val SNAPSHOT="fragment_diagnostic_snapshot"
    fun pending(job: JSONObject)=job.has(SNAPSHOT)
    fun operation(job: JSONObject)=job.optString("fragment_retained_action","diagnostics")
    fun begin(job: JSONObject,payload: String,now: Long,operation: String="diagnostics") {
        check(!pending(job)) { "A diagnostic export is already queued" }
        check(job.optString("state") !in setOf("running","stopping","reserved","finalizing","queued","held")) { "Stop the task before inspecting its files" }
        require(operation in setOf("diagnostics","repair","samples"))
        val snapshot=JSONObject(job.toString())
        job.put(SNAPSHOT,snapshot).put("fragment_retained_action",operation).put("payload",payload).put("state","queued").put("created",now)
            .put("detail",if(operation=="repair") "Waiting for local video recovery; original media and cache kept" else if(operation=="samples") "Waiting to export sample fragments; original media and cache kept" else "Waiting to export fragment diagnostics; retained media will not be changed")
    }
    fun restore(job: JSONObject,status: String) {
        val snapshot=job.optJSONObject(SNAPSHOT) ?: return
        val operation=operation(job)
        val files=job.optJSONArray("files")
        val events=job.optString("events")
        job.keys().asSequence().toList().forEach { job.remove(it) }
        snapshot.keys().forEach { job.put(it,snapshot.get(it)) }
        if(files!=null) job.put("files",files)
        job.put("events",events).put(when(operation) { "repair"->"retained_recovery_status";"samples"->"fragment_sample_status";else->"fragment_diagnostic_status" },status)
    }
}
