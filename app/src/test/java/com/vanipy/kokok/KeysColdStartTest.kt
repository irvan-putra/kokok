package com.vanipy.kokok

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.vanipy.kokok.FeedStore.appKeyset
import com.vanipy.kokok.FeedStore.lastAppliedRev
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeysColdStartTest {
    private fun views(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    @Test fun firstCryptoActionExportsOnlyPublicKeyAndKeepsItAcrossReopen() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("feed", Context.MODE_PRIVATE).edit().clear().commit()
        // Deliberately no FeedCrypto reference or Tink registration before this.
        val first = Keys.publicKeysetJson(context)
        val saved = context.appKeyset
        assertNotNull(saved)
        val obj = JSONObject(first)
        val data = obj.getJSONArray("key").getJSONObject(0).getJSONObject("keyData")
        assertEquals("ASYMMETRIC_PUBLIC", data.getString("keyMaterialType"))
        assertTrue(data.getString("typeUrl").endsWith("EciesAeadHkdfPublicKey"))
        assertFalse(first.contains("PrivateKey"))
        assertEquals(first, Keys.publicKeysetJson(context))
        assertEquals(saved, context.appKeyset)
        context.lastAppliedRev = 17
        val a = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        val all = views(a.findViewById(android.R.id.content))
        val label = all.filterIsInstance<TextView>().single { it.tag == "phone-public-key" }
        assertTrue(label.isTextSelectable)
        assertEquals(first, label.text.toString())
        all.filterIsInstance<Button>().single { it.text.toString() == "copy phone public pairing key" }.performClick()
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals(first, clip.primaryClip!!.getItemAt(0).text.toString())
        all.filterIsInstance<Button>().single { it.text.toString() == "share phone public pairing key" }.performClick()
        assertEquals(saved, context.appKeyset)
        assertEquals(17L, context.lastAppliedRev)
        a.finish()
    }

    @Test fun unreadableExistingKeyDoesNotCrashRenderOrReplacePrivateState() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("feed", Context.MODE_PRIVATE).edit().clear().commit()
        context.appKeyset = "synthetic-unreadable-state"
        val a = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        val all = views(a.findViewById(android.R.id.content))
        assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().startsWith("Public key unavailable.") })
        for (name in listOf("copy phone public pairing key", "share phone public pairing key")) {
            all.filterIsInstance<Button>().single { it.text.toString() == name }.performClick()
        }
        assertEquals("synthetic-unreadable-state", context.appKeyset)
        a.finish()
    }
}
