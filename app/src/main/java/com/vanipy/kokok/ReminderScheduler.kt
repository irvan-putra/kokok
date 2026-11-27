package com.vanipy.kokok

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.TimeZone

/**
 * Exact scheduling for reminder occurrences. Reminders deliberately do not
 * use setAlarmClock: they must not claim the status-bar "next alarm" slot.
 * setExactAndAllowWhileIdle still fires exactly and in Doze under the app's
 * USE_EXACT_ALARM grant. One PendingIntent per reminder id keeps repeated
 * reschedules idempotent.
 */
object ReminderScheduler {

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction("com.vanipy.kokok.REMINDER")
            .putExtra("id", id)
        return PendingIntent.getBroadcast(
            context, ("rem:$id").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun scheduleNext(context: Context, def: ReminderModel.Reminder) {
        val state = ReminderStore.state(context, def.id)
        val at = ReminderModel.nextFireMillis(
            def, state, System.currentTimeMillis(), TimeZone.getDefault())
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (at < 0) {
            am.cancel(pendingIntent(context, def.id))
            return
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, def.id))
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, id))
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(("rem:$id").hashCode())
    }

    /**
     * Applies the feed's reminder list: reminders absent from the feed are
     * canceled and forgotten (their done history is kept for replay safety);
     * every current reminder is (re)scheduled. Returns (active, removed).
     */
    fun applyFeed(context: Context, feedReminders: List<ReminderModel.Reminder>): Pair<Int, Int> {
        val defs = ReminderStore.defs(context)
        val feedIds = HashSet<String>()
        for (def in feedReminders) feedIds.add(def.id)
        var removed = 0
        for (id in defs.keys.toList()) {
            if (id !in feedIds) {
                cancel(context, id)
                defs.remove(id)
                removed++
            }
        }
        for (def in feedReminders) {
            val prev = defs[def.id]
            if (prev == null || !prev.sameAs(def)) defs[def.id] = def
        }
        ReminderStore.saveDefs(context, defs)
        for (def in defs.values) scheduleNext(context, def)
        return Pair(defs.size, removed)
    }
}
