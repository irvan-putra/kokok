package com.vanipy.kokok

import android.content.Context

/** Config replacement is serialized with fetch/apply. Revision, keys and alarms never reset. */
object PairingStore {
    fun install(context: Context, config: PairingConfig): Boolean = synchronized(Sync) {
        context.createDeviceProtectedStorageContext().getSharedPreferences("feed", Context.MODE_PRIVATE).edit()
            .putString("feedUrl", config.feedUrl)
            .putString("verifyKeyset", config.verifyKeyset)
            .commit()
    }
}
