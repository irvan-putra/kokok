package com.vanipy.kokok

import android.content.Context
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
class AlarmBootMirrorTest {
 @Test fun lockedUpdatesReconcileWithoutMovingKeysPairingSoundOrSleep() {
  val c=RuntimeEnvironment.getApplication() as Context
  val um=shadowOf(c.getSystemService(UserManager::class.java))
  um.setUserUnlocked(true)
  val cp=c.getSharedPreferences("feed",0)
  cp.edit().clear().putString("appKeyset","synthetic-key").putString("feedUrl","synthetic-url").putLong("lastAppliedRev",42).commit()
  c.createDeviceProtectedStorageContext().getSharedPreferences("alarm-boot-mirror",0).edit().clear().commit()
  c.getSharedPreferences("alarm_sound",0).edit().putString("uri","content://settings/system/alarm_alert").commit()
  val alarm=FeedStore.StoredAlarm("a","synthetic",2000000000000,9,"scheduled")
  FeedStore.saveAlarms(c,mapOf("a" to alarm))
  AlarmBootMirror.prepare(c)
  val original=cp.all.toMap()
  val boot=c.createDeviceProtectedStorageContext().getSharedPreferences("alarm-boot-mirror",0)
  assertEquals(setOf("alarms","sound","pending"),boot.all.keys)
  um.setUserUnlocked(false)
  assertEquals(alarm,FeedStore.alarms(c)["a"])
  assertEquals("content://settings/system/alarm_alert",AlarmSound.selected(c).toString())
  FeedStore.saveAlarms(c,mapOf("a" to alarm.copy(status="dismissed")))
  assertEquals(original,cp.all) // locked updates leave rollback-compatible CP untouched
  um.setUserUnlocked(true)
  assertEquals("dismissed",FeedStore.alarms(c)["a"]!!.status)
  assertEquals("synthetic-key",cp.getString("appKeyset",null))
  assertEquals("synthetic-url",cp.getString("feedUrl",null))
  assertEquals(42L,cp.getLong("lastAppliedRev",0))
  assertFalse(boot.getBoolean("pending",true))
  assertTrue(cp.getString("alarms","")!!.contains("dismissed")) // old app schema retained
 }
 @Test fun lockedBootComponentsAndNetworkPermissionArePresent() {
  val c=RuntimeEnvironment.getApplication() as Context
  val pm=c.packageManager
  val flags=android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE or android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE
  val service=pm.getServiceInfo(android.content.ComponentName(c,AlarmRingService::class.java),flags)
  val activity=pm.getActivityInfo(android.content.ComponentName(c,AlarmActivity::class.java),flags)
  assertTrue(service.directBootAware)
  assertTrue(activity.directBootAware)
  val pkg=pm.getPackageInfo(c.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
  assertTrue(pkg.requestedPermissions.contains("android.permission.ACCESS_NETWORK_STATE"))
 }
 @Test fun lockedBootDoesNotScheduleCredentialPrivateNetworkWork() {
  val c=RuntimeEnvironment.getApplication() as Context
  shadowOf(c.getSystemService(UserManager::class.java)).setUserUnlocked(false)
  val js=c.getSystemService(android.app.job.JobScheduler::class.java)
  js.cancelAll()
  Sync.scheduleJob(c)
  assertTrue(js.allPendingJobs.isEmpty())
 }
}
