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
    private var ttsReady = false
    private var micOn = false
    private var wakeWordMode = true
    private var wakeActiveUntil = 0L
    private var wakeExpireJob: Job? = null
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
        initTTS()
        // NO mic, NO sounds, NO listeners on create
        // Everything waits for user to press VOICE button
    }

    private fun startForegroundNotif() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        startForeground(1, NotificationCompat.Builder(this, BroAIApp.CH_MAIN)
            .setContentTitle("Bro AI")
            .setContentText("Tap app to start")
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
                        // Resume listening after speaking if mic is on
                        if (micOn) scope.launch { delay(200); listen() }
                    }
                    override fun onError(id: String?) { isSpeaking = false }
                })
            }
        }
    }

    // Called by AndroidBridge when VOICE button pressed ON
    fun micOn() {
        if (micOn) return
        micOn = true
        wakeWordMode = true
        wakeActiveUntil = 0L
        listen()
    }

    // Called by AndroidBridge when VOICE button pressed OFF
    fun micOff() {
        micOn = false
        wakeWordMode = true
        wakeActiveUntil = 0L
        wakeExpireJob?.cancel()
        stopListen()
    }

    // Start listening — one continuous session
    // On end of speech → immediately restarts WITHOUT any sound
    private fun listen() {
        if (!micOn || isSpeaking) return
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(res: Bundle?) {
                if (!micOn) return
                val text = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: run { if (micOn) listen(); return }
                if (text.length < 2) { if (micOn) listen(); return }
                handleSpeech(text)
            }
            override fun onError(e: Int) {
                if (!micOn) return
                // Silent restart — no delay that causes cling
                scope.launch {
                    delay(if (e == SpeechRecognizer.ERROR_NETWORK) 2000L else 50L)
                    if (micOn && !isSpeaking) listen()
                }
            }
            // Immediately restart on end of speech — this is what makes it continuous
            override fun onEndOfSpeech() {
                if (micOn && !isSpeaking) scope.launch { delay(50); listen() }
            }
            override fun onReadyForSpeech(p: Bundle?) {}
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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            })
        } catch (e: Exception) {
            scope.launch { delay(200); if (micOn) listen() }
        }
    }

    private fun stopListen() {
        try { sr?.stopListening(); sr?.destroy(); sr = null } catch (e: Exception) {}
    }

    private fun handleSpeech(text: String) {
        val wakePattern = Regex(
            "^(hey\\s*bro|yo\\s*bro|hey\\s*bot)[,\\s]+(.+)",
            RegexOption.IGNORE_CASE
        )
        val wakeMatch = wakePattern.find(text)

        if (wakeWordMode) {
            if (wakeMatch != null && wakeMatch.groupValues[2].trim().isNotEmpty()) {
                // Hey Bro + command — activate window and process
                activateWake()
                val cmd = wakeMatch.groupValues[2].trim()
                handleCommand(cmd)
            } else {
                // No Hey Bro or Hey Bro alone — ignore, keep listening
                listen()
            }
        } else {
            if (System.currentTimeMillis() > wakeActiveUntil) {
                deactivateWake(); listen(); return
            }
            val cmd = if (wakeMatch != null) wakeMatch.groupValues[2].trim() else text.trim()
            if (cmd.isEmpty()) { listen(); return }
            extendWake()
            handleCommand(cmd)
        }
    }

    private fun handleCommand(cmd: String) {
        if (Regex("(?:turn off|disable|stop)\\s+(?:voice|mic|listening)", RegexOption.IGNORE_CASE).containsMatchIn(cmd)) {
            speakOut("Okay going quiet.")
            micOff()
            return
        }
        // All other commands handled by HTML via WebView JS
        // Just keep listening
        scope.launch { delay(300); if (micOn) listen() }
    }

    private fun activateWake() {
        wakeWordMode = false
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
        wakeExpireJob?.cancel()
        wakeExpireJob = scope.launch { delay(30_000L); if (micOn) deactivateWake() }
    }

    private fun extendWake() {
        wakeActiveUntil = System.currentTimeMillis() + 30_000L
        wakeExpireJob?.cancel()
        wakeExpireJob = scope.launch { delay(30_000L); if (micOn) deactivateWake() }
    }

    private fun deactivateWake() {
        wakeWordMode = true
        wakeActiveUntil = 0L
        wakeExpireJob?.cancel()
    }

    fun speakOut(text: String) {
        if (!ttsReady || text.isBlank()) return
        isSpeaking = true
        val id = "bro_${System.currentTimeMillis()}"
        val bnd = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, bnd, id)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        super.onStartCommand(i, f, id); return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy(); instance = null; scope.cancel()
        tts?.stop(); tts?.shutdown(); sr?.destroy(); wakeLock?.release()
        val i = Intent(this, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    override fun onBind(i: Intent): IBinder? { super.onBind(i); return null }
}
