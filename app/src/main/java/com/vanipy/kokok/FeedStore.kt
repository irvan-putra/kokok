package com.vanipy.kokok

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent alarm + sync state in SharedPreferences. The application sets
 * defaultToDeviceProtectedStorage=true, so this is readable before first unlock
 * (direct boot) which BOOT_COMPLETED/LOCKED_BOOT_COMPLETED rescheduling needs.
 */
object FeedStore {
    data class StoredAlarm(
        val id: String,
        val label: String,
        val atMillis: Long,
        val snooze: Int,
        val status: String, // scheduled | fired | dismissed | canceled
        val local: Boolean = false, // created on-device (test alarm), not feed-managed
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences("feed", Context.MODE_PRIVATE)

    fun alarms(context: Context): MutableMap<String, StoredAlarm> {
        val raw = prefs(context).getString("alarms", "{}") ?: "{}"
        val obj = JSONObject(raw)
        val out = LinkedHashMap<String, StoredAlarm>()
        for (key in obj.keys()) {
            val a = obj.getJSONObject(key)
            out[key] = StoredAlarm(
                a.getString("id"),
                a.optString("label", ""),
                a.getLong("atMillis"),
                a.optInt("snooze", 9),
                a.getString("status"),
                a.optBoolean("local", false),
            )
        }
        return out
    }

    fun saveAlarms(context: Context, alarms: Map<String, StoredAlarm>) {
        val obj = JSONObject()
        for ((key, a) in alarms) {
            obj.put(key, JSONObject()
                .put("id", a.id)
                .put("label", a.label)
                .put("atMillis", a.atMillis)
                .put("snooze", a.snooze)
                .put("status", a.status)
                .put("local", a.local))
        }
        prefs(context).edit().putString("alarms", obj.toString()).apply()
    }

    var Context.lastAppliedRev: Long
        get() = prefs(this).getLong("lastAppliedRev", 0L)
        set(v) = prefs(this).edit().putLong("lastAppliedRev", v).apply()

    var Context.feedUrl: String
        get() = prefs(this).getString("feedUrl", "") ?: ""
        set(v) = prefs(this).edit().putString("feedUrl", v).apply()

    var Context.verifyKeyset: String
        get() = prefs(this).getString("verifyKeyset", "") ?: ""
        set(v) = prefs(this).edit().putString("verifyKeyset", v).apply()

    var Context.appKeyset: String?
        get() = prefs(this).getString("appKeyset", null)
        set(v) = prefs(this).edit().putString("appKeyset", v).apply()

    var Context.lastSyncAt: Long
        get() = prefs(this).getLong("lastSyncAt", 0L)
        set(v) = prefs(this).edit().putLong("lastSyncAt", v).apply()

    var Context.lastSyncStatus: String
        get() = prefs(this).getString("lastSyncStatus", "") ?: ""
        set(v) = prefs(this).edit().putString("lastSyncStatus", v).apply()
}
