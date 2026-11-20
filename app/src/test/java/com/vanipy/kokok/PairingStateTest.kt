package com.vanipy.kokok

import android.content.Context
import android.content.Intent
import com.vanipy.kokok.FeedStore.appKeyset
import com.vanipy.kokok.FeedStore.feedUrl
import com.vanipy.kokok.FeedStore.verifyKeyset
import com.vanipy.kokok.FeedStore.lastAppliedRev
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingStateTest {
    private fun config(): String = JSONObject()
        .put("feedUrl", "https://example.org/feed.json")
        .put("verifyKeyset", JSONObject(File("src/test/resources/sign-pub.json").readText()))
        .toString()

    @Test fun replacementKeepsRevisionPhoneKeyAndAlarmState() {
        val context = RuntimeEnvironment.getApplication()
        context.createDeviceProtectedStorageContext().getSharedPreferences("feed", Context.MODE_PRIVATE).edit().clear().commit()
        context.feedUrl = "https://example.org/old"
        context.lastAppliedRev = 42
        context.appKeyset = "synthetic-state-marker"
        FeedStore.saveAlarms(context, mapOf("local" to FeedStore.StoredAlarm("local", "synthetic", 1000, 1, "scheduled", true)))
        val before = FeedStore.alarms(context)
        assertTrue(PairingStore.install(context, PairingConfig.parse(config())))
        assertEquals(42L, context.lastAppliedRev)
        assertEquals("synthetic-state-marker", context.appKeyset)
        assertEquals(before, FeedStore.alarms(context))
        assertEquals("https://example.org/feed.json", context.feedUrl)
        // A new publisher does not get to replay an old revision.
        val f = FeedModel.parse("{\"v\":1,\"rev\":42,\"alarms\":[]}")
        try { FeedModel.apply(f, context.lastAppliedRev, FeedStore.alarms(context), 2000); fail("revision reset") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("rev")) }
    }

    @Test fun cancelShareReviewLeavesExistingPairingUntouched() {
        val context = RuntimeEnvironment.getApplication()
        context.createDeviceProtectedStorageContext().getSharedPreferences("feed", Context.MODE_PRIVATE).edit().clear().commit()
        context.feedUrl = "https://example.org/old"
        context.verifyKeyset = "old-public-placeholder"
        context.lastAppliedRev = 19
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, config())
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).create().resume().get()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertEquals("https://example.org/old", context.feedUrl)
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("https://example.org/old", context.feedUrl)
        assertEquals("old-public-placeholder", context.verifyKeyset)
        assertEquals(19L, context.lastAppliedRev)
        activity.finish()
    }
}
