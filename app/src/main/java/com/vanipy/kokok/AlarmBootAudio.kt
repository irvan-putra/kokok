package com.vanipy.kokok

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.UserManager
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Private direct-boot copy of the exact chosen tone, never a substitute.
 * Preferences and the original URI remain credential-private and unchanged.
 * No provider access is attempted while the user is locked.
 */
object AlarmBootAudio {
    private const val LIMIT = 32 * 1024 * 1024
    private fun unlocked(c: Context) = c.getSystemService(UserManager::class.java).isUserUnlocked
    private fun dc(c: Context) = c.createDeviceProtectedStorageContext()
    private fun file(c: Context) = File(dc(c).filesDir, "alarm-tone.bin")
    private fun metadata(c: Context) = dc(c).getSharedPreferences("alarm-tone-cache", 0)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Synchronized fun refresh(c: Context) {
        if (!unlocked(c)) return
        val selected = AlarmSound.playbackUri(c)
        val actual = if (RingtoneManager.isDefault(selected))
            RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM) else selected
        val meta = metadata(c)
        // Invalidate first. A failed refresh must never play an older choice.
        check(meta.edit().clear().commit())
        val atomic = AtomicFile(file(c))
        var output: java.io.FileOutputStream? = null
        try {
            require(actual != null && actual.scheme == "content")
            val bytes = c.contentResolver.openInputStream(actual)!!.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    require(buffer.size() + n <= LIMIT) { "Alarm tone is too large" }
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
            require(bytes.isNotEmpty())
            output = atomic.startWrite()
            output.write(bytes)
            atomic.finishWrite(output)
            output = null
            check(meta.edit().putString("choice", selected.toString())
                .putString("resolved", actual.toString()).putString("sha256", hash(bytes)).commit())
        } catch (e: Exception) {
            output?.let { atomic.failWrite(it) }
            atomic.delete()
            meta.edit().clear().commit()
            android.util.Log.w("KokokAlarmAudio", "Chosen tone could not be cached for locked boot")
        }
    }

    @Synchronized fun lockedFile(c: Context): File? {
        if (unlocked(c)) return null
        val meta = metadata(c)
        if (meta.getString("choice", null) != AlarmSound.playbackUri(c).toString()) return null
        return try {
            val f = file(c)
            if (f.length() !in 1..LIMIT.toLong()) return null
            if (hash(AtomicFile(f).readFully()) != meta.getString("sha256", null)) return null
            f
        } catch (e: Exception) { null }
    }

    fun setDataSource(c: Context, player: MediaPlayer) {
        if (unlocked(c)) player.setDataSource(c, AlarmSound.playbackUri(c))
        else {
            val f = checkNotNull(lockedFile(c)) { "Chosen tone unavailable before unlock" }
            java.io.FileInputStream(f).use { player.setDataSource(it.fd) }
        }
    }
}
