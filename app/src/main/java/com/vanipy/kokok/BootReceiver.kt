package com.vanipy.kokok

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-applies stored alarm state after boot (LOCKED_BOOT_COMPLETED arrives before
 * first unlock on FBE devices; device-protected storage makes FeedStore readable
 * then), and after wall-clock time/timezone changes. Past alarms inside the late
 * grace ring immediately, older ones are marked missed. Fired/missed/dismissed
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
        var changed = false
        for ((id, alarm) in stored.toMap()) {
            if (alarm.status != "scheduled") continue
            when {
                alarm.atMillis > now ->
                    Scheduler.schedule(context, FeedModel.Alarm(id, alarm.label, alarm.atMillis, alarm.snooze))
                now - alarm.atMillis <= FeedFormat.LATE_GRACE_MILLIS -> {
                    Scheduler.schedule(context, FeedModel.Alarm(id, alarm.label, now + 1_000, alarm.snooze))
                }
                else -> {
                    stored[id] = alarm.copy(status = "missed")
                    changed = true
                }
            }
        }
        if (changed) FeedStore.saveAlarms(context, stored)
        Sync.scheduleJob(context)
    }
}
