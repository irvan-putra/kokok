package com.vanipy.kokok

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.TimeZone

/** Handles the two reminder actions. "done" ends the awaiting occurrence for
 *  good; "again" (including swiping the notification away) re-fires it after
 *  the reminder's snooze minutes. */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val def = ReminderStore.defs(context)[id] ?: return
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(("rem:$id").hashCode())

        val now = System.currentTimeMillis()
        val state = ReminderStore.state(context, id)
        when (intent.action) {
            ACTION_DONE -> {
                if (state.awaitingDate.isNotEmpty()) state.doneDates.add(state.awaitingDate)
                state.awaitingDate = ""
                state.snoozeUntilMillis = 0
            }
            ACTION_AGAIN -> {
                val today = ReminderModel.dateString(now, TimeZone.getDefault())
                // only an occurrence still awaiting the user can come back
                if (state.awaitingDate == today) {
                    state.snoozeUntilMillis = now + def.snoozeMinutes * 60_000L
                }
            }
            else -> return
        }
        ReminderStore.saveState(context, id, state)
        ReminderScheduler.scheduleNext(context, def)
    }

    companion object {
        const val ACTION_DONE = "com.vanipy.kokok.REMINDER_DONE"
        const val ACTION_AGAIN = "com.vanipy.kokok.REMINDER_AGAIN"
    }
}
