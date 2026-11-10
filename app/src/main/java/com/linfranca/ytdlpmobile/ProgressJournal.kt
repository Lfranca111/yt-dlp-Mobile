package com.linfranca.ytdlpmobile

/** Extract only novel lines from a rolling service buffer. Never append the whole buffer after eviction. */
internal class ProgressJournal {
    private var previous=""
    private var lastProgress=0L
    private val recent=linkedSetOf<String>()
    fun reset() { previous="";lastProgress=0;recent.clear() }
    fun accept(current: String,now: Long=System.currentTimeMillis()): String {
        if(current==previous) return ""
        val delta=when {
            previous.isEmpty() -> current
            current.startsWith(previous) -> current.substring(previous.length)
            else -> {
                // Find a suffix ending at the old buffer's end inside the new buffer.
                var overlap=0
                val tail=previous.takeLast(4096)
                for(i in tail.indices) {
                    if(i>0 && tail[i-1]!='\n') continue
                    val suffix=tail.substring(i)
                    var at=current.indexOf(suffix)
                    while(at>=0) {
                        val end=at+suffix.length
                        if((at==0 || current[at-1]=='\n') && (end==current.length || current[end]=='\n')) { overlap=end;break }
                        at=current.indexOf(suffix,at+1)
                    }
                    if(overlap>0) break
                }
                if(overlap>0) current.substring(overlap) else current.lineSequence().filter { it !in recent }.joinToString("\n")
            }
        }
        previous=current
        current.lineSequence().forEach { recent.add(it) }
        while(recent.size>4096) recent.remove(recent.first())
        return delta.lineSequence().filter { line ->
            if(!line.startsWith("[download]") || !line.contains('%')) true
            else if(line.contains("100%") || now-lastProgress>=1000) { lastProgress=now;true } else false
        }.filter { it.isNotBlank() }.joinToString("\n")
    }
}
