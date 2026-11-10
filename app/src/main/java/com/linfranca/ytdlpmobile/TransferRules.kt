package com.linfranca.ytdlpmobile

import org.json.JSONObject

/** Pure scheduling rules, shared by persistence and dispatch. */
internal object TransferRules {
    val activeStates=setOf("running","stopping","reserved","finalizing")
    fun active(jobs: List<JSONObject>)=jobs.firstOrNull { it.optString("state") in activeStates }
    fun canReserve(jobs: List<JSONObject>,paused: Boolean,limit: Int=1)=!paused && jobs.count { it.optString("state") in activeStates }<limit && jobs.none { it.optString("state")=="queued" }
    fun next(jobs: List<JSONObject>,paused: Boolean,limit: Int=1): JSONObject? =
        if(paused || jobs.count { it.optString("state") in activeStates }>=limit) null
        else jobs.filter { it.optString("state")=="queued" }.minByOrNull {
            if(it.optBoolean("live")) 0 else if(it.optInt("start_failures")>0 || it.optInt("retry_count")>0) 1 else 2
        }
    fun acceptsCompletion(jobs: List<JSONObject>,id: String,lease: String=""): Boolean {
        val current=jobs.find { it.optString("id")==id && it.optString("state") in activeStates } ?: return false
        return id.isNotBlank() && current.optString("id")==id && (lease.isBlank() || current.optString("lease")==lease)
    }
    fun canPrioritize(job: JSONObject?)=job?.optBoolean("live")==true && job.optString("state") in setOf("queued","held","attention","interrupted")
    fun recover(jobs: List<JSONObject>) { jobs.filter { it.optString("state") in activeStates }.forEach { it.put("state","interrupted").put("detail","App process ended. Review and retry; recordings are never restarted silently.") } }
    fun expiredBrowser(created: Long,now: Long)=created<=0 || now-created>30_000
}
