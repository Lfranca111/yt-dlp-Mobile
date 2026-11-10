package com.linfranca.ytdlpmobile

import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

internal class DownloadOptionsPanel(private val c: Context,private val getForm: ()->JSONObject,private val setForm: (JSONObject)->Unit) {
    val root=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL }
    private val form=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE }
    private val checks=linkedMapOf<String,CheckBox>()
    private val choices=linkedMapOf<String,Spinner>()
    private val inputs=linkedMapOf<String,EditText>()
    init {
        root.addView(Button(c).apply { text="Extra options & presets ▾";setOnClickListener { form.visibility=if(form.visibility==View.GONE) View.VISIBLE else View.GONE } });root.addView(form)
        form.addView(TextView(c).apply { text="For the normal Download button only. Browser Assist, recordings, Collections and Monitoring use their own settings. All extra options default off. Section cuts may begin near a keyframe." })
        fun check(key: String,label: String) { checks[key]=CheckBox(c).apply { text=label;form.addView(this) } }
        fun input(key: String,label: String,value: String="") { inputs[key]=EditText(c).apply { hint=label;setText(value);form.addView(this) } }
        fun choice(key: String,values: List<String>,selected: String) { choices[key]=PanelSpinner(c).apply { adapter=ArrayAdapter(c,android.R.layout.simple_spinner_dropdown_item,values);setSelection(values.indexOf(selected).coerceAtLeast(0));form.addView(this) } }
        check("resolution_on","Prefer maximum resolution");choice("resolution",listOf("144","240","360","480","720","1080","1440","2160"),"1080")
        check("section_on","Download a section");input("start","Start (HH:MM:SS)","00:00");input("end","End (HH:MM:SS)","01:00")
        check("items_on","Only selected playlist items");input("items","Playlist items, e.g. 1-5,8,12")
        check("rate_on","Limit download speed");input("rate","Speed limit","5");choice("unit",listOf("K","M"),"M")
        form.addView(TextView(c).apply { text="Speed units: K = KiB/s; M = MiB/s" })
        check("cover","Embed cover thumbnail");check("thumbnail","Save thumbnail separately");check("metadata","Embed title and creator metadata");check("chapters","Embed chapters when available")
        check("filename_on","Custom filename prefix (video ID added to avoid collisions)");input("filename","Filename prefix")
        mapOf("resolution_on" to listOf("resolution"),"section_on" to listOf("start","end"),"items_on" to listOf("items"),"rate_on" to listOf("rate","unit"),"filename_on" to listOf("filename")).forEach { (key,targets) ->
            fun enabled(on: Boolean) { targets.forEach { inputs[it]?.isEnabled=on;choices[it]?.isEnabled=on } }
            enabled(false);checks.getValue(key).setOnCheckedChangeListener { _,on->enabled(on) }
        }
        form.addView(Button(c).apply { text="Simultaneous downloads / recordings";setOnClickListener { TransferSettings.configure(c as android.app.Activity) } })
        form.addView(Button(c).apply { text="Save current settings as preset";setOnClickListener { edit(null,getForm().put("extra",snapshot())) } })
        form.addView(Button(c).apply { text="Presets: apply / edit / delete / create";setOnClickListener { choose() } })
    }
    fun snapshot()=JSONObject().also { o -> checks.forEach { (k,v)->o.put(k,v.isChecked) };inputs.forEach { (k,v)->o.put(k,v.text.toString()) };choices.forEach { (k,v)->o.put(k,v.selectedItem.toString()) } }
    fun apply(o: JSONObject) { checks.forEach { (k,v)->v.isChecked=o.optBoolean(k) };inputs.forEach { (k,v)->if(o.has(k)) v.setText(o.optString(k)) };choices.forEach { (k,v)->if(o.has(k)) v.setSelection((0 until v.adapter.count).firstOrNull { v.adapter.getItem(it).toString()==o.optString(k) } ?: 0) } }
    private val prefs get()=c.getSharedPreferences("download_presets",Context.MODE_PRIVATE)
    private fun presets()=runCatching { JSONArray(prefs.getString("items","[]")) }.getOrDefault(JSONArray())
    private fun choose() {
        val a=presets();val names=(0 until a.length()).map { a.getJSONObject(it).optString("name") }+"Create manually"
        AlertDialog.Builder(c).setTitle("Download presets").setItems(names.toTypedArray()) { _,i ->
            if(i==a.length()) edit(null,JSONObject()) else {
                val p=a.getJSONObject(i)
                AlertDialog.Builder(c).setTitle(p.optString("name")).setItems(arrayOf("Apply (does not start)","Edit","Delete")) { _,choice -> when(choice) {
                    0->{ setForm(p);apply(p.optJSONObject("extra") ?: JSONObject()) }
                    1->edit(i,p)
                    2->{ a.remove(i);prefs.edit().putString("items",a.toString()).apply() }
                } }.show()
            }
        }.show()
    }
    private fun edit(index: Int?,original: JSONObject) {
        val box=LinearLayout(c).apply { orientation=LinearLayout.VERTICAL;setPadding(24,0,24,0) }
        val name=EditText(c).apply { hint="Preset name";setText(original.optString("name"));box.addView(this) }
        val commands=EditText(c).apply { hint="Custom yt-dlp commands";setText(original.optString("commands"));box.addView(this) }
        val format=PanelSpinner(c).apply { adapter=ArrayAdapter(c,android.R.layout.simple_spinner_dropdown_item,DownloadService.PRESETS);setSelection(DownloadService.PRESETS.indexOf(original.optString("format",DownloadService.PRESET_BEST)).coerceAtLeast(0));box.addView(this) }
        val current=CheckBox(c).apply { text="Replace saved options with the current form (including extra options)";box.addView(this) }
        box.addView(TextView(c).apply { text="Keeps format, playlist/subtitle settings and extra options from the selected/current form. Credentials and webpage URLs are not saved. Custom commands must not contain cookies, tokens, passwords or authorization headers." })
        val dialog=AlertDialog.Builder(c).setTitle("Save / edit preset").setView(android.widget.ScrollView(c).apply { addView(box) }).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val command=commands.text.toString()
            if(name.text.isBlank()) { name.error="Name required";return@setOnClickListener }
            if(Regex("(?i)(cookie|password|authorization|token|api[_-]?key|--username|--proxy|--add-header|(?:^|\\s)-[up](?:\\s|$))").containsMatchIn(command)) { commands.error="Keep credentials out of presets";return@setOnClickListener }
            try { CommandTokenizer.parse(command) } catch(e: Exception) { commands.error=e.message;return@setOnClickListener }
            val p=(if(current.isChecked) getForm().put("extra",snapshot()) else JSONObject(original.toString()))
                .put("name",name.text.toString()).put("commands",command).put("format",format.selectedItem.toString())
            val a=presets();if(index==null) a.put(p) else a.put(index,p)
            prefs.edit().putString("items",a.toString()).apply();dialog.dismiss()
        } };dialog.show()
    }
}
