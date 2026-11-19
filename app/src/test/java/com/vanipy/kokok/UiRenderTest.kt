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
    private fun render(view: View, out: File, width: Int = 1080) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = maxOf(view.measuredHeight, 1200)
        view.layout(0, 0, width, h)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        view.draw(canvas)
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
        context.lastSyncStatus = "applied rev 2: 1 scheduled, 0 canceled"
        val now = System.currentTimeMillis()
        val alarms = LinkedHashMap<String, FeedStore.StoredAlarm>()
        alarms["a1"] = FeedStore.StoredAlarm("a1", "wake up", now + 43_200_000, 9, "scheduled")
        alarms["a2"] = FeedStore.StoredAlarm("a2", "old one", now - 3_600_000, 9, "fired")
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

    @Test
    fun pairingReviewRendersAtPhoneAndTabletWidths() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("feed", Context.MODE_PRIVATE).edit().clear().commit()
        val pub = org.json.JSONObject(File("src/test/resources/sign-pub.json").readText())
        val config = org.json.JSONObject().put("feedUrl", "https://example.org/feed.json")
            .put("verifyKeyset", pub).toString()
        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, config)
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).create().resume().visible().get()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        for (width in listOf(960, 1170, 1920)) {
            render(dialog.window!!.decorView, File("build/outputs/ui/pairing-review-$width.png"), width)
            // Render the whole main content, including the paste and public-key controls.
            val root = (activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView).getChildAt(0)
            render(root, File("build/outputs/ui/pairing-main-$width.png"), width)
        }
        dialog.dismiss()
        activity.finish()
    }
    @Test
    fun alarmVolumeControlDoesNotChangeUserVolume() {
        val context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, 0, 0)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        org.junit.Assert.assertEquals(android.media.AudioManager.STREAM_ALARM, activity.volumeControlStream)
        org.junit.Assert.assertEquals(0, audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM))
        fun find(view: View): android.widget.Button? {
            if (view is android.widget.Button && view.text.toString() == "adjust alarm volume") return view
            if (view is android.view.ViewGroup) for (n in 0 until view.childCount) {
                find(view.getChildAt(n))?.let { return it }
            }
            return null
        }
        val button = find(activity.findViewById(android.R.id.content))!!
        button.performClick()
        org.junit.Assert.assertEquals(0, audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM))
        audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, 3, 0)
        activity.onWindowFocusChanged(true)
        val root = (activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView).getChildAt(0)
        render(root, File("build/outputs/ui/alarm-volume-main.png"), 960)
        org.junit.Assert.assertEquals(3, audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM))
        activity.finish()
    }

    @Test
    fun manifestLauncherAndThemedMaskRender() {
        val context = RuntimeEnvironment.getApplication()
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
        val adaptive = icon as android.graphics.drawable.AdaptiveIconDrawable
        val mono = adaptive.monochrome
        assertTrue(mono != null)
        val themed = android.graphics.drawable.AdaptiveIconDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(225, 235, 220)), mono!!)
        // Themed icons tint the entire alpha silhouette, not its RGB colours.
        mono.setTint(android.graphics.Color.rgb(25, 55, 25))
        val legacy = android.graphics.drawable.BitmapDrawable(context.resources,
            android.graphics.BitmapFactory.decodeFile("src/main/res/mipmap-xxxhdpi/ic_launcher.png"))
        for ((name, drawable) in listOf("adaptive" to adaptive, "themed" to themed, "legacy" to legacy)) {
            for (size in listOf(48, 96, 192)) {
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bmp))
                val out = File("build/outputs/ui/launcher-$name-$size.png")
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(out.length() > 100)
            }
        }
    }

}
