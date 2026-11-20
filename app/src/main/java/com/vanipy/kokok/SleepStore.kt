package com.vanipy.kokok

import android.content.Context
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sleep data never goes in the direct-boot alarm store or encrypted inbound feed. */
object SleepStore {
    // Credential-protected path is public ApplicationInfo API24+. Do not rely on
    // the app's default context, which intentionally uses device-protected alarms.
    internal fun file(c: Context) = java.io.File(c.applicationInfo.credentialProtectedDataDir, "files/sleep-private.json")
    private class PrivatePrefs(val c: Context) {
        private fun load(): JSONObject = try { JSONObject(android.util.AtomicFile(file(c)).readFully().toString(Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }
        fun getString(k: String, d: String) = load().optString(k, d)
        fun getBoolean(k: String, d: Boolean) = load().optBoolean(k, d)
        fun getLong(k: String, d: Long) = load().optLong(k, d)
        fun getInt(k: String, d: Int) = load().optInt(k, d)
        fun edit() = Editor(c, load())
    }
    private class Editor(val c: Context, val data: JSONObject) {
        fun putString(k: String,v: String) = apply { data.put(k,v) }
        fun putBoolean(k: String,v: Boolean) = apply { data.put(k,v) }
        fun putLong(k: String,v: Long) = apply { data.put(k,v) }
        fun putInt(k: String,v: Int) = apply { data.put(k,v) }
        fun remove(k: String) = apply { data.remove(k) }
        fun apply() {
            check(unlocked(c))
            val target = file(c); target.parentFile!!.mkdirs()
            val atomic = android.util.AtomicFile(target)
            val stream = atomic.startWrite()
            try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
    }
    private fun prefs(c: Context) = PrivatePrefs(c)
    fun unlocked(c: Context) = c.getSystemService(UserManager::class.java).isUserUnlocked
    fun enabled(c: Context) = unlocked(c) && prefs(c).getBoolean("enabled", false)
    fun status(c: Context) = if (unlocked(c)) prefs(c).getString("status", "Off. No sleep data collected by kokok.")!! else "Unlock phone to view sleep records."
    fun generation(c: Context) = prefs(c).getLong("generation", 0)
    @Synchronized fun setEnabled(c: Context, value: Boolean): Long {
        val g = generation(c) + 1
        prefs(c).edit().putBoolean("enabled", value).putLong("generation", g)
            .putString("status", if (value) "Requesting sleep updates. No estimate yet." else "Off. Stored estimates kept until you clear them.").apply()
        return g
    }
    @Synchronized fun status(c: Context, message: String) { if (unlocked(c)) prefs(c).edit().putString("status", message).apply() }
    @Synchronized fun segment(c: Context, start: Long, end: Long, status: Int) {
        if (!enabled(c)) return
        // Status 0 is successful; 1 has missing data; 2 is not detected. Keep unknown statuses visible.
        if (status != 2 && (start < 0 || end <= start || end > System.currentTimeMillis() + 60_000)) return
        val p = prefs(c)
        val rows = JSONArray(p.getString("segments", "[]"))
        val out = JSONArray()
        for (i in 0 until rows.length()) {
            val x = rows.getJSONObject(i)
            if (x.optLong("start") != start || x.optLong("end") != end) out.put(x)
        }
        out.put(JSONObject().put("start", start).put("end", end).put("status", status)
            .put("received", System.currentTimeMillis()))
        val sorted = (0 until out.length()).map { out.getJSONObject(it) }.sortedByDescending { it.optLong("end") }
        p.edit().putString("segments", JSONArray(sorted.take(30)).toString()).apply()
    }
    @Synchronized fun classification(c: Context, time: Long, confidence: Int) {
        if (!enabled(c) || time < 0 || time > System.currentTimeMillis() + 60_000 || confidence !in 0..100) return
        val p = prefs(c)
        if (time < p.getLong("classTime", 0)) return
        p.edit().putLong("classTime", time).putInt("confidence", confidence).apply()
    }
    @Synchronized fun clear(c: Context) {
        prefs(c).edit().remove("segments").remove("classTime").remove("confidence").apply()
    }
    fun summary(c: Context): String {
        if (!unlocked(c)) return "Unlock phone to view sleep records."
        val p = prefs(c)
        val df = SimpleDateFormat("EEE d MMM yyyy HH:mm z", Locale.US)
        fun time(t: Long) = df.format(Date(t))
        val out = StringBuilder("kokok estimated sleep, not measured sleep or sleep stages.\n")
        out.append(status(c)).append('\n')
        val ct = p.getLong("classTime", 0)
        if (ct > 0) out.append("Last likelihood estimate: ").append(p.getInt("confidence", 0)).append("/100 at ")
            .append(time(ct)).append(". This is not proof of sleep or a calibrated accuracy percentage.\n")
        val rows = JSONArray(p.getString("segments", "[]"))
        if (rows.length() == 0) out.append("No sleep segment received. Missing data is not zero sleep.\n")
        for (i in 0 until rows.length()) {
            val x = rows.getJSONObject(i)
            val code = x.optInt("status", -1)
            when (code) {
                0, 1 -> out.append("Estimated: ").append(time(x.getLong("start"))).append(" to ")
                    .append(time(x.getLong("end"))).append(if (code == 1) " (missing data near this window)" else " (provider detected)").append('\n')
                2 -> out.append("Sleep not detected or insufficient confidence; not evidence of no sleep.\n")
                else -> out.append("Unavailable estimate (provider status ").append(code).append(").\n")
            }
        }
        out.append("Phone light/movement can differ from your sleep. Power saving or missing sensors can delay or prevent results. No REM or medical claim. Alarm times are unchanged.")
        return out.toString()
    }
}
