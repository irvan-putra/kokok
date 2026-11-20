package com.vanipy.kokok

import android.content.Context
import android.os.UserManager

/** Minimal direct-boot mirror: alarm records and the selected sound only.
 * Original credential-private feed preferences, keys and pairing stay intact.
 * Before unlock, receivers can update this mirror (fired/snoozed/dismissed).
 * First unlocked read reconciles those records, then refreshes the mirror.
 * No sleep records, feed credentials or signing/decryption keys are copied.
 */
object AlarmBootMirror {
    private fun unlocked(c: Context) = c.getSystemService(UserManager::class.java).isUserUnlocked
    private fun mirror(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("alarm-boot-mirror",0)
    private fun original(c: Context) = c.getSharedPreferences("feed",0)
    @Synchronized fun alarms(c: Context): String {
        val boot = mirror(c)
        if (!unlocked(c)) return boot.getString("alarms","{}") ?: "{}"
        val cp = original(c)
        if (boot.getBoolean("pending",false)) {
            // CP remains authoritative except for receiver changes while locked.
            val raw = boot.getString("alarms","{}") ?: "{}"
            org.json.JSONObject(raw) // fail closed on malformed mirror, keep CP
            check(cp.edit().putString("alarms",raw).commit())
            check(boot.edit().putBoolean("pending",false).commit())
        }
        val raw = cp.getString("alarms","{}") ?: "{}"
        check(boot.edit().putString("alarms",raw).commit())
        return raw
    }
    @Synchronized fun saveAlarms(c: Context, raw: String) {
        org.json.JSONObject(raw)
        if (unlocked(c)) {
            // Reconcile first, before the caller's later state becomes canonical.
            check(original(c).edit().putString("alarms",raw).commit())
            check(mirror(c).edit().putString("alarms",raw).putBoolean("pending",false).commit())
        } else check(mirror(c).edit().putString("alarms",raw).putBoolean("pending",true).commit())
    }
    @Synchronized fun sound(c: Context): String? {
        val boot = mirror(c)
        if (!unlocked(c)) return boot.getString("sound",null)
        val uri = c.getSharedPreferences("alarm_sound",0).getString("uri",null)
        val edit = boot.edit()
        if (uri == null) edit.remove("sound") else edit.putString("sound",uri)
        check(edit.commit())
        return uri
    }
    fun prepare(c: Context) { if (unlocked(c)) { alarms(c); sound(c) } }
}
