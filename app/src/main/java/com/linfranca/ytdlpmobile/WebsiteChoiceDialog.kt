package com.linfranca.ytdlpmobile

import android.app.Activity
import androidx.appcompat.app.AlertDialog

internal object WebsiteChoiceDialog {
    fun choose(activity: Activity,url: String,done: ()->Unit) {
        val site=WebsiteSourceResolver.site(url)
        if(site=="AniWaves") { ani(activity,url,done);return }
        val old=PlaybackChoice.get(activity,url)
        fun server(audio: String) {
            val servers=arrayOf("Automatic / first available","Server 1","Server 2","Server 3","Server 4","Server 5","Server 6")
            var selected=servers.indexOfFirst { it.equals(old.server,true) }.coerceAtLeast(0)
            AlertDialog.Builder(activity).setTitle("$site playback server")
                .setSingleChoiceItems(servers,selected) { _,i->selected=i }
                .setPositiveButton("Use selection") { _,_->
                    // Explicit automatic selection must clear a previous server preference.
                    PlaybackChoice.set(activity,url,PlaybackChoice.Choice(audio,if(selected==0) "" else servers[selected]))
                    done()
                }.setNegativeButton("Cancel",null).show()
        }
        if(site=="h123movies") {
            var audio=if(old.audio=="en") 1 else 0
            AlertDialog.Builder(activity).setTitle("h123movies audio language")
                .setSingleChoiceItems(arrayOf("Website default","English"),audio) { _,i->audio=i }
                .setPositiveButton("Choose server") { _,_->server(if(audio==1) "en" else "") }.setNegativeButton("Cancel",null).show()
        } else server("")
    }
    private fun ani(activity: Activity,url: String,done: ()->Unit) {
        val canceled=java.util.concurrent.atomic.AtomicBoolean(false)
        val loading=AlertDialog.Builder(activity).setTitle("AniWaves playback choices").setMessage("Reading the episode's audio versions and servers…")
            .setNegativeButton("Cancel") { _,_->canceled.set(true) }.setOnCancelListener { canceled.set(true) }.create()
        loading.show()
        Thread {
            val result=runCatching { WebsiteHandlerBridge.aniOptions(activity.applicationContext,url) { canceled.get() || activity.isFinishing || activity.isDestroyed } }
            activity.runOnUiThread {
                loading.dismiss()
                if(canceled.get() || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onFailure {
                    AlertDialog.Builder(activity).setTitle("Playback choices unavailable")
                        .setMessage("The episode's current server list could not be read (${WebsiteDownloadFailure.describe(it).kind}). Retry, or use the existing Browser-assisted download option.")
                        .setPositiveButton("Retry") { _,_->ani(activity,url,done) }.setNegativeButton("Cancel",null).show()
                }.onSuccess { catalog ->
                    val old=PlaybackChoice.get(activity,url)
                    var selected=catalog.groups.indexOfFirst { it.key==old.audio.ifBlank { "sub" } }.coerceAtLeast(0)
                    fun heading(title: String,detail: String)=android.widget.TextView(activity).apply { text="$title\n$detail";textSize=18f;setPadding(32,24,32,16) }
                    AlertDialog.Builder(activity).setCustomTitle(heading("AniWaves audio version",if(catalog.representative) "Options from episode ${catalog.episode}. Future episodes are checked again when downloaded." else "Current options for episode ${catalog.episode}"))
                        .setSingleChoiceItems(catalog.groups.map { it.label }.toTypedArray(),selected) { _,i->selected=i }
                        .setPositiveButton("Choose server") { _,_->
                            val group=catalog.groups[selected]
                            val servers=arrayOf("Automatic / first available",*group.servers.map { it.name }.toTypedArray())
                            var server=servers.indexOfFirst { it.equals(old.server,true) }.coerceAtLeast(0)
                            AlertDialog.Builder(activity).setCustomTitle(heading("AniWaves ${group.label} server",if(old.server.isNotBlank() && group.servers.none { it.name.equals(old.server,true) }) "The previously selected server is unavailable for this audio version. Choose a current option." else "Options read from this episode; Automatic tries the exposed servers within this audio version."))
                                .setSingleChoiceItems(servers,server) { _,i->server=i }
                                .setPositiveButton("Use selection") { _,_-> PlaybackChoice.set(activity,url,PlaybackChoice.Choice(group.key,if(server==0) "" else servers[server]));done() }
                                .setNegativeButton("Cancel",null).show()
                        }.setNegativeButton("Cancel",null).show()
                }
            }
        }.start()
    }

}
