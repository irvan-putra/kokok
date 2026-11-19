package com.vanipy.kokok

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-applies stored alarm state after boot (LOCKED_BOOT_COMPLETED arrives before
 * first unlock on FBE devices; device-protected storage makes FeedStore readable
 * then), and after wall-clock time/timezone changes. Past-due alarms ring
 * immediately no matter how late. Fired/dismissed
 * alarms are never re-rung.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> rescheduleAll(context)
        }
    }

    private fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val stored = FeedStore.alarms(context)
        for ((id, alarm) in stored.toMap()) {
            if (alarm.status != "scheduled") continue
            if (alarm.atMillis > now) {
                Scheduler.schedule(context, FeedModel.Alarm(id, alarm.label, alarm.atMillis, alarm.snooze))
            } else {
                // past-due: ring immediately, however late
                Scheduler.schedule(context, FeedModel.Alarm(id, alarm.label, now + 1_000, alarm.snooze))
            }
        }
        Sync.scheduleJob(context)
    }
}
