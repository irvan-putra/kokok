package com.vanipy.kokok

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager

/** Process-wide ringing state so receivers and the activity share one player. */
object AlarmRinger {
    private var player: MediaPlayer? = null

    fun start(context: Context) {
        if (player != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = MediaPlayer().apply {
            setDataSource(context, uri)
            isLooping = true
            prepare()
            start()
        }
    }

    fun stop() {
        player?.stop()
        player?.release()
        player = null
    }
}
