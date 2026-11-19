package com.vanipy.kokok

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** Fires when the OS delivers a scheduled alarm: marks it fired, posts the
 *  full-screen alarm notification that brings up AlarmActivity over the lockscreen. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val label = intent.getStringExtra("label") ?: ""
        val snooze = intent.getIntExtra("snooze", 9)

        val stored = FeedStore.alarms(context)
        val alarm = stored[id] ?: return
        if (alarm.status != "scheduled") return // fired/missed/dismissed never re-ring
        stored[id] = alarm.copy(status = "fired")
        FeedStore.saveAlarms(context, stored)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kokok:ring")
        wake.acquire(60_000)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(true)
        })

        val fullScreen = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, AlarmActivity::class.java)
                .putExtra("id", id).putExtra("label", label).putExtra("snooze", snooze)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val snoozePi = PendingIntent.getBroadcast(
            context, id.hashCode() xor 1,
            Intent(context, SnoozeReceiver::class.java)
                .putExtra("id", id).putExtra("snooze", snooze),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissPi = PendingIntent.getBroadcast(
            context, id.hashCode() xor 2,
            Intent(context, DismissReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label.ifEmpty { "alarm" })
            .setContentText("ringing")
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "snooze", snoozePi)
            .addAction(0, "dismiss", dismissPi)
            .build()
        nm.notify(id.hashCode(), notif)
    }

    companion object {
        const val CHANNEL = "alarms"
    }
}
