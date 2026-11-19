package com.vanipy.kokok

import android.app.job.JobParameters
import android.app.job.JobService

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                fetchAndApplySafe()
                jobFinished(params, false)
            } catch (e: Exception) {
                with(FeedStore) { this@SyncJobService.lastSyncStatus = "sync failed: ${e.javaClass.simpleName}" }
                jobFinished(params, true)
            }
        }.start()
        return true
    }

    private fun fetchAndApplySafe() {
        if (with(FeedStore) { this@SyncJobService.feedUrl }.isEmpty()) return
        Sync.fetchAndApply(this)
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
