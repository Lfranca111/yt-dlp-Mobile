package com.linfranca.ytdlpmobile

import android.view.View
import android.webkit.WebView
import android.widget.Toast

/** Optional, document-local presentation control for both Browser Assist screens. */
internal object BrowserPageZoom {
    fun install(webView: WebView, zoomIn: View, zoomOut: View, resetZoom: View) {
        val script by lazy(LazyThreadSafetyMode.NONE) {
            webView.resources.openRawResource(R.raw.browser_page_zoom).bufferedReader().use { it.readText().trim() }
        }
        var feedback: Toast? = null
        fun showFeedback(button: View, message: String) {
            feedback?.cancel()
            feedback = Toast.makeText(button.context, message, Toast.LENGTH_SHORT).also { it.show() }
        }
        fun attach(button: View, direction: Int) {
            button.setOnClickListener {
                val currentUrl = webView.url
                if (currentUrl?.startsWith("https://", true) == true || currentUrl?.startsWith("http://", true) == true) {
                    webView.evaluateJavascript("$script($direction)") { result ->
                        if (!button.isAttachedToWindow || webView.url != currentUrl) return@evaluateJavascript
                        val percent = result?.toIntOrNull()
                        val message = if (percent == null || percent <= 0) "Page zoom is unavailable on this page."
                            else "Page zoom: $percent%"
                        showFeedback(button, message)
                    }
                } else {
                    showFeedback(button, "Open a webpage to use the zoom controls.")
                }
            }
        }
        attach(zoomIn, 1)
        attach(zoomOut, -1)
        attach(resetZoom, 0)
    }
}
