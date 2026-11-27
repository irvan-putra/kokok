package com.vanipy.kokok

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules reminder occurrences after boot, app update and wall-clock or
 * timezone changes. Reminders live in credential-protected storage, so this
 * receiver is deliberately not direct-boot aware: it runs after the first
 * unlock, and a reminder whose time passed while the phone stayed locked
 * fires immediately then (same-day only).
 */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.USER_UNLOCKED",
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                for (def in ReminderStore.defs(context).values) {
                    ReminderScheduler.scheduleNext(context, def)
                }
            }
        }
    }
}
