package com.linfranca.ytdlpmobile

import org.json.JSONObject

/** GUI options compile to the same yt-dlp arguments the normal downloader already accepts. */
internal object ExtraDownloadOptions {
    fun arguments(o: JSONObject,playlist: Boolean): List<String> {
        val a=mutableListOf<String>()
        fun option(name: String,value: String) { a+=name;a+=value }
        if(o.optBoolean("resolution_on")) {
            val h=o.optString("resolution").toIntOrNull();require(h!=null && h in setOf(144,240,360,480,720,1080,1440,2160)) { "Choose a valid maximum resolution" }
            // Format sorting respects existing MP4/audio presets and caps height where available.
            option("-S","res:$h")
        }
        if(o.optBoolean("section_on")) {
            val start=o.optString("start");val end=o.optString("end")
            fun seconds(s: String): Double {
                require(s.matches(Regex("\\d+(?::[0-5]\\d){0,2}(?:\\.\\d+)?"))) { "Use seconds, MM:SS or HH:MM:SS for the section" }
                return s.split(':').fold(0.0) { v,n -> v*60+n.toDouble() }
            }
            require(seconds(end)>seconds(start)) { "Section end must be after start" }
            option("--download-sections","*$start-$end")
        }
        if(o.optBoolean("items_on")) {
            require(playlist) { "Enable playlist download before choosing playlist items" }
            val items=o.optString("items").replace(" ","")
            require(items.matches(Regex("[1-9]\\d*(?:-[1-9]\\d*)?(?:,[1-9]\\d*(?:-[1-9]\\d*)?)*"))) { "Playlist items: use 1-5,8,12" }
            option("--playlist-items",items)
        }
        if(o.optBoolean("rate_on")) {
            val rate=o.optString("rate");require(rate.toDoubleOrNull()?.let { it>0 && it.isFinite() }==true) { "Enter a positive speed limit" }
            val unit=o.optString("unit");require(unit in setOf("K","M"));option("--limit-rate",rate+unit)
        }
        if(o.optBoolean("cover")) a+="--embed-thumbnail"
        if(o.optBoolean("thumbnail")) a+="--write-thumbnail"
        if(o.optBoolean("metadata")) { a+="--embed-metadata";a+="--no-embed-info-json" }
        if(o.optBoolean("chapters")) a+="--embed-chapters" else if(o.optBoolean("metadata")) a+="--no-embed-chapters"
        if(o.optBoolean("filename_on")) {
            val name=o.optString("filename").trim()
            require(name.isNotEmpty() && name.length<=120 && !name.contains(Regex("[/\\\\\\r\\n]"))) { "Filename must be a name, without folders" }
            // Passed as an internal template extra; never allow an output path outside the session.
        }
        return a
    }
    fun template(o: JSONObject,playlist: Boolean): String? = if(o.optBoolean("filename_on"))
        (if(playlist) "%(playlist_index)05d - " else "")+o.optString("filename").trim().replace("%","%%")+" - %(id)s.%(ext)s" else null
}
