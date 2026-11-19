package com.vanipy.kokok

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vanipy.kokok.FeedStore.feedUrl
import com.vanipy.kokok.FeedStore.lastSyncAt
import com.vanipy.kokok.FeedStore.lastSyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private val df = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()

        root.addView(text("kokok ${BuildConfig.VERSION_NAME}", 28f, Color.rgb(255, 140, 0), bold = true))
        root.addView(text("alarms set for you from the feed; they ring fully offline once applied.", 16f, Color.DKGRAY))

        root.addView(text("setup", 20f, Color.BLACK, bold = true, topPad = 32))
        root.addView(text("android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", 16f, Color.DKGRAY))

        val exactOk = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() else true
        root.addView(checkRow("exact alarms", exactOk))
        if (!exactOk && Build.VERSION.SDK_INT >= 31) {
            root.addView(button("open exact alarm settings") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            })
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val fsiOk = if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true
        root.addView(checkRow("full-screen alarm", fsiOk))
        if (!fsiOk && Build.VERSION.SDK_INT >= 34) {
            root.addView(button("open full-screen intent settings") {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")))
            })
        }

        val notifOk = if (Build.VERSION.SDK_INT >= 33) nm.areNotificationsEnabled() else true
        root.addView(checkRow("notifications", notifOk))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        root.addView(text(
            if (batteryOk) "battery optimization excluded"
            else "battery optimization active (usually fine for alarms; only change if syncs look late)",
            16f, Color.DKGRAY))
        if (!batteryOk) {
            root.addView(button("exclude from battery optimization (optional)") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            })
        }

        root.addView(text("alarms", 20f, Color.BLACK, bold = true, topPad = 32))
        val stored = FeedStore.alarms(this).values.sortedBy { it.atMillis }
        if (stored.isEmpty()) {
            root.addView(text("none. paired alarms appear here once applied.", 16f, Color.DKGRAY))
        }
        for (a in stored) {
            val mark = when (a.status) {
                "scheduled" -> "\u23F0"
                "fired" -> "\u2713 fired"
                "missed" -> "\u2717 missed"
                else -> a.status
            }
            val color = when (a.status) {
                "missed" -> Color.rgb(180, 30, 30)
                "fired", "dismissed" -> Color.GRAY
                else -> Color.BLACK
            }
            root.addView(text("$mark  ${df.format(Date(a.atMillis))}  ${a.label}", 16f, color))
        }

        root.addView(button("sync now", topPad = 24) {
            Thread {
                try {
                    val o = Sync.fetchAndApply(this)
                    runOnUiThread { render() }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                        render()
                    }
                }
            }.start()
        })
        val lastSync = if (lastSyncAt > 0) df.format(Date(lastSyncAt)) else "never"
        root.addView(text("last sync: $lastSync \u2014 $lastSyncStatus", 14f, Color.GRAY))

        root.addView(button("test alarm (rings in 60 s)", topPad = 24) {
            val id = "local-${System.currentTimeMillis()}"
            val at = System.currentTimeMillis() + 60_000
            val alarms = FeedStore.alarms(this)
            alarms[id] = FeedStore.StoredAlarm(id, "test alarm", at, 1, "scheduled", local = true)
            FeedStore.saveAlarms(this, alarms)
            Scheduler.schedule(this, FeedModel.Alarm(id, "test alarm", at, 1))
            Toast.makeText(this, "test alarm set for one minute from now", Toast.LENGTH_LONG).show()
            render()
        })

        root.addView(text("pairing", 20f, Color.BLACK, bold = true, topPad = 32))
        if (feedUrl.isEmpty()) {
            root.addView(text("not paired. your assistant sets up the feed; after that this shows the phone's public key to hand over once.", 16f, Color.DKGRAY))
            root.addView(button("show pairing key") {
                val pub = Keys.publicKeysetJson(this)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, pub)
                }
                startActivity(Intent.createChooser(send, "pairing key"))
            })
        } else {
            root.addView(text("paired.", 16f, Color.rgb(0, 120, 60)))
        }

        root.addView(button("check for update", topPad = 24) {
            Thread {
                val u = try { UpdateChecker.check(BuildConfig.VERSION_NAME) } catch (e: Exception) { null }
                runOnUiThread {
                    if (u == null) Toast.makeText(this, "no newer release found", Toast.LENGTH_SHORT).show()
                    else {
                        Toast.makeText(this, "downloading kokok ${u.version}", Toast.LENGTH_LONG).show()
                        UpdateChecker.downloadAndPromptInstall(this, u)
                    }
                }
            }.start()
        })
    }

    private fun checkRow(label: String, ok: Boolean): View =
        text(if (ok) "\u2713 $label allowed" else "\u2717 $label NOT allowed",
            16f, if (ok) Color.rgb(0, 120, 60) else Color.rgb(180, 30, 30))

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, topPad: Int = 16): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, topPad, 0, 8)
        }

    private fun button(label: String, topPad: Int = 8, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = topPad
        }
}
