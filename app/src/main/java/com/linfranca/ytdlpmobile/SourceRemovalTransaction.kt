package com.linfranca.ytdlpmobile

import org.json.JSONArray
import org.json.JSONObject

/** Never read a saved AtomicFile while its new write is open. Cleanup follows durable verification. */
internal object SourceRemovalTransaction {
    fun run(id: String,read: ()->List<JSONObject>,write: (String)->Unit,cleanup: ()->Unit) {
        val remaining=read().filterNot { it.getString("id")==id }
        val payload=JSONArray(remaining).toString()
        write(payload)
        check(read().none { it.getString("id")==id }) { "Source removal could not be verified; linked rules were retained" }
        cleanup()
    }
}
