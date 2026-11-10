package com.linfranca.ytdlpmobile

import android.content.res.ColorStateList

/** Queries enabled colors with state_enabled present; an empty query matches -state_enabled. */
internal object PaletteStateLists {
    fun recolor(old: ColorStateList, transform: (Int) -> Int): ColorStateList {
        val enabled = android.R.attr.state_enabled
        val states = arrayOf(
            intArrayOf(-enabled),
            intArrayOf(enabled, android.R.attr.state_pressed),
            intArrayOf(enabled, android.R.attr.state_checked),
            intArrayOf(enabled, android.R.attr.state_selected),
            intArrayOf(enabled, android.R.attr.state_focused),
            intArrayOf()
        )
        val colors = states.map { state ->
            val query = if (state.isEmpty()) intArrayOf(enabled) else state
            transform(old.getColorForState(query, old.defaultColor))
        }.toIntArray()
        return ColorStateList(states, colors)
    }
}
