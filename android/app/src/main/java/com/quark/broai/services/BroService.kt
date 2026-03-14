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
import com.quark.broai.brain.AgentActions
import com.quark.broai.brain.BroBrain
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class BroService : LifecycleService() {

    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    private var ttsReady = false
    private var listening = false

    // Wake word state — mirrors HTML exactly
    private var wakeWordMode    = true   // true = waiting for Hey Bro
    private var wakeActiveUntil = 0L     // when 30s window expires

    // Idle + auto-nav
    private var isIdle      = false
    private var lastActive  = System.currentTimeMillis()
    private var navJob: Job?       = null
    private var idleJob: Job?      = null
    private var navVisionJob: Job? = null

    // Emergency listener
    private var emergSR: SpeechRecognizer? = null

    // ESP32
    private var espIP = ""
    private var espOK = false
    private val espHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

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
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BroAI::WL")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        espIP = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
            .getString(BroAIApp.K_ESP_IP, "") ?: ""
        startForegroundNotif()
        initTTS()
    }

    private fun startForegroundNotif() {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        startForeground(1, NotificationCompat.Builder(this, BroAIApp.CH_MAIN)
            .setContentTitle("Bro AI active")
            .setContentText("Say Hey Bro — always listening")
            .setSmallIcon(R.drawable.ic_bro_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build())
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language    = Locale.US
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(0.85f)  // Jarvis-style lower pitch
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { isSpeaking = true }
                    override fun onDone(id: String?)  {
                        isSpeaking = false
                        scope.launch { delay(200); if (!listening) startListening() }
                    }
                    override fun onError(id: String?) { isSpeaking = false }
                })
                scope.launch {
                    delay(1200)
                    startListening()
                    startIdleWatch()
                    startEmergencyListener()
                }
            }
        }
    }

    fun speakOut(text: String) {
        if (!ttsReady || text.isBlank()) return
        stopListening()
        isSpeaking = true
        val id  = "bro_${System.currentTimeMillis()}"
        val bnd = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
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
                val text = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: return
                if (text.length < 2) { scope.launch { delay(200); startListening() }; return }
                onSpeech(text)
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
        } catch (e: Exception) {
            scope.launch { delay(1000); startListening() }
        }
    }

    private fun stopListening() { try { sr?.stopListening(); listening = false } catch (e: Exception) {} }

    // Main speech handler — mirrors HTML listenLoop() exactly
    private fun onSpeech(text: String) {
        val lower  = text.lowercase().trim()
        val isWake = lower.contains("hey bro") || lower.contains("yo bro") || lower.contains("hey bot")

        // Wake word mode — waiting for Hey Bro
        if (wakeWordMode) {
            if (isWake) { activateWake(); speakOut("Yeah?") }
            else scope.launch { delay(200); startListening() }
            return
        }

        // Check if 30s window expired
        if (System.currentTimeMillis() > wakeActiveUntil) {
            deactivateWake()
            scope.launch { delay(200); startListening() }
            return
        }

        extendWake()  // reset 30s timer on every command
        resetIdle()

        // Strip wake word prefix if repeated
        val cmd = text.replace(Regex("^(hey bro|yo bro|hey bot)[,\\s]*", RegexOption.IGNORE_CASE), "")
            .trim().ifEmpty { text }

        // Voice off command
        if (Regex("(?:stop|disable|turn off) voice|voice off", RegexOption.IGNORE_CASE).containsMatchIn(cmd)) {
            speakOut("Okay, going quiet.")
            wakeWordMode = true
            scope.launch { delay(500); startListening() }
            return
        }

        // Process command
        scope.launch {
            try {
                val handled = AgentActions.handle(this@BroService, cmd)
                if (!handled) {
                    val reply = withContext(Dispatchers.IO) { BroBrain.ask(this@BroService, cmd) }
                    handleRobotTags(reply)
                    speakOut(reply)
                }
            } catch (e: Exception) {
                speakOut("Something went wrong. Try again.")
                scope.launch { delay(400); startListening() }
            }
        }
    }

    private fun activateWake() {
        wakeWordMode    = false
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
        resetIdle()
    }

    private fun extendWake() {
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
    }

    private fun deactivateWake() {
        wakeWordMode    = true
        wakeActiveUntil = 0L
        // After 30s silence — start auto-nav if ESP connected
        val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
        if (espOK && prefs.getBoolean(BroAIApp.S_AUTO_NAV, true)) {
            isIdle = true
            startAutoNav()
        }
    }

    private fun resetIdle() {
        lastActive = System.currentTimeMillis()
        if (isIdle) { isIdle = false; stopAutoNav() }
    }

    // Idle watch — 30s threshold same as HTML
    private fun startIdleWatch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (isActive) {
                delay(5000)
                val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
                if (prefs.getBoolean(BroAIApp.S_AUTO_NAV, true) && espOK &&
                    System.currentTimeMillis() - lastActive > 30_000L && !isIdle) {
                    isIdle = true
                    startAutoNav()
                }
            }
        }
    }

    // Auto-nav — mirrors HTML runAutoNav() exactly
    // forward/obstacle-avoid loop with ultrasonic + IR sensors
    private fun startAutoNav() {
        navJob?.cancel()
        startNavVisionLearning()
        navJob = scope.launch {
            while (isActive && isIdle && espOK) {
                try {
                    val sensors = fetchSensors()
                    val us  = sensors.optInt("ultrasonic", 100)
                    val ir1 = sensors.optBoolean("ir1", false)
                    val ir2 = sensors.optBoolean("ir2", false)
                    if (us < 25 || ir1 || ir2) {
                        // Obstacle — turn randomly like HTML
                        espCmd(if (Math.random() > 0.5) "left" else "right", 40)
                        delay(700 + (Math.random() * 400).toLong())
                        espCmd("stop")
                    } else {
                        espCmd("forward", 42)
                    }
                    delay(1800 + (Math.random() * 800).toLong())
                } catch (e: Exception) { delay(2000) }
            }
            espCmd("stop")
        }
    }

    // Vision learning during auto-nav — narrates discoveries every 8s
    private fun startNavVisionLearning() {
        navVisionJob?.cancel()
        navVisionJob = scope.launch {
            delay(3000)
            while (isActive && isIdle) {
                delay(8000)
                if (!isIdle) break
                val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
                val kG = prefs.getString(BroAIApp.K_GEMINI, "") ?: ""
                if (kG.isNotEmpty()) {
                    val narration = withContext(Dispatchers.IO) {
                        BroBrain.navNarrate(this@BroService, "exploring room autonomously")
                    }
                    // Save to self-learned facts
                    BroBrain.selfLearned.add(0, "[Nav] ${narration.take(80)}")
                    if (BroBrain.selfLearned.size > 50)
                        BroBrain.selfLearned.removeAt(BroBrain.selfLearned.size - 1)
                    // Speak discovery aloud
                    speakOut(narration)
                }
            }
        }
    }

    private fun stopAutoNav() {
        navJob?.cancel(); navVisionJob?.cancel()
        isIdle = false
        if (espOK) scope.launch(Dispatchers.IO) { espCmd("stop") }
    }

    private fun espCmd(action: String, speed: Int = 0) {
        if (!espOK || espIP.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val b = JSONObject().apply { put("action", action); if (speed > 0) put("speed", speed) }
                espHttp.newCall(Request.Builder()
                    .url("http://$espIP/command")
                    .post(b.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute().close()
            } catch (e: Exception) {}
        }
    }

    private suspend fun fetchSensors(): JSONObject = withContext(Dispatchers.IO) {
        try {
            val r = espHttp.newCall(Request.Builder().url("http://$espIP/sensors").build()).execute()
            JSONObject(r.body?.string() ?: "{}")
        } catch (e: Exception) { JSONObject() }
    }

    // Robot movement tags — mirrors HTML handleRobotCmds()
    private fun handleRobotTags(reply: String) {
        if (!espOK) return
        val t = reply.uppercase()
        when {
            t.contains("[MOVE_FORWARD]")  -> espCmd("forward", 50)
            t.contains("[MOVE_BACKWARD]") -> espCmd("backward", 50)
            t.contains("[TURN_LEFT]")     -> espCmd("left", 50)
            t.contains("[TURN_RIGHT]")    -> espCmd("right", 50)
            t.contains("[STOP]")          -> espCmd("stop")
            t.contains("[TURN_90L]")      -> { espCmd("left", 40);  scope.launch { delay(800);  espCmd("stop") } }
            t.contains("[TURN_90R]")      -> { espCmd("right", 40); scope.launch { delay(800);  espCmd("stop") } }
            t.contains("[TURN_180]")      -> { espCmd("right", 40); scope.launch { delay(1600); espCmd("stop") } }
        }
        when {
            t.contains("[HEAD_LEFT]")   -> espCmd("servo_head_45")
            t.contains("[HEAD_RIGHT]")  -> espCmd("servo_head_135")
            t.contains("[HEAD_CENTER]") -> espCmd("servo_head_90")
        }
    }

    // Emergency listener — mirrors HTML listenEmerg()
    // Listens for: help, sos, danger, fire, intruder, attack, mayday
    private fun startEmergencyListener() {
        scope.launch { delay(2000); startEmergSR() }
    }

    private fun startEmergSR() {
        emergSR?.destroy()
        emergSR = SpeechRecognizer.createSpeechRecognizer(this)
        emergSR?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: android.os.Bundle?) {
                val text = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: return
                val emergWords = listOf("help", "emergency", "sos", "danger",
                    "intruder", "attack", "fire", "mayday", "call police")
                if (emergWords.any { text.contains(it) }) {
                    speakOut("Emergency detected! Do you need help?")
                }
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
        // Self-restart — never stays dead
        val i = Intent(this, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }
}
