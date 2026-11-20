package com.vanipy.kokok

import android.content.Context
import android.net.Uri
import android.os.UserManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AlarmBootAudioTest {
 @Test fun exactBytesAreLockedReadableAndStaleChoiceOrCorruptionIsRejected() {
  val c=RuntimeEnvironment.getApplication() as Context
  val um=shadowOf(c.getSystemService(UserManager::class.java))
  um.setUserUnlocked(true)
  val uri=Uri.parse("content://synthetic/alarm/one")
  val bytes=byteArrayOf(1,3,5,7,9)
  shadowOf(c.contentResolver).registerInputStream(uri,java.io.ByteArrayInputStream(bytes))
  AlarmSound.save(c,uri)
  assertNull(AlarmBootAudio.lockedFile(c))
  um.setUserUnlocked(false)
  val cached=AlarmBootAudio.lockedFile(c)
  assertNotNull(cached)
  assertArrayEquals(bytes,cached!!.readBytes())
  cached.writeBytes(byteArrayOf(9,8,7))
  assertNull(AlarmBootAudio.lockedFile(c))
  um.setUserUnlocked(true)
  c.getSharedPreferences("alarm_sound",0).edit().putString("uri","content://synthetic/alarm/two").commit()
  AlarmBootMirror.sound(c)
  um.setUserUnlocked(false)
  assertNull(AlarmBootAudio.lockedFile(c))
 }
 @Test fun failedRefreshCannotLeavePreviousSoundPlayable() {
  val c=RuntimeEnvironment.getApplication() as Context
  val um=shadowOf(c.getSystemService(UserManager::class.java));um.setUserUnlocked(true)
  val one=Uri.parse("content://synthetic/alarm/good")
  shadowOf(c.contentResolver).registerInputStream(one,java.io.ByteArrayInputStream(byteArrayOf(2,4,6)))
  AlarmSound.save(c,one)
  val two=Uri.parse("content://synthetic/alarm/empty")
  shadowOf(c.contentResolver).registerInputStream(two,java.io.ByteArrayInputStream(byteArrayOf()))
  AlarmSound.save(c,two)
  assertEquals(two,AlarmSound.selected(c))
  um.setUserUnlocked(false)
  assertNull(AlarmBootAudio.lockedFile(c))
 }
}
