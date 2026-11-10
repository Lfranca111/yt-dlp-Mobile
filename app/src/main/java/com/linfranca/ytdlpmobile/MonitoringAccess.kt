package com.linfranca.ytdlpmobile

import android.content.Context
import android.webkit.CookieManager
import java.io.File
import java.util.UUID

/** Private per-job jar; the user's imported cookie file is never modified. */
object MonitoringAccess {
    fun requestCookie(c: Context,url: String): String {
        val imported=File(c.filesDir,"cookies/imported-cookies.txt")
        val scoped=if(imported.isFile) runCatching { WebsiteHandlerBridge.cookieHeader(imported.readLines(),url) }.getOrDefault("") else ""
        return MonitoringCookieHeader.merge(scoped,CookieManager.getInstance().getCookie(url).orEmpty())
    }
    fun observe(c: Context,url: String,stage: String,mode: String="mobile") {
        val host=runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        runCatching {
            val available=runCatching { !CookieManager.getInstance().getCookie(url).isNullOrBlank() }.getOrNull()
            MonitoringStore.log(c,"monitoring session; stage=$stage; host=$host; browserMode=$mode; browserCookiesAvailable=${available ?: "unknown"}; importedJarAvailable=${File(c.filesDir,"cookies/imported-cookies.txt").isFile}; sharedWebViewProfile=true; perTabSessionStorageNotCopied=true; agreementStateVerifiedByPage=false; values omitted")
        }
    }
    /** Match Main Download's cookie source. A shared WebView must not
     * overwrite cookies from the signed-in session the user imported. */
    internal fun downloadJar(c: Context,url: String): DownloadJar? {
        val imported=File(c.filesDir,"cookies/imported-cookies.txt")
        if(imported.isFile) return DownloadJar(MonitoringDownloadCookies.copy(imported,
            File(c.cacheDir,"monitoring-jars")), "imported")
        return cookieJar(c,url)?.let { DownloadJar(it,"browser") }
    }

    internal data class DownloadJar(val file: File,val source: String)

    fun cookieJar(c: Context,url: String): File? {
        val imported=File(c.filesDir,"cookies/imported-cookies.txt")
        val browser=CookieManager.getInstance().getCookie(url).orEmpty()
        if(!imported.isFile && browser.isBlank()) return null
        val file=File(c.cacheDir,"monitoring-jars/${UUID.randomUUID()}.txt").also { it.parentFile?.mkdirs() }
        file.writeText(if(imported.isFile) imported.readText()+"\n" else "# Netscape HTTP Cookie File\n")
        if(browser.isNotBlank()) file.appendText(WebpageAccessDiagnostic.cookieFile(url,browser).lineSequence()
            .filter { !it.startsWith("#") && it.isNotBlank() }.joinToString("\n")+"\n")
        return file
    }
}
