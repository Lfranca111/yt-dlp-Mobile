package com.linfranca.ytdlpmobile

import java.util.concurrent.atomic.AtomicBoolean

/** One instance per job. Never replaces selected media or resets the retry budget on fallback. */
internal class DownloadRateLimitRecovery {
    private val stopped = AtomicBoolean(false)
    var retries = 0
        private set
    var encountered = false
        private set
    fun beginAttempt() { stopped.set(false) }
    fun observe(line: String): Boolean = DownloadFailurePolicy.rateLimited(line) && stopped.compareAndSet(false, true)
    fun requireNotLimited() { check(!stopped.get()) { "HTTP Error 429: downloader attempt stopped" } }
    fun wasLimited(text: String) = stopped.get() || DownloadFailurePolicy.rateLimited(text)
    private var retryNotBefore=0L
    var serverWaitTooLong=false
        private set
    fun respectRetryAfter(epochMs: Long) { retryNotBefore=maxOf(retryNotBefore,epochMs) }
    fun message()=if(serverWaitTooLong) "The media server requested a longer Retry-After wait. No early retry was made; the incomplete workspace was retained. Try again after the server's requested wait." else MESSAGE
    fun nextDelay(text: String, now: Long=System.currentTimeMillis()): Long? {
        if (!wasLimited(text)) return null
        encountered = true
        if (retries >= 2) return null
        val delay=maxOf(if(retries==0) 30_000L else 60_000L,if(retryNotBefore>now) retryNotBefore-now else 0L)
        if(delay>300_000L) { serverWaitTooLong=true;return null }
        retries++
        return delay
    }
    companion object {
        const val MESSAGE = "The media server is limiting requests (HTTP 429). Automatic recovery was exhausted; incomplete media was not exported. Wait before trying again, or select another server."
        /** Poll cancellation while retaining the queue lease; no new media requests during this wait. */
        fun await(delayMs: Long, running: () -> Boolean, sleep: (Long) -> Unit = Thread::sleep) {
            var remaining = delayMs
            while (remaining > 0) {
                check(running()) { "Download stopped" }
                val step = minOf(remaining, 250L)
                sleep(step)
                remaining -= step
            }
            check(running()) { "Download stopped" }
        }
    }
}
