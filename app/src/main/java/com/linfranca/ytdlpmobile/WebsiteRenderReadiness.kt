package com.linfranca.ytdlpmobile
internal class PageInspectionIncomplete(message: String): IllegalStateException("Page inspection incomplete: $message. Collected evidence will be assessed before normal fallback.")
/** Stable primary content, never endless ad/analytics network activity or a title alone. */
internal class WebsiteRenderReadiness {
    private var previous="";private var stable=0
    fun accept(ready: String,pending: Boolean,identity: String): Boolean {
        if(ready !in setOf("interactive","complete") || pending || identity.isBlank()) { previous="";stable=0;return false }
        if(identity==previous) stable++ else { previous=identity;stable=1 }
        return stable>=3
    }
}
