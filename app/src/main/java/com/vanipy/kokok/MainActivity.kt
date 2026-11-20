package com.vanipy.kokok

import android.app.AlertDialog
import android.widget.EditText
import com.vanipy.kokok.FeedStore.verifyKeyset
import com.vanipy.kokok.FeedStore.lastAppliedRev
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
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

    private var soundPreview: android.media.MediaPlayer? = null
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopPreviewTask = Runnable { stopSoundPreview() }
    private val soundPickerRequest = 37
    private lateinit var root: LinearLayout
    private var alarmVolumeLabel: TextView? = null
    private val volumeObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshAlarmVolume() }
    }
    private val df = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmBootMirror.prepare(this)
        // Physical volume keys target alarm volume only while this screen is active.
        volumeControlStream = AudioManager.STREAM_ALARM
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }
        scroll.addView(root)
        setContentView(scroll)
        handlePairingShare(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
        // Android's volume panel need not take window focus. Observe settings so
        // the visible level stays current while the user changes it there.
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    override fun onPause() {
        stopSoundPreview()
        contentResolver.unregisterContentObserver(volumeObserver)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingShare(intent)
    }

    private val sleepPermissionRequest = 42
    private fun sleepRefresh() { runOnUiThread { if (!isFinishing && !isDestroyed) render() } }
    private fun enableSleep() {
        val no = SleepMonitoring.unavailable(this)
        if (no != null) { Toast.makeText(this, no, Toast.LENGTH_LONG).show(); return }
        if (!SleepMonitoring.permission(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), sleepPermissionRequest)
            return
        }
        SleepStore.setEnabled(this, true)
        SleepMonitoring.register(this) { sleepRefresh() }
        render()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == sleepPermissionRequest) {
            if (SleepMonitoring.permission(this)) enableSleep()
            else { SleepStore.status(this, "Off. Physical activity permission declined; alarms unchanged."); render() }
        }
    }
    private fun renderSleep() {
        root.addView(text("estimated sleep (optional)", 20f, Color.BLACK, bold = true, topPad = 32))
        root.addView(text("Google Play services uses phone light and movement to estimate sleep. Stillness in the dark is not proof of sleep. No REM, stages or medical measurement. No mic, camera, location permission or alarm-time changes. This adds a Google provider dependency, not a blanket promise about its data practices.", 14f, Color.DKGRAY))
        if (!SleepStore.unlocked(this)) {
            root.addView(text("Unlock phone to view or change sleep estimates.", 16f, Color.DKGRAY))
            return
        }
        val no = SleepMonitoring.unavailable(this)
        if (no != null) root.addView(text(no, 16f, Color.DKGRAY))
        if (SleepStore.enabled(this) && !SleepMonitoring.permission(this))
            SleepStore.status(this, "Physical activity permission missing. Sleep updates unavailable; stored estimates retained.")
        root.addView(text(SleepStore.summary(this), 14f, Color.DKGRAY).apply { setTextIsSelectable(true); tag = "sleep-summary" })
        if (no == null) root.addView(button(if (SleepStore.enabled(this)) "retry sleep subscription" else "enable sleep estimates") {
            if (SleepStore.enabled(this)) SleepMonitoring.register(this) { sleepRefresh() }
            else AlertDialog.Builder(this).setTitle("Enable estimated sleep?")
                .setMessage("Android asks for Physical activity permission. Google Play services estimates sleep from phone signals; it may return missing or delayed data. Kokok stores up to 30 windows privately after unlock plus the latest likelihood. No upload or automatic sharing by kokok. Only Copy summary puts your estimates on the clipboard. Alarms stay unchanged. You can stop and clear records here.")
                .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> enableSleep() }.show()
        })
        root.addView(button("stop sleep estimates") {
            SleepStore.setEnabled(this, false)
            SleepMonitoring.unregister(this) { sleepRefresh() }
            render()
        })
        root.addView(button("copy private sleep summary") {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("kokok estimated sleep", SleepStore.summary(this)))
            Toast.makeText(this, "Sleep summary copied. Share only where you choose.", Toast.LENGTH_LONG).show()
        })
        root.addView(button("clear stored sleep estimates") {
            AlertDialog.Builder(this).setTitle("Clear sleep estimates?")
                .setMessage("Deletes only sleep records from kokok, not alarms or provider history. Stop estimates first if you do not want future updates.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear records") { _, _ -> SleepStore.clear(this); render() }.show()
        })
    }

    private fun handlePairingShare(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND && incoming.type == "text/plain") {
            val value = incoming.getStringExtra(Intent.EXTRA_TEXT) ?: return
            incoming.removeExtra(Intent.EXTRA_TEXT)
            reviewPairing(value)
        }
    }

    private fun reviewPairing(raw: String) {
        val config = try { PairingConfig.parse(raw) } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Pairing not imported")
                .setMessage(e.message ?: "Invalid configuration")
                .setPositiveButton("OK", null).show()
            return
        }
        val replacing = feedUrl.isNotEmpty()
        AlertDialog.Builder(this)
            .setTitle(if (replacing) "Replace alarm publisher?" else "Pair alarm publisher?")
            .setMessage("Only import configuration from your trusted assistant.\n\n" +
                "Host: ${Uri.parse(config.feedUrl).host}\nPublisher key id: ${config.publisherKeyId}\n\n" +
                "The next verified feed can set alarms and cancel remote alarms it omits. " +
                "Overdue alarms ring immediately. Local test alarms are not feed-managed.\n\n" +
                "Existing alarms, phone key and replay protection are kept. The new publisher must " +
                "use a revision above $lastAppliedRev. Import is not proof that an alarm is set.\n\n" +
                "Phone decryption key is held in app-private device-protected storage, " +
                "not hardware-backed key storage. Automatic phone-to-assistant receipts are not available.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Trust publisher and sync") { _, _ ->
                Thread {
                    synchronized(Sync) {
                        // Keep revision, phone key and alarms. Never clear them on re-pair.
                        val ok = PairingStore.install(this, config)
                        if (!ok) {
                            runOnUiThread { Toast.makeText(this, "Pairing could not be saved", Toast.LENGTH_LONG).show() }
                            return@Thread
                        }
                        Sync.scheduleJob(this)
                        try { Sync.fetchAndApply(this) }
                        catch (e: Exception) {
                            with(FeedStore) { lastSyncStatus = "paired; first sync failed: ${e.javaClass.simpleName}" }
                        }
                    }
                    runOnUiThread { render() }
                }.start()
            }.show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshAlarmVolume()
    }

    private fun refreshAlarmVolume() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        alarmVolumeLabel?.text = "alarm volume: $current / $max" +
            if (current == 0) " (silent)" else ""
    }

    private fun render() {
        root.removeAllViews()

        root.addView(text("kokok ${BuildConfig.VERSION_NAME}", 28f, Color.rgb(255, 140, 0), bold = true))
        root.addView(text("alarms set for you from the feed; they ring fully offline once applied.", 16f, Color.DKGRAY))

        alarmVolumeLabel = text("", 16f, Color.DKGRAY)
        root.addView(alarmVolumeLabel)
        refreshAlarmVolume()
        root.addView(button("adjust alarm volume") {
            // ADJUST_SAME opens Android's alarm-volume panel without changing volume.
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.adjustStreamVolume(AudioManager.STREAM_ALARM,
                AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        })
        root.addView(text("Uses phone alarm volume, not media volume. Tap to adjust in Android's volume panel. Kokok never raises it automatically.", 14f, Color.DKGRAY))

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
                else -> a.status
            }
            val color = when (a.status) {
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

        root.addView(text("alarm sound", 20f, Color.BLACK, bold = true, topPad = 24))
        root.addView(text(AlarmSound.label(this), 16f, Color.DKGRAY).apply { tag = "alarm-sound-label" })
        root.addView(text("Only kokok uses this choice. The phone default stays in use until you choose another sound. Preview plays for up to 10 seconds at your existing alarm volume, without changing any alarm.", 14f, Color.DKGRAY))
        root.addView(button("preview alarm sound (up to 10 s)") { previewSound() })
        root.addView(button("stop sound preview") { stopSoundPreview() })
        root.addView(button("choose alarm sound") {
            stopSoundPreview()
            try {
                val picker = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmSound.playbackUri(this@MainActivity))
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose kokok alarm sound")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(picker, soundPickerRequest)
            } catch (e: Exception) {
                Toast.makeText(this, "Sound chooser unavailable. Current sound kept.", Toast.LENGTH_LONG).show()
            }
        })
        root.addView(button("use phone default alarm sound") {
            stopSoundPreview()
            AlarmSound.useDefault(this)
            render()
        })

        renderSleep()

        root.addView(text("pairing", 20f, Color.BLACK, bold = true, topPad = 32))
        root.addView(text(if (feedUrl.isEmpty()) "not paired" else "publisher configured; check last sync and alarm list above", 16f, Color.DKGRAY))
        root.addView(text("Paste the non-secret pairing JSON from your trusted assistant, or share it to kokok. No password or private key belongs here.", 16f, Color.DKGRAY))
        val pairingInput = EditText(this).apply {
            hint = "pairing JSON (feedUrl + verifyKeyset)"
            minLines = 3
            maxLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(65536))
        }
        root.addView(pairingInput)
        root.addView(button(if (feedUrl.isEmpty()) "review pairing" else "review replacement pairing") {
            reviewPairing(pairingInput.text.toString())
        })
        root.addView(text("phone public pairing key", 18f, Color.BLACK, bold = true, topPad = 24))
        root.addView(text("Public information only. Long-press the text below and select all to copy. Your private decryption key stays on this phone.", 16f, Color.DKGRAY))
        val publicKey = try { Keys.publicKeysetJson(this) } catch (e: Exception) { null }
        val publicKeyView = text(publicKey ?: "Public key unavailable. Existing keys and alarms were kept. Close and reopen kokok to retry; do not clear app data.", 14f, Color.DKGRAY).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            tag = "phone-public-key"
        }
        root.addView(publicKeyView)
        root.addView(button("copy phone public pairing key") {
            if (publicKey != null) {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("phone public pairing key", publicKey))
                    Toast.makeText(this, "public pairing key copied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Long-press the public text above to copy it", Toast.LENGTH_LONG).show()
                }
            } else Toast.makeText(this, "Public key unavailable; existing phone data kept", Toast.LENGTH_LONG).show()
        })
        root.addView(button("share phone public pairing key") {
            if (publicKey != null) {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, publicKey)
                    }
                    startActivity(Intent.createChooser(send, "phone public pairing key"))
                } catch (e: Exception) {
                    Toast.makeText(this, "Share unavailable. Copy the public text above instead.", Toast.LENGTH_LONG).show()
                }
            } else Toast.makeText(this, "Public key unavailable; existing phone data kept", Toast.LENGTH_LONG).show()
        })

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

    private fun stopSoundPreview() {
        previewHandler.removeCallbacks(stopPreviewTask)
        soundPreview?.let { try { it.stop() } catch (e: Exception) {} ; it.release() }
        soundPreview = null
    }

    private fun previewSound() {
        stopSoundPreview()
        if (AlarmRinger.isRinging()) {
            Toast.makeText(this, "Dismiss or snooze the ringing alarm first", Toast.LENGTH_LONG).show()
            return
        }
        val player = android.media.MediaPlayer()
        soundPreview = player
        try {
            player.setDataSource(this, AlarmSound.playbackUri(this))
            player.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            player.setOnCompletionListener { stopSoundPreview() }
            player.setOnErrorListener { _, _, _ ->
                stopSoundPreview()
                Toast.makeText(this, "Preview failed. Choose an available device alarm sound.", Toast.LENGTH_LONG).show()
                true
            }
            player.prepare()
            player.start()
            android.util.Log.i("KokokAlarmAudio", "preview playback started")
            previewHandler.postDelayed(stopPreviewTask, 10_000)
        } catch (e: Exception) {
            stopSoundPreview()
            Toast.makeText(this, "Preview unavailable. Current sound and alarm data kept.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Android Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != soundPickerRequest || resultCode != RESULT_OK) return
        @Suppress("DEPRECATION")
        val uri = data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            try {
                if (uri == android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
                    AlarmSound.useDefault(this) else AlarmSound.save(this, uri)
            } catch (e: Exception) {
                Toast.makeText(this, "Sound not changed. Choose a device alarm sound.", Toast.LENGTH_LONG).show()
            }
        }
        render()
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
