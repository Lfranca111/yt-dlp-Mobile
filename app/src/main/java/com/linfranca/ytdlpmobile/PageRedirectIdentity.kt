package com.linfranca.ytdlpmobile

import java.net.URI

internal object PageRedirectIdentity {
    fun stableId(id: String,original: String,target: String,page: String=""): String {
        val a=URI(original);val b=URI(target)
        if(id.startsWith("custom-live:") && page.isNotBlank()) return CustomLivePolicy.identity(stableId(page,original,target))
        val prefix=if(id.startsWith("custom:")) "custom:" else ""
        return runCatching {
            val u=URI(id.removePrefix(prefix))
            if(u.scheme in setOf("http","https") && u.rawAuthority==b.rawAuthority)
                prefix+a.scheme+"://"+a.rawAuthority+u.rawPath.orEmpty()+(u.rawQuery?.let { "?$it" } ?: "")+(u.rawFragment?.let { "#$it" } ?: "")
            else id
        }.getOrDefault(id)
    }
}
