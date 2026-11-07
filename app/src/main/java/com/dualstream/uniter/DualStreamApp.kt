package com.dualstream.uniter

import android.app.Application
import com.dualstream.uniter.core.Logx
import com.dualstream.uniter.core.Prefs
import com.dualstream.uniter.service.NotificationHelper

class DualStreamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        Logx.verbose = prefs.verboseLog
        NotificationHelper.ensureChannels(this)
        Logx.i("app", "DualStream Uniter ${BuildConfig.VERSION_NAME} started")
    }
}
