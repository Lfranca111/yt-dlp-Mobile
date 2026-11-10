package com.linfranca.ytdlpmobile

import android.annotation.SuppressLint
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

internal object BrowserCookieInterception {
    // WebKit 1.15.0 documents this constant for isFeatureSupported, but its
    // lint list omits it. Keep the feature check for installed WebViews.
    @SuppressLint("WrongConstant")
    fun enable(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)) {
            WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest(settings, true)
        }
    }
}
