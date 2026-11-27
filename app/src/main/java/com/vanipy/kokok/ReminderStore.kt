package com.vanipy.kokok

import android.content.Context
import org.json.JSONObject

/**
 * Persistent reminder state in credential-protected SharedPreferences.
 * Reminders are notification-level, so unlike alarms they are not mirrored
 * into device-protected storage: they resume after the first unlock
 * (ReminderRestoreReceiver), never before it.
 */
object ReminderStore {
    private fun prefs(context: Context) =
        context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun defs(context: Context): MutableMap<String, ReminderModel.Reminder> {
        val obj = JSONObject(prefs(context).getString("defs", "{}") ?: "{}")
        val out = LinkedHashMap<String, ReminderModel.Reminder>()
        for (key in obj.keys()) {
            try {
                val def = ReminderModel.parseReminder(obj.getJSONObject(key))
                out[def.id] = def
            } catch (e: Exception) {
                // a def we once accepted must stay parseable; drop loudly, never crash the UI
                android.util.Log.e("KokokReminder", "dropping unreadable reminder def " + key, e)
            }
        }
        return out
    }

    fun saveDefs(context: Context, defs: Map<String, ReminderModel.Reminder>) {
        val obj = JSONObject()
        for ((key, def) in defs) obj.put(key, ReminderModel.toJson(def))
        prefs(context).edit().putString("defs", obj.toString()).apply()
    }

    fun state(context: Context, id: String): ReminderModel.State {
        val states = JSONObject(prefs(context).getString("states", "{}") ?: "{}")
        val obj = states.optJSONObject(id) ?: return ReminderModel.State()
        val s = ReminderModel.State()
        val done = obj.optJSONArray("done")
        if (done != null) for (i in 0 until done.length()) s.doneDates.add(done.getString(i))
        s.awaitingDate = obj.optString("awaiting", "")
        s.snoozeUntilMillis = obj.optLong("snoozeUntil", 0)
        return s
    }

    fun saveState(context: Context, id: String, state: ReminderModel.State) {
        val states = JSONObject(prefs(context).getString("states", "{}") ?: "{}")
        val obj = JSONObject()
        val done = org.json.JSONArray()
        for (d in state.doneDates) done.put(d)
        obj.put("done", done)
        obj.put("awaiting", state.awaitingDate)
        obj.put("snoozeUntil", state.snoozeUntilMillis)
        states.put(id, obj)
        prefs(context).edit().putString("states", states.toString()).apply()
    }
}
