package com.linfranca.ytdlpmobile

import android.content.Context
import android.net.*
import java.util.concurrent.Executors

/** Condition-change recovery is opportunistic; saved retry alarms/work remain the durable path. */
internal object MonitoringNetworkRecovery {
    private val io=Executors.newSingleThreadExecutor()
    private var registered=false
    @Synchronized fun initialize(c: Context) {
        if(registered) return
        val app=c.applicationContext
        val manager=app.getSystemService(ConnectivityManager::class.java)
        try {
            manager.registerDefaultNetworkCallback(object: ConnectivityManager.NetworkCallback() {
                private var usable=false
                @Synchronized override fun onCapabilitiesChanged(n: Network,caps: NetworkCapabilities) {
                    val current=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val restored=current && !usable
                    usable=current
                    if(restored) io.execute { runCatching { recover(app) }.onFailure { MonitoringStore.log(app,"network recovery unavailable; ${MonitoringStore.error(it)}") } }
                }
                @Synchronized override fun onLost(n: Network) { usable=false }
            })
            registered=true
        } catch(e: Exception) { MonitoringStore.log(app,"network observation unavailable; saved retries remain active; ${e.javaClass.simpleName}") }
    }
    private fun recover(c: Context) {
        MonitoringPowerDiagnostics.record(c,"validated network available")
        val now=System.currentTimeMillis()
        MonitoringStore.rules(c).filter { it.enabled && it.failures in 1..3 &&
            it.customState.optString("recoveryKind") in setOf("dns","network") &&
            MonitoringPolicy.inWindow(now+2000,it.zone,it.start,it.end,it.days) }.forEach { rule ->
            // Keep the failure budget; a network transition must never reset a retry burst.
            MonitoringStore.mutate(c,rule.id) { if(it.generation==rule.generation) it.next=minOf(it.next.takeIf { due -> due>0 } ?: Long.MAX_VALUE,now+2000) }
            MonitoringStore.get(c,rule.id)?.let { MonitoringScheduler.ensure(c,it,true) }
            MonitoringStore.log(c,"network restored; pending failed check brought forward; rule=${rule.id}; failures=${rule.failures}")
        }
        TransferQueue.retryBlockedMonitoring(c,setOf("dns","network"))
        TransferQueue.dispatch(c)
    }
}
