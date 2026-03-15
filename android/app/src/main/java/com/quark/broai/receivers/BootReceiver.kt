package com.quark.broai.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.quark.broai.BroAIApp
import com.quark.broai.services.BroService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            val p = ctx.getSharedPreferences(BroAIApp.PREFS, Context.MODE_PRIVATE)
            if (p.getBoolean(BroAIApp.K_BOOT, true) && p.getBoolean(BroAIApp.K_MASTER, true)) {
                val i = Intent(ctx, BroService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }
    }
}
