package com.vanipy.kokok

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.TimeZone

/**
 * Fires when the OS delivers a scheduled reminder occurrence. Default path is
 * a heads-up notification with "again in N min" and "done" actions; swiping it
 * away also means "again" (a reminder you dismiss without acting on comes
 * back). ring=true hands the occurrence to the full kokok ringer instead.
 * Delivery from an exact alarm puts the app on the temporary allowlist, so
 * starting the foreground ring service here is the supported route.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val def = ReminderStore.defs(context)[id] ?: return
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val today = ReminderModel.dateString(now, tz)
        val state = ReminderStore.state(context, id)
        if (state.doneDates.contains(today)) {
            ReminderScheduler.scheduleNext(context, def)
            return
        }
        // an "again" fire that arrives early is a duplicate, not a new occurrence
        if (state.awaitingDate == today && state.snoozeUntilMillis > now) return
        state.awaitingDate = today
        state.snoozeUntilMillis = 0
        ReminderStore.saveState(context, id, state)

        if (def.ring) ringNow(context, def, today, now) else notify(context, def, now)
        // parks the next occurrence (tomorrow or later); "again" reschedules sooner
        ReminderScheduler.scheduleNext(context, def)
    }

    private fun notify(context: Context, def: ReminderModel.Reminder, now: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "reminders", NotificationManager.IMPORTANCE_HIGH))

        val openPi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val againPi = actionIntent(context, def.id, ReminderActionReceiver.ACTION_AGAIN)
        val donePi = actionIntent(context, def.id, ReminderActionReceiver.ACTION_DONE)

        val notif = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(def.label.ifEmpty { "reminder" })
            .setContentText("again in ${def.snoozeMinutes} min, or done")
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .setWhen(now)
            .setShowWhen(true)
            .setAutoCancel(false)
            .setContentIntent(openPi)
            // swiping the notification away means "remind me again", never "done"
            .setDeleteIntent(againPi)
            .addAction(0, "again in ${def.snoozeMinutes} min", againPi)
            .addAction(0, "done", donePi)
            .build()
        nm.notify(("rem:${def.id}").hashCode(), notif)
    }

    private fun ringNow(context: Context, def: ReminderModel.Reminder, today: String, now: Long) {
        // a ringing reminder occurrence becomes a local alarm so the existing
        // ringer, snooze and dismiss flow owns it verbatim; the local flag
        // keeps it outside feed cancellation
        val occId = "rem:${def.id}@$today"
        val alarms = FeedStore.alarms(context)
        alarms[occId] = FeedStore.StoredAlarm(occId, def.label, now, def.snoozeMinutes, "fired", local = true)
        FeedStore.saveAlarms(context, alarms)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kokok:ring").acquire(60_000)
        context.startForegroundService(Intent(context, AlarmRingService::class.java)
            .putExtra("id", occId).putExtra("label", def.label).putExtra("snooze", def.snoozeMinutes))
    }

    private fun actionIntent(context: Context, id: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, ("rem:$id").hashCode() xor action.hashCode(),
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val CHANNEL = "reminders"
    }
}
