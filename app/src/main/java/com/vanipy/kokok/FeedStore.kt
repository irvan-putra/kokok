package com.vanipy.kokok

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent alarm + sync state in SharedPreferences. The application sets
 * the same ordinary context as actual signed 0.1.4. Runtime qualification showed
 * its data is credential-protected despite the old manifest default. Do not move
 * these files or claim pre-unlock recovery without an actual migration gate.
 */
object FeedStore {
    data class StoredAlarm @JvmOverloads constructor(
        val id: String,
        val label: String,
        val atMillis: Long,
        val snooze: Int,
        val status: String, // scheduled | fired | dismissed | canceled
        val local: Boolean = false, // created on-device (test alarm), not feed-managed
        val snoozed: Boolean = false, // locally selected next ring, never reset by feed refresh
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences("feed", Context.MODE_PRIVATE)

    fun alarms(context: Context): MutableMap<String, StoredAlarm> {
        val raw = AlarmBootMirror.alarms(context)
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
                a.optBoolean("snoozed", false),
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
                .put("local", a.local)
                .put("snoozed", a.snoozed))
        }
        AlarmBootMirror.saveAlarms(context, obj.toString())
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
