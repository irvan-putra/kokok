package com.vanipy.kokok

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Foreground service that owns sustained alarm ringing. A BroadcastReceiver's
 *  process is killable as soon as onReceive returns, so long-lived audio cannot
 *  live there; the foreground service keeps the process (and the ring) alive
 *  until snooze or dismiss stops it. */
class AlarmRingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kokok:ring-service").apply {
            // cap so a missed stop can never drain the battery forever
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id") ?: ""
        val label = intent?.getStringExtra("label") ?: ""
        val snooze = intent?.getIntExtra("snooze", 9) ?: 9

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            AlarmReceiver.CHANNEL, "alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(true)
            // sustained sound + vibration come from AlarmRinger, not the notification
            setSound(null, null)
            enableVibration(false)
        })

        val fullScreen = PendingIntent.getActivity(
            this, id.hashCode(),
            Intent(this, AlarmActivity::class.java)
                .putExtra("id", id).putExtra("label", label).putExtra("snooze", snooze)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val snoozePi = PendingIntent.getBroadcast(
            this, id.hashCode() xor 1,
            Intent(this, SnoozeReceiver::class.java)
                .putExtra("id", id).putExtra("snooze", snooze),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissPi = PendingIntent.getBroadcast(
            this, id.hashCode() xor 2,
            Intent(this, DismissReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = android.app.Notification.Builder(this, AlarmReceiver.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label.ifEmpty { "alarm" })
            .setContentText("ringing")
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "snooze", snoozePi)
            .addAction(0, "dismiss", dismissPi)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id.hashCode(), notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            @Suppress("DEPRECATION")
            startForeground(id.hashCode(), notif)
        }

        // the service owns the ring: process stays alive in the foreground until
        // snooze/dismiss stops the service, independent of any activity lifecycle
        AlarmRinger.start(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AlarmRinger.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
