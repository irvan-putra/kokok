package com.vanipy.kokok

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * OS alarm scheduling via setAlarmClock: exact, never batch-adjusted, fires in Doze.
 * Cancellation uses the same PendingIntent signature. Duplicate identity = alarm id.
 */
object Scheduler {

    private fun pendingIntent(context: Context, id: String, label: String, snooze: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction("com.vanipy.kokok.RING")
            .putExtra("id", id)
            .putExtra("label", label)
            .putExtra("snooze", snooze)
        return PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(context: Context, alarm: FeedModel.Alarm) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, alarm.id, alarm.label, alarm.snooze)
        val info = AlarmManager.AlarmClockInfo(alarm.atMillis, showIntent(context))
        am.setAlarmClock(info, pi)
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, id, "", 0))
    }

    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}
