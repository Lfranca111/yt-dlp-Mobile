package com.linfranca.ytdlpmobile

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject

object TransferNotifications {
    fun show(c: Context,j: JSONObject,text: String,conflict: Boolean) {
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transfer_results","Downloads, history and live alerts",NotificationManager.IMPORTANCE_DEFAULT))
        val id=j.getString("id")
        fun open(action: String)=PendingIntent.getActivity(c,(id+action).hashCode(),Intent(c,TransferQueueActivity::class.java)
            .setAction("queue-$id-$action").putExtra("job",id).putExtra("queue_action",action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=NotificationCompat.Builder(c,"transfer_results").setSmallIcon(R.drawable.ic_download)
            .setContentTitle(j.optString("title","Download update")).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open("details")).setAutoCancel(true)
        if(conflict) b.addAction(0,"Record now",open("record_now")).addAction(0,"Keep queued",open("details"))
        else if(j.optString("state")=="attention") b.addAction(0,"Review / Retry",open("details"))
        runCatching { manager.notify(("transfer-$id").hashCode(),b.build()) }
    }
}
