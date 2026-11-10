package com.linfranca.ytdlpmobile

import android.content.Context

internal object TransferSettings {
    fun limit(c: Context)=c.getSharedPreferences("transfer_options",Context.MODE_PRIVATE).getInt("limit",1).coerceAtLeast(1)
    fun setLimit(c: Context,value: Int) {
        require(value>0) { "Enter a positive whole number" }
        c.getSharedPreferences("transfer_options",Context.MODE_PRIVATE).edit().putInt("limit",value).apply()
        MonitoringStore.log(c,"Concurrent media task limit changed; limit=$value; existing tasks retained")
        TransferQueue.dispatch(c)
    }
    fun configure(c: android.app.Activity) {
        val input=android.widget.EditText(c).apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(limit(c).toString()) }
        val dialog=androidx.appcompat.app.AlertDialog.Builder(c).setTitle("Simultaneous downloads / recordings")
            .setMessage("Default: 1. Enter a maximum active task count. Higher values increase bandwidth, CPU, memory, heat, battery use and storage demand. They can cause recording gaps, buffering or website connection limits. The maximum is not a guarantee your phone or network can sustain it. Live recordings take priority, then retries, then downloads. Running tasks finish when the limit is lowered. Collections run separately.")
            .setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val value=input.text.toString().toIntOrNull()
            if(value==null || value<=0) input.error="Enter a positive whole number within the supported integer range"
            else { setLimit(c,value);dialog.dismiss() }
        } };dialog.show()
    }
}
