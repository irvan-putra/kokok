package com.vanipy.kokok

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** Fires when the OS delivers a scheduled alarm: marks it fired and hands the
 *  ring to AlarmRingService, a foreground service that owns sustained audio.
 *  Exact-alarm delivery puts the app on the temporary allowlist, so starting a
 *  foreground service here is the supported route; a receiver-local player
 *  would die with the receiver's process. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val label = intent.getStringExtra("label") ?: ""
        val snooze = intent.getIntExtra("snooze", 9)

        val stored = FeedStore.alarms(context)
        val alarm = stored[id] ?: return
        if (alarm.status != "scheduled") return // fired/dismissed/canceled never re-ring
        stored[id] = alarm.copy(status = "fired")
        FeedStore.saveAlarms(context, stored)

        // bridges the gap until the service holds its own wake lock in onCreate
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kokok:ring").acquire(60_000)

        context.startForegroundService(Intent(context, AlarmRingService::class.java)
            .putExtra("id", id).putExtra("label", label).putExtra("snooze", snooze))
    }

    companion object {
        const val CHANNEL = "alarms"
    }
}
