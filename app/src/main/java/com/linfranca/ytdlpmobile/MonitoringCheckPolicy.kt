package com.linfranca.ytdlpmobile

/** A routine check never launches browser UI. Browser-rendered checks run in a bounded worker; only the explicit browser button launches UI. */
internal object MonitoringCheckPolicy {
    enum class Route { BACKGROUND, BROWSER_RENDERED }
    fun route(custom: Boolean,browserOnly: Boolean)=if(custom && browserOnly) Route.BROWSER_RENDERED else Route.BACKGROUND
}
