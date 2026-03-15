package com.quark.broai.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.quark.broai.BroAIApp
import com.quark.broai.MainActivity
import com.quark.broai.R
import kotlinx.coroutines.*
import java.util.Locale

class BroService : LifecycleService() {

    private var tts: TextToSpeech? = null
    private var sr:  SpeechRecognizer? = null
    private var emergSR: SpeechRecognizer? = null
    private var ttsReady = false
    private var wakeWordMode = true
    private var wakeActiveUntil = 0L
    private var listening = false
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
        val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(BroAIApp.K_WAKELCK, true)) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BroAI::WL")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }
        startForegroundNotif()
        if (prefs.getBoolean(BroAIApp.K_BG_WAKE, true)) initTTS()
        if (prefs.getBoolean(BroAIApp.K_EMERGENCY, true)) scope.launch { delay(3000); startEmergSR() }
    }

    private fun startForegroundNotif() {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        startForeground(1, NotificationCompat.Builder(this, BroAIApp.CH_MAIN)
            .setContentTitle("Bro AI active")
            .setContentText("Say Hey Bro anytime")
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
                    override fun onDone(id: String?) {
                        isSpeaking = false
                        scope.launch { delay(300); if (!listening) startListening() }
                    }
                    override fun onError(id: String?) { isSpeaking = false }
                })
                scope.launch { delay(1000); startListening() }
            }
        }
    }

    fun speakOut(text: String) {
        if (!ttsReady || text.isBlank()) return
        isSpeaking = true
        val id  = "bro_${System.currentTimeMillis()}"
        val bnd = android.os.Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts?.speak(text.take(300), TextToSpeech.QUEUE_FLUSH, bnd, id)
    }

    fun startListening() {
        if (isSpeaking) return
        listening = false; sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: android.os.Bundle?) {
                listening = false
                val text = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: return
                if (text.length < 2) { scope.launch { delay(200); startListening() }; return }
                handleSpeech(text)
            }
            override fun onError(e: Int) {
                listening = false
                scope.launch {
                    delay(if (e == SpeechRecognizer.ERROR_NETWORK) 3000L else 600L)
                    if (!isSpeaking) startListening()
                }
            }
            override fun onReadyForSpeech(p: android.os.Bundle?) { listening = true }
            override fun onEndOfSpeech() {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(p: ByteArray?) {}
            override fun onPartialResults(p: android.os.Bundle?) {}
            override fun onEvent(p: Int, p1: android.os.Bundle?) {}
            override fun onRmsChanged(p: Float) {}
        })
        try {
            sr?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        } catch (e: Exception) { scope.launch { delay(1000); startListening() } }
    }

    private fun handleSpeech(text: String) {
        val lower = text.lowercase()
        val isWake = lower.contains("hey bro") || lower.contains("yo bro") || lower.contains("hey bot")
        if (wakeWordMode) {
            if (isWake) {
                wakeWordMode = false
                wakeActiveUntil = System.currentTimeMillis() + 30_000L
                speakOut("Yeah?")
            } else scope.launch { delay(200); startListening() }
            return
        }
        if (System.currentTimeMillis() > wakeActiveUntil) {
            wakeWordMode = true
            scope.launch { delay(200); startListening() }
            return
        }
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
        scope.launch { delay(300); startListening() }
    }

    // Emergency listener
    private fun startEmergSR() {
        emergSR?.destroy()
        emergSR = SpeechRecognizer.createSpeechRecognizer(this)
        emergSR?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: android.os.Bundle?) {
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: return
                val words = listOf("help","sos","danger","fire","intruder","attack","mayday","call police")
                if (words.any { t.contains(it) }) speakOut("Emergency detected! Do you need help?")
                scope.launch { delay(8000); startEmergSR() }
            }
            override fun onError(e: Int) { scope.launch { delay(10_000L); startEmergSR() } }
            override fun onEndOfSpeech() { scope.launch { delay(8000); startEmergSR() } }
            override fun onReadyForSpeech(p: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(p: ByteArray?) {}
            override fun onPartialResults(p: android.os.Bundle?) {}
            override fun onEvent(p: Int, p1: android.os.Bundle?) {}
            override fun onRmsChanged(p: Float) {}
        })
        try {
            emergSR?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            })
        } catch (e: Exception) { scope.launch { delay(12_000L); startEmergSR() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId); return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy(); instance = null; scope.cancel()
        tts?.stop(); tts?.shutdown(); sr?.destroy(); emergSR?.destroy(); wakeLock?.release()
        val i = Intent(this, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }
}
