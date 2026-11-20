package com.vanipy.kokok
/** Presentation only. Fired can still be ringing, so it always stays active. */
object AlarmListGrouping {
    fun isHistory(status: String): Boolean = status in setOf("dismissed", "canceled", "missed")
}
