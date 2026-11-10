package com.linfranca.ytdlpmobile

/** Local input inspection limits, not network retries or permission to accept incomplete media. */
internal object MediaProbePolicy {
    const val BYTES=50_000_000
    const val ANALYZE_US=60_000_000
    val inputArguments get()=listOf("-probesize",BYTES.toString(),"-analyzeduration",ANALYZE_US.toString())
    fun postprocessorArgument()="FixupM3u8+ffmpeg_i:"+inputArguments.joinToString(" ")
    fun version(text: String): String = Regex("(?m)^ffmpeg version ([A-Za-z0-9._+~-]{1,60})").find(text)?.groupValues?.get(1) ?: "unavailable"
}
