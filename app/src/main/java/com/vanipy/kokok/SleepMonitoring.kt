package com.vanipy.kokok

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import com.google.android.gms.location.SleepSegmentRequest

/** Optional SDK-gated path. It never calls the alarm scheduler, feed writer, mic or network. */
object SleepMonitoring {
    fun permission(c: Context) = Build.VERSION.SDK_INT >= 29 &&
        c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    fun unavailable(c: Context): String? {
        if (Build.VERSION.SDK_INT < 29) return "Sleep estimates need Android 10 or later. Alarms still work."
        if (!SleepStore.unlocked(c)) return "Unlock phone before enabling sleep estimates."
        return try {
            val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c)
            if (code == ConnectionResult.SUCCESS) null else "Google Play services unavailable or needs an update (code $code). Alarms unchanged."
        } catch (e: Exception) { "Sleep provider unavailable. Alarms unchanged." }
    }
    private fun callback(c: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(c, 704, Intent(c, SleepUpdateReceiver::class.java).setAction("com.vanipy.kokok.SLEEP_UPDATE"), flags)
    }
    fun register(c: Context, done: () -> Unit = {}) {
        if (!SleepStore.enabled(c)) { done(); return }
        val no = unavailable(c)
        if (no != null || !permission(c)) {
            SleepStore.status(c, no ?: "Physical activity permission not granted. Sleep updates unavailable.")
            done(); return
        }
        val generation = SleepStore.generation(c)
        try {
            ActivityRecognition.getClient(c).requestSleepSegmentUpdates(callback(c), SleepSegmentRequest.getDefaultSleepSegmentRequest())
                .addOnSuccessListener {
                    if (SleepStore.enabled(c) && SleepStore.generation(c) == generation)
                        SleepStore.status(c, "Subscribed. Waiting for provider estimates; timing and detection are not guaranteed.")
                    else if (!SleepStore.enabled(c)) unregister(c)
                    done()
                }.addOnFailureListener {
                    if (SleepStore.enabled(c) && SleepStore.generation(c) == generation)
                        SleepStore.status(c, "Subscription failed. No sleep estimate is promised. You can retry; alarms unchanged.")
                    done()
                }
        } catch (e: Exception) {
            SleepStore.status(c, "Sleep subscription unavailable. Alarms unchanged."); done()
        }
    }
    fun unregister(c: Context, done: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 29) { done(); return }
        try {
            ActivityRecognition.getClient(c).removeSleepSegmentUpdates(callback(c))
                .addOnSuccessListener { done() }
                .addOnFailureListener {
                    if (!SleepStore.enabled(c)) SleepStore.status(c, "Off locally; provider removal failed. Incoming updates are ignored. Retry stop to remove subscription.")
                    done()
                }
        } catch (e: Exception) {
            if (!SleepStore.enabled(c)) SleepStore.status(c, "Off locally; provider removal unavailable. Incoming updates are ignored.")
            done()
        }
    }
}

class SleepUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.vanipy.kokok.SLEEP_UPDATE" || !SleepStore.enabled(context) || !SleepMonitoring.permission(context)) return
        try {
            if (SleepSegmentEvent.hasEvents(intent)) for (e in SleepSegmentEvent.extractEvents(intent))
                SleepStore.segment(context, e.startTimeMillis, e.endTimeMillis, e.status)
            if (SleepClassifyEvent.hasEvents(intent)) for (e in SleepClassifyEvent.extractEvents(intent))
                SleepStore.classification(context, e.timestampMillis, e.confidence)
        } catch (e: Exception) { SleepStore.status(context, "Sleep update could not be read; no estimate invented.") }
    }
}

/** Post-unlock only, intentionally separate from direct-boot alarm recovery. */
class SleepRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_USER_UNLOCKED)) return
        AlarmBootMirror.prepare(context)
        if (!SleepStore.enabled(context)) return
        val result = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val finish = Runnable { if (finished.compareAndSet(false, true)) result.finish() }
        handler.postDelayed(finish, 8_000)
        SleepMonitoring.register(context) { handler.removeCallbacks(finish); finish.run() }
    }
}
