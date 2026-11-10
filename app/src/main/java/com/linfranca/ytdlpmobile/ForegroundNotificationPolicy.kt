package com.linfranca.ytdlpmobile

/** Android foreground ownership is separate from task completion/result notifications. */
internal object ForegroundNotificationPolicy {
    enum class Action { REMOVE_LAST,HAND_OVER,KEEP_PRIMARY }
    fun removal(removed: Int,primary: Int?,remaining: Int?): Action = when {
        remaining==null -> Action.REMOVE_LAST
        primary==removed || primary==null -> Action.HAND_OVER
        else -> Action.KEEP_PRIMARY
    }
}
