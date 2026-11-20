package com.vanipy.kokok

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.vanipy.kokok.FeedStore.appKeyset
import com.vanipy.kokok.FeedStore.lastAppliedRev
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmSoundTest {
    private fun views(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun defaultRemainsUntilExplicitChoiceAndDoesNotChangeAlarmState() {
        val c = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("alarm_sound", Context.MODE_PRIVATE).edit().clear().commit()
        val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val def = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM)
        c.appKeyset = java.io.File("src/test/resources/app-priv.json").readText()
        c.lastAppliedRev = 2
        val key = c.appKeyset
        FeedStore.saveAlarms(c, mapOf("tomorrow" to FeedStore.StoredAlarm("tomorrow", "synthetic", 9999999999999, 9, "scheduled")))
        val alarms = FeedStore.alarms(c)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible()
        val a = controller.get()
        fun click(name: String) = views(a.findViewById(android.R.id.content)).filterIsInstance<Button>().single { it.text.toString() == name }.performClick()
        assertNull(AlarmSound.selected(c))
        click("choose alarm sound")
        val request = shadowOf(a).nextStartedActivityForResult
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, request.intent.action)
        assertEquals(RingtoneManager.TYPE_ALARM, request.intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, 0))
        assertFalse(request.intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
        shadowOf(a).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
        assertNull(AlarmSound.selected(c))
        click("choose alarm sound")
        val next = shadowOf(a).nextStartedActivityForResult
        val chosen = Uri.parse("content://media/internal/audio/media/42")
        shadowOf(a).receiveResult(next.intent, Activity.RESULT_OK, Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, chosen))
        assertEquals(chosen, AlarmSound.selected(c))
        assertEquals(chosen, AlarmSound.playbackUri(c))
        controller.pause().resume()
        assertEquals(chosen, AlarmSound.selected(c))
        click("use phone default alarm sound")
        assertNull(AlarmSound.selected(c))
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AlarmSound.playbackUri(c))
        click("preview alarm sound (up to 10 s)")
        click("stop sound preview")
        assertEquals(vol, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(def, RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM))
        assertEquals(key, c.appKeyset)
        assertEquals(2L, c.lastAppliedRev)
        assertEquals(alarms, FeedStore.alarms(c))
        a.finish()
    }
    @Test fun refusesUnexpectedUriWithoutReplacingCurrentChoice() {
        val c = RuntimeEnvironment.getApplication()
        val chosen = Uri.parse("content://media/internal/audio/media/24")
        AlarmSound.save(c, chosen)
        try { AlarmSound.save(c, Uri.parse("https://example.org/sound")); fail("network sound accepted") } catch (expected: IllegalArgumentException) {}
        assertEquals(chosen, AlarmSound.selected(c))
        AlarmSound.useDefault(c)
    }
}
