package com.linfranca.ytdlpmobile

import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.UUID
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object H123Probe {
    fun measure(request: YoutubeDLRequest,folder: File,stopped: ()->Boolean,process: (String?)->Unit): H123TrialPolicy.Measurement {
        val id="h123-probe-${UUID.randomUUID()}";val stop=AtomicBoolean(false);val started=SystemClock.elapsedRealtime()
        var bytes=0L;var previous=0L;var rejected=false;var unexpectedFailure=false;val spaceLimited=AtomicBoolean(false)
        val timer=Executors.newSingleThreadScheduledExecutor()
        fun halt() { stop.set(true);runCatching { YoutubeDL.destroyProcessById(id) } }
        request.addCommands(listOf("--progress-template","download:H123_PROBE:%(progress.downloaded_bytes)s","--progress-delta","0.2","--retries","0","--fragment-retries","0"))
        process(id)
        timer.scheduleAtFixedRate({
            if(folder.usableSpace<256L*1024*1024 || folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }>96L*1024*1024) spaceLimited.set(true)
            if(stopped() || stop.get() || spaceLimited.get() || SystemClock.elapsedRealtime()-started>=8000) halt()
        },250,250,TimeUnit.MILLISECONDS)
        try {
            YoutubeDL.execute(request,id,true) { _,_,line ->
                val n=Regex("H123_PROBE:(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                if(n!=null) { bytes+=if(n>=previous) n-previous else n;previous=n }
                if(line.contains("HTTP Error 429",true) || line.contains("HTTP Error 403",true) || line.startsWith("ERROR:")) { rejected=true;halt() }
                if(stopped() || bytes>=48L*1024*1024 && SystemClock.elapsedRealtime()-started>=2000) halt()
            }
        } catch(_: Exception) { unexpectedFailure=!stop.get() }
        finally { timer.shutdownNow();process(null) }
        if(stopped()) throw CancellationException("Download canceled during route trial")
        val elapsed=(SystemClock.elapsedRealtime()-started).coerceAtLeast(1)
        return H123TrialPolicy.Measurement(bytes,elapsed,!rejected && !spaceLimited.get() && !unexpectedFailure && bytes>=262144 && elapsed>=1500)
    }
}
