package com.vanipy.kokok

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val snooze = intent.getIntExtra("snooze", 9)
        AlarmRinger.stop()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.hashCode())
        val stored = FeedStore.alarms(context)
        val alarm = stored[id] ?: return
        val at = System.currentTimeMillis() + snooze * 60_000L
        stored[id] = alarm.copy(atMillis = at, status = "scheduled")
        FeedStore.saveAlarms(context, stored)
        Scheduler.schedule(context, FeedModel.Alarm(id, alarm.label, at, snooze))
    }
}
