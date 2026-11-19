package com.vanipy.kokok

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/** Local choice only. No write to the phone's global default or alarm stream. */
object AlarmSound {
    private fun prefs(context: Context) = context.getSharedPreferences("alarm_sound", Context.MODE_PRIVATE)
    fun selected(context: Context): Uri? = prefs(context).getString("uri", null)?.let(Uri::parse)
    fun playbackUri(context: Context): Uri = selected(context)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    fun save(context: Context, uri: Uri) {
        require(uri.scheme == "content") { "Choose a device alarm sound" }
        require(prefs(context).edit().putString("uri", uri.toString()).commit()) { "Sound could not be saved" }
    }
    fun useDefault(context: Context) { prefs(context).edit().remove("uri").commit() }
    fun label(context: Context): String {
        val uri = selected(context)
        val actual = uri ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
        val title = try { actual?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) } } catch (e: Exception) { null }
        return if (uri == null) "phone default alarm sound" + (title?.let { ": $it" } ?: "")
        else title ?: "chosen device alarm sound"
    }
}
