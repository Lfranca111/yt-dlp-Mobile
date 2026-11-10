package com.linfranca.ytdlpmobile

/** A single transient snapshot is not enough to accept a rendered channel. */
internal class RenderedCheckProgress {
    private var previous: List<String>?=null
    private var count=0
    fun accept(ids: List<String>): Boolean {
        if(ids.isEmpty()) { previous=null;count=0;return false }
        count=if(ids==previous) count+1 else 1
        previous=ids.toList()
        return count>=4
    }
}
