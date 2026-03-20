package com.quark.broai.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
    private var sr: SpeechRecognizer? = null
    private var emergSR: SpeechRecognizer? = null
    private var ttsReady = false

    // Mic state — only ON when user explicitly pressed VOICE button
    private var micOn = false

    // Wake word state
    // true  = waiting for "Hey Bro + command"
    // false = 30s active window, any speech accepted
    private var wakeWordMode = true
    private var wakeActiveUntil = 0L
    private var wakeExpireJob: Job? = null

    // Auto nav
    private var lastActiveTime = System.currentTimeMillis()
    private var isIdle = false
    private var idleJob: Job? = null
    private var navJob: Job? = null
    private var espIP = ""

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
        espIP = prefs.getString(BroAIApp.K_ESP_IP, "") ?: ""
        if (prefs.getBoolean(BroAIApp.K_WAKELCK, true)) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BroAI::WL")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }
        startForegroundNotif()
        initTTS()
        // Emergency listener starts independently — no cling sound
        if (prefs.getBoolean(BroAIApp.K_EMERGENCY, true)) {
            scope.launch { delay(3000); startEmergSR() }
        }
        // DO NOT start mic here — only starts when user presses VOICE button
    }

    private fun startForegroundNotif() {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        startForeground(1, NotificationCompat.Builder(this, BroAIApp.CH_MAIN)
            .setContentTitle("Bro AI active")
            .setContentText("Tap VOICE in Bro Bot to start")
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
                        // Resume listening after speaking — only if mic is ON
                        if (micOn) scope.launch { delay(300); resumeListening() }
                    }
                    override fun onError(id: String?) { isSpeaking = false }
                })
            }
        }
    }

    // ── Called by HTML when user taps VOICE button ─────────────
    // This is the ONLY way the mic turns on
    fun micEnable() {
        if (micOn) return
        micOn = true
        wakeWordMode = true
        wakeActiveUntil = 0L
        startIdleWatch()
        startListening()
    }

    // ── Called by HTML when user taps VOICE button again ────────
    fun micDisable() {
        micOn = false
        wakeWordMode = true
        wakeActiveUntil = 0L
        wakeExpireJob?.cancel()
        stopListening()
        stopAutoNav()
    }

    // ── Start listening — uses continuous mode to avoid restarts ─
    private fun startListening() {
        if (!micOn || isSpeaking) return
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: Bundle?) {
                if (!micOn) return
                val alts = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                val text = alts.firstOrNull()?.trim()?.takeIf { it.length >= 2 } ?: run {
                    resumeListening(); return
                }
                handleSpeech(text)
            }
            override fun onError(e: Int) {
                if (!micOn) return
                // Only resume on real errors — use longer delay to avoid cling sounds
                scope.launch {
                    delay(when (e) {
                        SpeechRecognizer.ERROR_NO_MATCH        -> 100L
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> 100L
                        SpeechRecognizer.ERROR_NETWORK         -> 3000L
                        SpeechRecognizer.ERROR_AUDIO           -> 500L
                        else                                   -> 300L
                    })
                    if (micOn && !isSpeaking) resumeListening()
                }
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onEndOfSpeech() {
                // Immediately restart — this is what makes it continuous
                if (micOn && !isSpeaking) scope.launch { delay(50); resumeListening() }
            }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(p: ByteArray?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(p: Int, p1: Bundle?) {}
            override fun onRmsChanged(p: Float) {}
        })
        try {
            sr?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Longer silence before timing out — reduces restarts
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            })
        } catch (e: Exception) {
            scope.launch { delay(500); if (micOn) resumeListening() }
        }
    }

    private fun resumeListening() {
        if (!micOn || isSpeaking) return
        try { sr?.stopListening() } catch (e: Exception) {}
        startListening()
    }

    private fun stopListening() {
        try { sr?.stopListening(); sr?.destroy(); sr = null } catch (e: Exception) {}
    }

    // ── Speech handler ──────────────────────────────────────────
    // Wake word cycle:
    // wakeWordMode=true  → only accepts "Hey Bro + command" together
    // wakeWordMode=false → accepts anything for 30s, each resets timer
    private fun handleSpeech(text: String) {
        val wakePattern = Regex("^(hey\\s*bro|yo\\s*bro|hey\\s*bot)[,\\s]+(.+)", RegexOption.IGNORE_CASE)
        val wakeMatch = wakePattern.find(text)

        if (wakeWordMode) {
            if (wakeMatch != null) {
                val cmd = wakeMatch.groupValues[2].trim()
                if (cmd.isNotEmpty()) {
                    activateWake()
                    processCommand(cmd)
                } else {
                    resumeListening()
                }
            } else {
                // Not Hey Bro + command — ignore silently, keep listening
                resumeListening()
            }
        } else {
            if (System.currentTimeMillis() > wakeActiveUntil) {
                deactivateWake()
                resumeListening()
                return
            }
            val cmd = if (wakeMatch != null) wakeMatch.groupValues[2].trim() else text.trim()
            if (cmd.isEmpty()) { resumeListening(); return }
            extendWake()
            resetIdle()
            processCommand(cmd)
        }
    }

    private fun processCommand(cmd: String) {
        val lower = cmd.lowercase()
        // Voice off
        if (Regex("(?:turn off|disable|stop)\\s*(?:the\\s*)?(?:mic|voice|listening)", RegexOption.IGNORE_CASE).containsMatchIn(cmd)) {
            speakOut("Okay going quiet.")
            micDisable()
            return
        }
        // All other commands handled by HTML via WebView
        // Just resume listening after processing
        scope.launch { delay(300); resumeListening() }
    }

    // ── Wake window helpers ────────────────────────────────────
    private fun activateWake() {
        wakeWordMode = false
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
        wakeExpireJob?.cancel()
        wakeExpireJob = scope.launch {
            delay(30_000L)
            if (micOn) deactivateWake()
        }
        resetIdle()
    }

    private fun extendWake() {
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
        wakeExpireJob?.cancel()
        wakeExpireJob = scope.launch {
            delay(30_000L)
            if (micOn) deactivateWake()
        }
    }

    private fun deactivateWake() {
        wakeWordMode = true
        wakeActiveUntil = 0L
        wakeExpireJob?.cancel()
        val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
        if (espIP.isNotEmpty() && prefs.getBoolean(BroAIApp.S_AUTO_NAV, true)) {
            isIdle = true
            startAutoNav()
        }
    }

    private fun resetIdle() {
        lastActiveTime = System.currentTimeMillis()
        if (isIdle) { isIdle = false; stopAutoNav() }
    }

    // ── Idle watch ─────────────────────────────────────────────
    private fun startIdleWatch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (isActive && micOn) {
                delay(5000)
                val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
                if (prefs.getBoolean(BroAIApp.S_AUTO_NAV, true) &&
                    espIP.isNotEmpty() &&
                    System.currentTimeMillis() - lastActiveTime > 30_000L &&
                    !isIdle) {
                    isIdle = true
                    startAutoNav()
                }
            }
        }
    }

    // ── Auto nav ───────────────────────────────────────────────
    private fun startAutoNav() {
        navJob?.cancel()
        navJob = scope.launch {
            while (isActive && isIdle && espIP.isNotEmpty()) {
                try {
                    espCmd("forward", 42)
                    delay(1800 + (Math.random() * 800).toLong())
                } catch (e: Exception) { delay(2000) }
            }
            espCmd("stop")
        }
    }

    private fun stopAutoNav() {
        navJob?.cancel()
        isIdle = false
        if (espIP.isNotEmpty()) espCmd("stop")
    }

    private fun espCmd(action: String, speed: Int = 0) {
        if (espIP.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://$espIP/command")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                val body = if (speed > 0) """{"action":"$action","speed":$speed}""" else """{"action":"$action"}"""
                conn.outputStream.write(body.toByteArray())
                conn.connect()
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    // ── Emergency listener — independent of main mic ────────────
    private fun startEmergSR() {
        emergSR?.destroy()
        emergSR = SpeechRecognizer.createSpeechRecognizer(this)
        emergSR?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: Bundle?) {
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: return
                val words = listOf("help","sos","danger","fire","intruder","attack","mayday","call police")
                if (words.any { t.contains(it) }) speakOut("Emergency detected! Do you need help?")
                scope.launch { delay(8000); startEmergSR() }
            }
            override fun onError(e: Int) { scope.launch { delay(10_000L); startEmergSR() } }
            override fun onEndOfSpeech() { scope.launch { delay(8000); startEmergSR() } }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(p: ByteArray?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(p: Int, p1: Bundle?) {}
            override fun onRmsChanged(p: Float) {}
        })
        try {
            emergSR?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            })
        } catch (e: Exception) { scope.launch { delay(12_000L); startEmergSR() } }
    }

    fun speakOut(text: String) {
        if (!ttsReady || text.isBlank()) return
        isSpeaking = true
        val id  = "bro_${System.currentTimeMillis()}"
        val bnd = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, bnd, id)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        tts?.stop(); tts?.shutdown()
        sr?.destroy(); emergSR?.destroy()
        wakeLock?.release()
        val i = Intent(this, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }
}
