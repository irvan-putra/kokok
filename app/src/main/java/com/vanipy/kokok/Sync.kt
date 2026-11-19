package com.vanipy.kokok

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.vanipy.kokok.FeedStore.lastAppliedRev
import com.vanipy.kokok.FeedStore.lastSyncAt
import com.vanipy.kokok.FeedStore.lastSyncStatus
import com.vanipy.kokok.FeedStore.feedUrl
import com.vanipy.kokok.FeedStore.verifyKeyset
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the signed+encrypted feed and applies it fail-closed.
 * HTTPS only, redirects are NOT followed. Publication of a feed is never a
 * confirmation that an alarm rang or even synced; the app shows what it applied.
 */
object Sync {
    private const val JOB_ID = 41

    fun scheduleJob(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setPeriodic(30 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }

    /** Result counts for user-visible status. */
    data class Outcome(val rev: Int, val scheduled: Int, val canceled: Int, val rangNow: Int)

    fun fetchAndApply(context: Context): Outcome {
        val url = context.feedUrl
        // https only; debug builds also accept the emulator host loopback for synthetic tests
        val syntheticHost = BuildConfig.DEBUG && url.startsWith("http://10.0.2.2")
        require(url.startsWith("https://") || syntheticHost) { "feed URL must be https" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val code = conn.responseCode
        if (code != 200) throw java.io.IOException("feed fetch HTTP $code")
        val envelope = conn.inputStream.readBytes().toString(Charsets.UTF_8)

        val feedJson = FeedCrypto.openEnvelope(envelope, context.verifyKeyset, Keys.handle(context))
        val feed = FeedModel.parse(feedJson)

        val stored = FeedStore.alarms(context)
        val now = System.currentTimeMillis()
        val plan = FeedModel.apply(feed, context.lastAppliedRev, stored, now)

        for (id in plan.toCancel) {
            Scheduler.cancel(context, id)
            stored.remove(id)
        }
        for (a in plan.toSchedule) {
            stored[a.id] = FeedStore.StoredAlarm(a.id, a.label, a.atMillis, a.snooze, "scheduled")
            Scheduler.schedule(context, a)
        }
        for (a in plan.toRingNow) {
            stored[a.id] = FeedStore.StoredAlarm(a.id, a.label, a.atMillis, a.snooze, "scheduled")
            Scheduler.schedule(context, FeedModel.Alarm(a.id, a.label, now + 1_000, a.snooze))
        }
        FeedStore.saveAlarms(context, stored)
        context.lastAppliedRev = feed.rev.toLong()
        context.lastSyncAt = now
        val outcome = Outcome(feed.rev, plan.toSchedule.size, plan.toCancel.size, plan.toRingNow.size)
        context.lastSyncStatus = "applied rev ${outcome.rev}: ${outcome.scheduled} scheduled, " +
            "${outcome.canceled} canceled" +
            if (outcome.rangNow > 0) ", ${outcome.rangNow} rang immediately (late)" else ""
        return outcome
    }
}
