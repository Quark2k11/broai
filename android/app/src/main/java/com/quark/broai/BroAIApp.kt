package com.quark.broai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.multidex.MultiDex

class BroAIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MultiDex.install(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            NotificationChannel(CH_MAIN, "Bro AI Active", NotificationManager.IMPORTANCE_LOW)
                .also { it.setShowBadge(false); nm.createNotificationChannel(it) }
            NotificationChannel(CH_ALERT, "Bro AI Alerts", NotificationManager.IMPORTANCE_HIGH)
                .also { nm.createNotificationChannel(it) }
        }
    }
    companion object {
        const val CH_MAIN  = "bro_main"
        const val CH_ALERT = "bro_alert"
        const val PREFS    = "broai_prefs"
        const val K_MASTER      = "pw_master"
        const val K_BG_WAKE     = "pw_bg_wake"
        const val K_BOOT        = "pw_boot"
        const val K_CALLS       = "pw_calls"
        const val K_MESSAGES    = "pw_messages"
        const val K_ACCESSIBLE  = "pw_access"
        const val K_EMERGENCY   = "pw_emergency"
        const val K_WAKELCK     = "pw_wakelock"
        const val K_OVERLAY     = "pw_overlay"
        const val K_LOCATION    = "pw_location"
        const val K_SCREEN_READ = "pw_screenread"
        const val K_SMART_HOME  = "pw_smarthome"
    }
}
