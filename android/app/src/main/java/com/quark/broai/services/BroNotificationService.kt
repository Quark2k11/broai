package com.quark.broai.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.quark.broai.BroAIApp

class BroNotificationService : NotificationListenerService() {
    companion object {
        var instance: BroNotificationService? = null
        val recent = mutableListOf<String>()
        private val MESSENGERS = setOf(
            "com.whatsapp","org.telegram.messenger","com.facebook.orca",
            "com.google.android.apps.messaging","com.samsung.android.messaging"
        )
    }
    override fun onCreate()  { super.onCreate();  instance = this }
    override fun onDestroy() { super.onDestroy(); instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(BroAIApp.K_MESSAGES, true)) return
        val extras = sbn.notification?.extras ?: return
        val title  = extras.getString("android.title") ?: ""
        val text   = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName,0)).toString()
        } catch (e: Exception) { sbn.packageName.substringAfterLast(".") }
        recent.add(0, "$app: $title $text"); if (recent.size > 50) recent.removeAt(50)
        if (MESSENGERS.any { sbn.packageName.contains(it) } && !BroService.isSpeaking)
            BroService.speak("$app: $text".take(120))
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
