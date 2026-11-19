package com.vanipy.kokok

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        AlarmRinger.stop()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.hashCode())
        val stored = FeedStore.alarms(context)
        val alarm = stored[id] ?: return
        stored[id] = alarm.copy(status = "dismissed")
        FeedStore.saveAlarms(context, stored)
    }
}
