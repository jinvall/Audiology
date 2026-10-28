package com.audiology2.stream

import android.app.Application
import android.os.Build
import android.view.View
import android.util.Log
import com.audiology2.stream.config.SettingsStore
import com.audiology2.stream.service.NotificationHelper

class StreamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Force LTR globally before any views inflate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // This must be done early, before any Activity creates its window
            // Use a default window policy approach
        }
        try {
            SettingsStore.init(this)
            NotificationHelper.createChannel(this)
        } catch (e: Throwable) {
            Log.e("StreamApp", "init failed", e)
        }
    }
}
