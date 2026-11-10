package com.linfranca.ytdlpmobile

/** Progress evidence is not a substitute for validating the saved duration. */
internal class DownloadSegmentAudit {
    private var last=0;private var total=0;private var skipped=0;private var retries=0
    @Synchronized fun newAttempt() { last=0;total=0;skipped=0;retries=0 }
    @Synchronized fun observe(line: String) {
        Regex("(?i)\\bfrag(?:ment)?\\s+(\\d+)\\s*/\\s*(\\d+)").find(line)?.let {
            last=it.groupValues[1].toIntOrNull() ?: 0;total=it.groupValues[2].toIntOrNull() ?: 0
        }
        if(line.contains("Skipping fragment",true)) skipped++
        if(line.contains("Retrying fragment",true)) retries++
    }
    @Synchronized fun fragmentsComplete()=total>0 && last==total && skipped==0
    @Synchronized fun skippedCount()=skipped
    @Synchronized fun summary()="Segment evidence: lastObservedProgress=$last/$total; skippedEvents=$skipped; retryEvents=$retries; saved duration checked separately"
    @Synchronized fun requireNoSkipped() { check(skipped==0) { "Incomplete download: unavailable media fragments were skipped; workspace retained" } }
}
