package com.linfranca.ytdlpmobile

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

class DisplaySettingsActivity : DisplayActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20*dp).toInt(),(16*dp).toInt(),(20*dp).toInt(),(24*dp).toInt())
        }
        fun text(value: String, size: Float = 16f) {
            body.addView(TextView(this).apply { text=value; textSize=size; setPadding(0,(12*dp).toInt(),0,(8*dp).toInt()) })
        }
        body.addView(MaterialButton(this).apply { text="Back"; setOnClickListener { finish() } })
        text("Appearance & display",26f)
        fun choices(title: String, options: List<Pair<String,String>>, selected: String, save: (String)->Unit) {
            text(title,20f)
            val group=RadioGroup(this).apply { orientation=RadioGroup.VERTICAL }
            val keys=mutableMapOf<Int,String>()
            options.forEach { (key,label) ->
                val button=RadioButton(this).apply { id=android.view.View.generateViewId();text=label }
                keys[button.id]=key
                group.addView(button)
                if(key==selected) group.check(button.id)
            }
            group.setOnCheckedChangeListener { _, id -> keys[id]?.let(save) }
            body.addView(group)
        }
        choices("Theme",listOf("dark" to "Dark","light" to "Light","device" to "Follow device"),
            DisplaySettings.theme(this)) { DisplaySettings.setTheme(this,it) }
        choices("Screen orientation",listOf("android" to "Follow Android","portrait" to "Portrait","landscape" to "Landscape"),
            DisplaySettings.orientation(this)) { DisplaySettings.setOrientation(this,it) }
        text("Follow Android respects your device rotation setting and rotation suggestion. Websites inside Browser Assist control their own colors. Layout changes keep the current forms and browser session open.")
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
