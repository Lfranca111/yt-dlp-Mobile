package com.linfranca.ytdlpmobile

/** A failed local mux must never trigger a second network download or bypass completion checks. */
internal object PostprocessRecoveryPolicy {
    fun failure(text: String)=text.contains("Postprocessing:",true) || text.contains("FFmpegPostProcessorError",true)
    fun eligible(text: String,fragmentsComplete: Boolean,expected: Double,missing: Int)=failure(text) && fragmentsComplete && expected.isFinite() && expected>0 && missing==0
    fun complete(expected: Double,actual: Double,video: Int,audio: Int,missing: Int)=MediaCompletion.acceptable(expected,actual) && video>0 && audio>0 && missing==0
    private val markers=linkedMapOf(
        "timestamps are unset" to "unset_timestamps","non monotonically" to "non_monotonic_timestamps","non-monotonous" to "non_monotonic_timestamps",
        "invalid data" to "invalid_media_data","invalid argument" to "invalid_argument","could not write header" to "mux_header_failed",
        "not currently supported in container" to "unsupported_container_codec","unsupported codec" to "unsupported_codec",
        "aac bitstream" to "aac_bitstream","malformed aac" to "aac_bitstream","no space left" to "storage_full",
        "permission denied" to "permission_denied","could not find codec parameters" to "codec_parameters_missing",
        "error splitting" to "bitstream_parse_failed","error while opening" to "open_failed","conversion failed" to "conversion_failed",
        "sample rate not set" to "audio_sample_rate_missing","sample rate is not set" to "audio_sample_rate_missing",
        "channels not set" to "audio_channels_missing","0 channels" to "audio_channels_missing",
        "dimensions not set" to "video_dimensions_missing","extradata" to "codec_initialization_issue",
        "incorrect codec parameters" to "incorrect_codec_parameters","could not find tag for codec" to "codec_tag_issue")
    fun markers(text: String)=markers.filterKeys { text.contains(it,true) }.values.toSet()
}
