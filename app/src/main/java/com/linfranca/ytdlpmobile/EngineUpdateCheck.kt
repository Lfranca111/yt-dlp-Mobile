package com.linfranca.ytdlpmobile

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Presentation timeout is bounded. The updater retains its lock until its thread actually exits. */
internal object EngineUpdateCheck {
    private val executor=Executors.newSingleThreadExecutor()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    @Volatile var busy=false;private set
    fun recover(context: Context) {
        val c=context.applicationContext;val prefs=c.getSharedPreferences("engine_display",Context.MODE_PRIVATE)
        if(!busy && prefs.getBoolean("inflight",false)) {
            prefs.edit().putBoolean("inflight",false).apply()
            MonitoringStore.log(c,"previous engine check interrupted by process exit; stale checking presentation cleared")
        }
    }
    @Synchronized fun start(context: Context): Boolean {
        if(busy) return false
        val token=EngineCheckState.begin() ?: return false
        busy=true;val c=context.applicationContext
        c.getSharedPreferences("engine_display",Context.MODE_PRIVATE).edit().putBoolean("inflight",true).apply()
        MonitoringStore.log(c,"engine check started; token=$token; presentation timeout=90s")
        val timeout=timer.schedule({
            if(EngineCheckState.complete(token,EngineCheckState.Phase.TIMED_OUT,"","Update check timed out; installed engine remains available"))
                MonitoringStore.log(c,"engine check timed out; token=$token; background updater cleanup may continue; late result cannot replace status")
        },90,TimeUnit.SECONDS)
        executor.execute {
            try {
                YoutubeDL.init(c);FFmpeg.init(c)
                val status=YoutubeDL.updateYoutubeDL(c,YoutubeDL.UpdateChannel.NIGHTLY)
                val version=YoutubeDL.versionName(c) ?: YoutubeDL.version(c) ?: "unknown"
                c.getSharedPreferences("engine_display",Context.MODE_PRIVATE).edit().putString("version",version).apply()
                val accepted=EngineCheckState.complete(token,EngineCheckState.Phase.READY,version,"yt-dlp update: $status")
                MonitoringStore.log(c,"engine check completed; token=$token; result accepted=$accepted")
            } catch(e: Exception) {
                val phase=if(e is InterruptedException || e is java.util.concurrent.CancellationException) EngineCheckState.Phase.CANCELED else EngineCheckState.Phase.FAILED
                EngineCheckState.complete(token,phase,"","Update check unavailable; installed engine remains usable")
                MonitoringStore.log(c,"engine check failed/interrupted; token=$token; type=${e.javaClass.simpleName}; values omitted")
            } finally {
                // Even an unexpected throwable must not leave a stale CHECKING presentation.
                EngineCheckState.complete(token,EngineCheckState.Phase.CANCELED,"","Engine check ended without a completed result")
                timeout.cancel(false);c.getSharedPreferences("engine_display",Context.MODE_PRIVATE).edit().putBoolean("inflight",false).apply();busy=false }
        }
        return true
    }
}
