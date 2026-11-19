package com.vanipy.kokok

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lockscreen-visible ringing screen. Sound/vibration stop on snooze or dismiss. */
class AlarmActivity : Activity() {

    private var alarmId: String = ""
    private var snooze: Int = 9

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent.getStringExtra("id") ?: ""
        snooze = intent.getIntExtra("snooze", 9)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {})
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // fallback start only: AlarmRingService owns sustained sound/vibration;
        // AlarmRinger.start is idempotent so this is a no-op when the service is up
        AlarmRinger.start(this)

        val alarm = FeedStore.alarms(this)[alarmId]
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(16, 16, 24))
            setPadding(48, 48, 48, 48)
        }
        val time = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            textSize = 64f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val label = TextView(this).apply {
            text = intent.getStringExtra("label") ?: alarm?.label ?: ""
            textSize = 22f
            setTextColor(Color.rgb(255, 160, 60))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 64)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val snoozeBtn = Button(this).apply {
            text = "snooze $snooze min"
            setOnClickListener {
                sendBroadcast(android.content.Intent(this@AlarmActivity, SnoozeReceiver::class.java)
                    .putExtra("id", alarmId).putExtra("snooze", snooze))
                finish()
            }
        }
        val dismissBtn = Button(this).apply {
            text = "dismiss"
            setOnClickListener {
                sendBroadcast(android.content.Intent(this@AlarmActivity, DismissReceiver::class.java)
                    .putExtra("id", alarmId))
                finish()
            }
        }
        buttons.addView(snoozeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(dismissBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(time)
        root.addView(label)
        root.addView(buttons)
        setContentView(root)
    }

}
