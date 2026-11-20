package com.vanipy.kokok

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SleepMonitoringTest {
    private val c get() = RuntimeEnvironment.getApplication() as Context
    @Before fun reset() {
        SleepStore.file(c).delete()
        c.createDeviceProtectedStorageContext().getSharedPreferences("sleep-private", 0).edit().clear().commit()
    }
    @Test fun offByDefaultAndNotInAlarmStore() {
        assertFalse(SleepStore.enabled(c))
        SleepStore.setEnabled(c, true)
        assertTrue(SleepStore.enabled(c))
        assertTrue(SleepStore.file(c).canonicalPath.startsWith(c.filesDir.canonicalPath))
        assertFalse(c.createDeviceProtectedStorageContext().getSharedPreferences("sleep-private", 0).contains("enabled"))
        assertTrue(SleepStore.summary(c).contains("No sleep segment received"))
    }
    @Test fun actualCredentialProtectedCustodyPreservedWithoutMigration() {
        val dp = c
        val seed = "synthetic-existing-key"
        dp.getSharedPreferences("feed",0).edit().putString("appKeyset",seed).putLong("lastAppliedRev",42).commit()
        val alarms = mapOf("fixture" to FeedStore.StoredAlarm("fixture", "synthetic",2000000000000,9,"scheduled"))
        FeedStore.saveAlarms(dp,alarms)
        val before = dp.getSharedPreferences("feed",0).all.toMap()
        SleepStore.setEnabled(c,true)
        SleepStore.segment(c,1000,2000,0)
        SleepStore.setEnabled(c,false)
        SleepStore.clear(c)
        assertEquals(before,dp.getSharedPreferences("feed",0).all)
        assertEquals(alarms,FeedStore.alarms(c))
        assertEquals(seed,c.getSharedPreferences("feed",0).getString("appKeyset",null))
        try { SleepStore.file(c.createDeviceProtectedStorageContext());fail("device-protected sleep path accepted") } catch(expected: IllegalStateException) {}
    }
    @Test fun deduplicatesPriorDayAndRetainsThirtyWindows() {
        SleepStore.setEnabled(c, true)
        val base = System.currentTimeMillis() - 50L * 86400000
        for (i in 0..35) {
            val start = base + i * 86400000L
            SleepStore.segment(c, start, start + 3600000, 0)
            SleepStore.segment(c, start, start + 3600000, 1)
        }
        val text = SleepStore.summary(c)
        assertEquals(30, Regex("Estimated:").findAll(text).count())
        assertEquals(30, Regex("missing data near this window").findAll(text).count())
        assertTrue(text.contains("not measured sleep"))
    }
    @Test fun unavailableIsNeverZeroSleepAndBadBoundsIgnored() {
        SleepStore.setEnabled(c, true)
        SleepStore.segment(c, 0, 0, 2)
        SleepStore.segment(c, 100, 20, 0)
        SleepStore.segment(c, 100, System.currentTimeMillis() + 3600000, 0)
        assertTrue(SleepStore.summary(c).contains("not evidence of no sleep"))
        assertFalse(SleepStore.summary(c).contains("Estimated:"))
    }
    @Test fun ignoresLateCallbacksAfterStopAndClearPreservesAlarms() {
        val a = mapOf("fixture" to FeedStore.StoredAlarm("fixture", "synthetic", 2000000000000, 9, "scheduled"))
        FeedStore.saveAlarms(c, a)
        SleepStore.setEnabled(c, true)
        SleepStore.classification(c, 1000, 80)
        SleepStore.setEnabled(c, false)
        SleepStore.segment(c, 1000, 2000, 0)
        SleepStore.classification(c, 2000, 90)
        assertTrue(SleepStore.summary(c).contains("80/100"))
        assertFalse(SleepStore.summary(c).contains("Estimated:"))
        SleepStore.clear(c)
        assertFalse(SleepStore.summary(c).contains("80/100"))
        assertEquals(a, FeedStore.alarms(c))
    }
    @Test fun orderedClassificationsOnlyAndNoStageClaim() {
        SleepStore.setEnabled(c, true)
        SleepStore.classification(c, 2000, 80)
        SleepStore.classification(c, 1000, 99)
        SleepStore.classification(c, 3000, 101)
        assertTrue(SleepStore.summary(c).contains("80/100"))
        assertTrue(SleepStore.summary(c).contains("not proof of sleep"))
    }
    @Test @Config(sdk = [26, 28]) fun olderDevicesKeepAlarmsWithoutSleepProvider() {
        assertTrue(SleepMonitoring.unavailable(c)!!.contains("Alarms still work"))
        assertFalse(SleepMonitoring.permission(c))
        val a = mapOf("fixture" to FeedStore.StoredAlarm("fixture", "synthetic", 2000000000000, 9, "scheduled"))
        FeedStore.saveAlarms(c, a)
        SleepRestoreReceiver().onReceive(c, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(a, FeedStore.alarms(c))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        assertNotNull(activity)
        activity.finish()
    }
    @Test fun sleepUiRendersSyntheticUnavailableAndEstimatedStates() {
        SleepStore.setEnabled(c, true)
        SleepStore.segment(c, 1700000000000, 1700020000000, 1)
        SleepStore.status(c, "Synthetic fixture: provider unavailable; stored estimate retained.")
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible()
        val a = controller.get()
        val scroll = (a.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as android.widget.ScrollView
        val root = scroll.getChildAt(0) as ViewGroup
        val sleep = (0 until root.childCount).map { root.getChildAt(it) }
            .filterIsInstance<android.widget.TextView>().first { it.tag == "sleep-summary" }
        // Only the optional sleep section is photographed; no real alarm/sleep data.
        val start = (0 until root.childCount).first { root.getChildAt(it) is android.widget.TextView && (root.getChildAt(it) as android.widget.TextView).text == "estimated sleep (optional)" }
        val section = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24,24,24,24); setBackgroundColor(android.graphics.Color.WHITE) }
        val end = (start+1 until root.childCount).first { root.getChildAt(it) is android.widget.TextView && (root.getChildAt(it) as android.widget.TextView).text == "pairing" }
        val count = end - start
        repeat(count) { val child=root.getChildAt(start);root.removeView(child);section.addView(child) }
        for (width in listOf(960,1170,1920)) {
            section.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            section.layout(0,0,width,section.measuredHeight)
            val bitmap=Bitmap.createBitmap(width,section.height,Bitmap.Config.ARGB_8888)
            section.draw(Canvas(bitmap))
            val out=File("build/outputs/ui/sleep-synthetic-$width.png");out.parentFile.mkdirs();out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        assertTrue(sleep.text.toString().contains("Estimated:"))
        a.finish()
    }
}
