package com.vanipy.kokok

import com.vanipy.kokok.FeedStore.lastAppliedRev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Receiver-level flows over the store + apply semantics (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiverFlowTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        FeedStore.saveAlarms(context, emptyMap())
        context.lastAppliedRev = 0
    }

    private fun feedJson(rev: Int, vararg alarms: Pair<String, Long>): String {
        val arr = alarms.joinToString(",") { (id, at) -> """{"id":"$id","label":"l-$id","atMillis":$at}""" }
        return """{"v":1,"rev":$rev,"alarms":[$arr]}"""
    }

    @Test
    fun scheduledThenFiredThenDismissed() {
        val now = System.currentTimeMillis()
        val stored = FeedStore.alarms(context)
        stored["a"] = FeedStore.StoredAlarm("a", "wake", now + 60_000, 9, "scheduled")
        FeedStore.saveAlarms(context, stored)
        assertEquals("scheduled", FeedStore.alarms(context)["a"]!!.status)

        // AlarmReceiver semantics: only scheduled alarms may fire
        stored["a"] = stored["a"]!!.copy(status = "fired")
        FeedStore.saveAlarms(context, stored)
        val plan = FeedModel.apply(FeedModel.parse(feedJson(1, "a" to now + 60_000)), 0, stored, now)
        assertEquals(0, plan.toSchedule.size) // fired never re-rings

        stored["a"] = stored["a"]!!.copy(status = "dismissed")
        FeedStore.saveAlarms(context, stored)
        assertEquals("dismissed", FeedStore.alarms(context)["a"]!!.status)
    }

    @Test
    fun cancelOrderingAbsenceCancels() {
        val now = System.currentTimeMillis()
        val stored = FeedStore.alarms(context)
        stored["a"] = FeedStore.StoredAlarm("a", "wake", now + 60_000, 9, "scheduled")
        stored["b"] = FeedStore.StoredAlarm("b", "keep", now + 60_000, 9, "scheduled")
        val plan = FeedModel.apply(FeedModel.parse(feedJson(2, "b" to now + 120_000)), 1, stored, now)
        assertEquals(listOf("a"), plan.toCancel)
        assertEquals(1, plan.toSchedule.size)
        assertEquals("b", plan.toSchedule[0].id)
    }

    @Test
    fun updateAfterFiredDoesNotReschedule() {
        val now = System.currentTimeMillis()
        val stored = FeedStore.alarms(context)
        stored["a"] = FeedStore.StoredAlarm("a", "wake", now - 60_000, 9, "fired")
        // feed rev 2 still carries alarm "a" with a NEW future time: must not re-ring
        val plan = FeedModel.apply(FeedModel.parse(feedJson(2, "a" to now + 3_600_000)), 1, stored, now)
        assertEquals(0, plan.toSchedule.size)
        assertEquals(0, plan.toRingNow.size)
    }

    @Test
    fun repeatingIdAfterFiredStaysSilent() {
        // repeating alarms use a fresh id per occurrence (id + date), so a fired
        // occurrence can never collide with the next one
        val now = System.currentTimeMillis()
        val stored = FeedStore.alarms(context)
        stored["daily-2026-11-19"] = FeedStore.StoredAlarm("daily-2026-11-19", "wake", now - 60_000, 9, "fired")
        val plan = FeedModel.apply(FeedModel.parse(feedJson(2, "daily-2026-11-20" to now + 86_400_000)), 1, stored, now)
        assertEquals(1, plan.toSchedule.size)
        assertEquals("daily-2026-11-20", plan.toSchedule[0].id)
    }

    @Test
    fun rebootRescheduleRingsLateAlarms() {
        val now = System.currentTimeMillis()
        val stored = FeedStore.alarms(context)
        stored["ok"] = FeedStore.StoredAlarm("ok", "wake", now + 3_600_000, 9, "scheduled")
        stored["late"] = FeedStore.StoredAlarm("late", "late", now - 5 * 60_000, 9, "scheduled")
        stored["old"] = FeedStore.StoredAlarm("old", "old", now - 3_600_000, 9, "scheduled")
        stored["done"] = FeedStore.StoredAlarm("done", "done", now - 3_600_000, 9, "dismissed")

        // BootReceiver logic: future -> reschedule, past-due -> ring now (never missed),
        // terminal untouched
        val plan = mutableListOf<String>()
        for ((id, alarm) in stored.toMap()) {
            if (alarm.status != "scheduled") continue
            if (alarm.atMillis <= now) plan.add(id) // would be scheduled to ring now
        }
        assertEquals(setOf("late", "old"), plan.toSet())
        FeedStore.saveAlarms(context, stored)
        val after = FeedStore.alarms(context)
        assertEquals("scheduled", after["ok"]!!.status)
        assertEquals("scheduled", after["late"]!!.status)
        assertEquals("scheduled", after["old"]!!.status)
        assertEquals("dismissed", after["done"]!!.status)
    }

    @Test
    fun timeChangeUsesFreshClockNotStoredTimes() {
        // TIME_SET / TIMEZONE_CHANGED path re-evaluates against currentTimeMillis,
        // so a clock moved forward past an alarm rings it immediately (late alarms
        // always ring, user decision 2026-11-19)
        val stored = FeedStore.alarms(context)
        val alarmAt = System.currentTimeMillis() - 30 * 60_000
        stored["a"] = FeedStore.StoredAlarm("a", "wake", alarmAt, 9, "scheduled")
        val nowAfterClockJump = System.currentTimeMillis()
        val plan = FeedModel.apply(FeedModel.parse(feedJson(1, "a" to alarmAt)), 0, stored, nowAfterClockJump)
        assertEquals(1, plan.toRingNow.size)
    }
}
