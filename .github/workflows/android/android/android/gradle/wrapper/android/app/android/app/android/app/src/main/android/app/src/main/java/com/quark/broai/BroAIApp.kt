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
                            createNotificationChannels()
                                }

                                    private fun createNotificationChannels() {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        val manager = getSystemService(NotificationManager::class.java)
                                                                    NotificationChannel(
                                                                                    CHANNEL_MAIN, "Bro AI Running",
                                                                                                    NotificationManager.IMPORTANCE_LOW
                                                                                                                ).apply {
                                                                                                                                description = "Bro AI background service"
                                                                                                                                                setShowBadge(false)
                                                                                                                                                                manager.createNotificationChannel(this)
                                                                                                                                                                            }
                                                                                                                                                                                        NotificationChannel(
                                                                                                                                                                                                        CHANNEL_ALERT, "Bro AI Alerts",
                                                                                                                                                                                                                        NotificationManager.IMPORTANCE_HIGH
                                                                                                                                                                                                                                    ).apply {
                                                                                                                                                                                                                                                    description = "Incoming calls and important alerts"
                                                                                                                                                                                                                                                                    manager.createNotificationChannel(this)
                                                                                                                                                                                                                                                                                }
                                                                                                                                                                                                                                                                                        }
                                                                                                                                                                                                                                                                                            }

                                                                                                                                                                                                                                                                                                companion object {
                                                                                                                                                                                                                                                                                                        const val CHANNEL_MAIN = "bro_main"
                                                                                                                                                                                                                                                                                                                const val CHANNEL_ALERT = "bro_alert"
                                                                                                                                                                                                                                                                                                                        const val PREF_NAME = "broai_prefs"
                                                                                                                                                                                                                                                                                                                                const val PREF_API_KEY_GEMINI = "gemini_key"
                                                                                                                                                                                                                                                                                                                                        const val PREF_API_KEY_GROQ = "groq_key"
                                                                                                                                                                                                                                                                                                                                                const val PREF_OWNER_NAME = "owner_name"
                                                                                                                                                                                                                                                                                                                                                        const val PREF_WAKE_WORD = "wake_word"
                                                                                                                                                                                                                                                                                                                                                            }
                                                                                                                                                                                                                                                                                                                                                            }