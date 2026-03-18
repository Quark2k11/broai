package com.quark.broai
import android.app.Application; import android.app.NotificationChannel; import android.app.NotificationManager; import android.os.Build; import androidx.multidex.MultiDex
class BroAIApp : Application() {
    override fun onCreate() { super.onCreate(); MultiDex.install(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val nm = getSystemService(NotificationManager::class.java)
            NotificationChannel(CH_MAIN,"Bro AI Active",NotificationManager.IMPORTANCE_LOW).also{it.setShowBadge(false);nm.createNotificationChannel(it)}
            NotificationChannel(CH_ALERT,"Bro AI Alerts",NotificationManager.IMPORTANCE_HIGH).also{nm.createNotificationChannel(it)} } }
    companion object {
        const val CH_MAIN="bro_main"; const val CH_ALERT="bro_alert"; const val PREFS="broai_prefs"
        const val K_GEMINI="gemini_key"; const val K_GROQ="groq_key"; const val K_MISTRAL="mistral_key"
        const val K_OR="or_key"; const val K_HF="hf_key"; const val K_WG="wg_key"; const val K_GH="gh_key"
        const val K_NAME="owner_name"; const val K_ESP_IP="esp_ip"; const val K_SETUP="setup_done"
        const val K_MASTER="pw_master"; const val K_BG_WAKE="pw_bg_wake"; const val K_BOOT="pw_boot"
        const val K_CALLS="pw_calls"; const val K_MESSAGES="pw_messages"; const val K_ACCESSIBLE="pw_access"
        const val K_EMERGENCY="pw_emergency"; const val K_WAKELCK="pw_wakelock"; const val K_OVERLAY="pw_overlay"
        const val K_LOCATION="pw_location"; const val K_SCREEN_READ="pw_screenread"; const val K_SMART_HOME="pw_smarthome"
        const val S_AUTO_NAV="autoNav"; const val S_OBSTACLE="obstacle"; const val S_GUARDIAN="guardian"
        const val S_DETECTION="detection"; const val S_FACE_REC="faceRec"; const val S_FOLLOW="followPerson"
        const val S_BAT_ALERT="batAlert"; const val S_EMERGENCY="emergency"
    }
}