package com.vanipy.kokok

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager

/** Process-wide ringing state so receivers and the activity share one player. */
object AlarmRinger {
    private var player: MediaPlayer? = null
    private var vibrator: android.os.Vibrator? = null

    fun isRinging(): Boolean = player != null

    fun start(context: Context) {
        if (player != null) return
        val uri = AlarmSound.playbackUri(context)
        try {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                // alarm audio attributes: routes to the alarm volume channel, not
                // media volume, and lets the OS treat it as an alarm
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                isLooping = true
                prepare()
                start()
                android.util.Log.i("KokokAlarmAudio", "playback started; looping=true")
            }
        } catch (e: Exception) {
            player = null // sound is best-effort; the full-screen UI still alarms
        }
        // vibration runs regardless of the sound path
        try {
            @Suppress("DEPRECATION")
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            vibrator = vib
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(longArrayOf(0, 800, 400), 0)
            }
        } catch (e: Exception) { /* best-effort */ }
    }

    fun stop() {
        player?.stop()
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
    }
}
