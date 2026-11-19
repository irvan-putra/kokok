package com.vanipy.kokok

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.vanipy.kokok.FeedStore.feedUrl
import com.vanipy.kokok.FeedStore.lastSyncAt
import com.vanipy.kokok.FeedStore.lastSyncStatus
import com.vanipy.kokok.FeedStore.verifyKeyset
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRenderTest {
    private fun render(view: View, out: File) {
        val width = 1080
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = maxOf(view.measuredHeight, 1200)
        view.layout(0, 0, width, h)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun mainActivityRendersUnpaired() {
        val context = RuntimeEnvironment.getApplication()
        context.feedUrl = ""
        context.verifyKeyset = ""
        FeedStore.saveAlarms(context, emptyMap())
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        val out = File("build/outputs/ui/main-unpaired.png")
        render(activity.findViewById(android.R.id.content), out)
        assertTrue(out.length() > 10_000)
    }

    @Test
    fun mainActivityRendersWithAlarms() {
        val context = RuntimeEnvironment.getApplication()
        context.feedUrl = "https://example.test/feed"
        context.verifyKeyset = "{}"
        context.lastSyncAt = System.currentTimeMillis()
        context.lastSyncStatus = "applied rev 2: 1 scheduled, 0 canceled, 0 missed"
        val now = System.currentTimeMillis()
        val alarms = LinkedHashMap<String, FeedStore.StoredAlarm>()
        alarms["a1"] = FeedStore.StoredAlarm("a1", "wake up", now + 43_200_000, 9, "scheduled")
        alarms["a2"] = FeedStore.StoredAlarm("a2", "old one", now - 3_600_000, 9, "missed")
        FeedStore.saveAlarms(context, alarms)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        val out = File("build/outputs/ui/main-paired.png")
        render(activity.findViewById(android.R.id.content), out)
        assertTrue(out.length() > 10_000)
    }

    @Test
    fun alarmActivityRendersRinging() {
        val context = RuntimeEnvironment.getApplication()
        val alarms = LinkedHashMap<String, FeedStore.StoredAlarm>()
        alarms["a1"] = FeedStore.StoredAlarm("a1", "wake up",
            System.currentTimeMillis(), 9, "fired")
        FeedStore.saveAlarms(context, alarms)
        val intent = android.content.Intent(context, AlarmActivity::class.java).putExtra("id", "a1")
        val activity = Robolectric.buildActivity(AlarmActivity::class.java, intent).create().visible().get()
        val out = File("build/outputs/ui/alarm-ringing.png")
        render(activity.findViewById(android.R.id.content), out)
        assertTrue(out.length() > 5_000)
    }
}
