package com.quark.broai.services
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.quark.broai.BroAIApp
import com.quark.broai.MainActivity
import com.quark.broai.R
import kotlinx.coroutines.*
import java.util.Locale

/**
 * BroService — runs silently in background
 * Does NOT start mic on create
 * Does NOT make any sound on create
 * Mic only starts when HTML calls startListening() via AndroidBridge
 */
class BroService : LifecycleService() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        var instance: BroService? = null
        var isSpeaking = false
        fun speak(t: String) { instance?.speakOut(t) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // WakeLock — keeps service alive silently
        val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(BroAIApp.K_WAKELCK, true)) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BroAI::WL")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }
        // Show silent notification
        startForegroundNotif()
        // Init TTS silently — no sound, no mic
        initTTS()
        // NO mic started here — NEVER on app open
        // NO emergency listener here — causes cling sounds
        // Mic only starts when user taps VOICE button in HTML
    }

    private fun startForegroundNotif() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        startForeground(1, NotificationCompat.Builder(this, BroAIApp.CH_MAIN)
            .setContentTitle("Bro AI")
            .setContentText("Tap app to use Bro AI")
            .setSmallIcon(R.drawable.ic_bro_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build())
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(0.85f)
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { isSpeaking = true }
                    override fun onDone(id: String?)  { isSpeaking = false }
                    override fun onError(id: String?) { isSpeaking = false }
                })
            }
        }
    }

    fun speakOut(text: String) {
        if (!ttsReady || text.isBlank()) return
        isSpeaking = true
        val id  = "bro_${System.currentTimeMillis()}"
        val bnd = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, bnd, id)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        super.onStartCommand(i, f, id)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        wakeLock?.release()
        // Restart self silently
        val i = Intent(this, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    override fun onBind(i: Intent): IBinder? { super.onBind(i); return null }
}
