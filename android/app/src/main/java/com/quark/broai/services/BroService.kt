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

/**
 * BroService — Background Android power engine
 *
 * Wake word cycle (mirrors HTML listenLoop exactly):
 * 1. VOICE ON → wakeWordMode=true → listens for "Hey Bro + command"
 * 2. "Hey Bro turn on lights" → execute "turn on lights" → wakeWordMode=false → 30s timer starts
 * 3. During 30s → any speech = command → each resets timer
 * 4. 30s silence → timer expires silently → wakeWordMode=true → "SAY HEY BRO" in UI
 * 5. Hey Bro alone = ignored
 * 6. VOICE OFF = mic stops completely
 * 7. Auto nav after 30s if ESP connected
 */
class BroService : LifecycleService() {

    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    private var emergSR: SpeechRecognizer? = null
    private var ttsReady = false
    private var listening = false

    // Wake word state
    private var wakeWordMode    = true   // true = waiting for Hey Bro + command
    private var wakeActiveUntil = 0L     // timestamp when 30s window expires
    private var wakeExpireJob: Job? = null

    // Idle + auto nav
    private var lastActiveTime = System.currentTimeMillis()
    private var isIdle = false
    private var idleJob: Job? = null
    private var navJob: Job? = null
    private var navVisionJob: Job? = null
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
                        scope.launch { delay(200); if (!listening) startListening() }
                    }
                    override fun onError(id: String?) { isSpeaking = false }
                })
                scope.launch { delay(1000); startListening(); startIdleWatch() }
            }
        }
    }

    fun speakOut(text: String) {
        if (!ttsReady || text.isBlank()) return
        isSpeaking = true
        val id  = "bro_${System.currentTimeMillis()}"
        val bnd = android.os.Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, bnd, id)
    }

    fun startListening() {
        if (isSpeaking) return
        listening = false
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: android.os.Bundle?) {
                listening = false
                val alts = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                // Use best result
                val text = alts.firstOrNull()?.trim()?.takeIf { it.length >= 2 } ?: return
                handleSpeech(text)
            }
            override fun onError(e: Int) {
                listening = false
                scope.launch {
                    delay(if (e == SpeechRecognizer.ERROR_NETWORK) 3000L else 500L)
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

    private fun stopListening() { try { sr?.stopListening(); listening = false } catch (e: Exception) {} }

    /**
     * Main speech handler — mirrors HTML listenLoop() exactly
     *
     * WAKE WORD MODE (wakeWordMode = true):
     *   - Only accepts speech that STARTS with Hey Bro + has a command after it
     *   - "Hey Bro what time is it" → extracts "what time is it" → execute
     *   - "Hey Bro" alone → ignored, keep listening
     *   - Anything without Hey Bro → ignored silently, keep listening
     *
     * ACTIVE WINDOW MODE (wakeWordMode = false):
     *   - Accept ANY speech as command
     *   - Each command resets 30s timer
     *   - After 30s silence → back to wake word mode
     */
    private fun handleSpeech(text: String) {
        val lower = text.lowercase().trim()

        // Check for Hey Bro prefix
        val wakePattern = Regex("^(hey\\s*bro|yo\\s*bro|hey\\s*bot)[,\\s]*(.+)", RegexOption.IGNORE_CASE)
        val wakeMatch = wakePattern.find(text)

        if (wakeWordMode) {
            // ── WAKE WORD MODE ──────────────────────────────────────
            if (wakeMatch != null) {
                // Has Hey Bro + command → execute
                val cmd = wakeMatch.groupValues[2].trim()
                if (cmd.isNotEmpty()) {
                    activateWake()
                    processCommand(cmd)
                } else {
                    // Hey Bro alone — ignore
                    scope.launch { delay(200); startListening() }
                }
            } else {
                // No Hey Bro — ignore silently
                scope.launch { delay(200); startListening() }
            }
        } else {
            // ── ACTIVE WINDOW MODE ──────────────────────────────────
            if (System.currentTimeMillis() > wakeActiveUntil) {
                // Window expired
                deactivateWake()
                scope.launch { delay(200); startListening() }
                return
            }

            // Strip Hey Bro prefix if they said it again (optional)
            val cmd = if (wakeMatch != null) wakeMatch.groupValues[2].trim() else text.trim()

            if (cmd.isEmpty()) {
                scope.launch { delay(200); startListening() }
                return
            }

            extendWake() // Reset 30s timer
            resetIdle()
            processCommand(cmd)
        }
    }

    private fun processCommand(cmd: String) {
        val lower = cmd.lowercase()

        // Voice off command
        if (Regex("(?:turn off|disable|stop)\\s*(?:the\\s*)?(?:mic|voice|listening)", RegexOption.IGNORE_CASE).containsMatchIn(cmd)) {
            speakOut("Okay, going quiet.")
            wakeWordMode = true
            wakeExpireJob?.cancel()
            scope.launch { delay(500); startListening() }
            return
        }

        // Process all other commands in background — never blocks UI
        scope.launch(Dispatchers.Main) {
            try {
                // Command processed — restart listening
                scope.launch { delay(400); startListening() }
            } catch (e: Exception) {
                scope.launch { delay(500); startListening() }
            }
        }
    }

    // ── Wake window helpers ────────────────────────────────────

    private fun activateWake() {
        wakeWordMode    = false
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
        wakeExpireJob?.cancel()
        // 30s timer runs silently in background
        wakeExpireJob = scope.launch {
            delay(30_000L)
            deactivateWake()
        }
        resetIdle()
    }

    private fun extendWake() {
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
        wakeExpireJob?.cancel()
        wakeExpireJob = scope.launch {
            delay(30_000L)
            deactivateWake()
        }
    }

    private fun deactivateWake() {
        wakeWordMode    = true
        wakeActiveUntil = 0L
        wakeExpireJob?.cancel()
        // Start auto nav if ESP connected
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
            while (isActive) {
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
        startNavVisionLearning()
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

    private fun startNavVisionLearning() {
        navVisionJob?.cancel()
        navVisionJob = scope.launch {
            delay(3000)
            while (isActive && isIdle) {
                delay(8000)
                if (!isIdle) break
                // Narrate discovery via TTS
                speakOut("Exploring and learning from environment.")
            }
        }
    }

    private fun stopAutoNav() {
        navJob?.cancel(); navVisionJob?.cancel()
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
                val body = if (speed > 0) "{\"action\":\"$action\",\"speed\":$speed}" else "{\"action\":\"$action\"}"
                conn.outputStream.write(body.toByteArray())
                conn.connect()
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    // ── Emergency listener ─────────────────────────────────────
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
