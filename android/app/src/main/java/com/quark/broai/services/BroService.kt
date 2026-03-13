package com.quark.broai.services
import android.app.PendingIntent; import android.content.Context; import android.content.Intent
import android.os.Build; import android.os.IBinder; import android.os.PowerManager
import android.speech.RecognitionListener; import android.speech.RecognizerIntent; import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech; import android.speech.tts.UtteranceProgressListener; import android.util.Log
import androidx.core.app.NotificationCompat; import androidx.lifecycle.LifecycleService
import com.quark.broai.BroAIApp; import com.quark.broai.MainActivity; import com.quark.broai.R
import com.quark.broai.brain.AgentActions; import com.quark.broai.brain.BroBrain
import kotlinx.coroutines.*; import okhttp3.MediaType.Companion.toMediaType; import okhttp3.OkHttpClient
import okhttp3.Request; import okhttp3.RequestBody.Companion.toRequestBody; import org.json.JSONObject
import java.util.Locale; import java.util.concurrent.TimeUnit

/**
 * BroService — Core engine.
 * Wake word: Hey Bro / Yo Bro / Hey Bot → "Yeah?" → 30s active window
 * Each command extends window by 30s.
 * After 30s silence → deactivateWake() → if ESP connected → startAutoNav()
 * Auto-nav: forward/obstacle-avoid loop + vision narration every 8s
 */
class BroService : LifecycleService() {
    private var tts:TextToSpeech?=null; private var sr:SpeechRecognizer?=null; private var ttsReady=false
    private var wakeWordMode=true; private var wakeActiveUntil=0L; private var listening=false
    private var isIdle=false; private var lastActive=System.currentTimeMillis()
    private var navJob:Job?=null; private var idleJob:Job?=null; private var navVisionJob:Job?=null
    private var emergSR:SpeechRecognizer?=null
    private var espIP=""; private var espOK=false
    private val scope=CoroutineScope(Dispatchers.Main+SupervisorJob())
    private val espHttp=OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS).build()
    private var wake:PowerManager.WakeLock?=null
    companion object {
        var instance:BroService?=null; var isSpeaking=false
        fun speak(t:String){instance?.speakOut(t)}
    }
    override fun onCreate(){
        super.onCreate(); instance=this
        wake=(getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BroAI::WL").apply{acquire(12*60*60*1000L)}
        espIP=getSharedPreferences(BroAIApp.PREFS,MODE_PRIVATE).getString(BroAIApp.K_ESP_IP,"")?:""
        startFg(); initTTS()
    }
    private fun startFg(){
        val pi=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),if(Build.VERSION.SDK_INT>=23)PendingIntent.FLAG_IMMUTABLE else 0)
        startForeground(1,NotificationCompat.Builder(this,BroAIApp.CH_MAIN).setContentTitle("Bro AI active").setContentText("Say Hey Bro — always listening").setSmallIcon(R.drawable.ic_bro_notif).setContentIntent(pi).setOngoing(true).setSilent(true).build())
    }
    private fun initTTS(){
        tts=TextToSpeech(this){s->if(s==TextToSpeech.SUCCESS){
            tts?.language=Locale.US; tts?.setSpeechRate(0.95f); tts?.setPitch(0.85f); ttsReady=true
            tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){isSpeaking=true}
                override fun onDone(id:String?){isSpeaking=false;scope.launch{delay(200);if(!listening)startListening()}}
                override fun onError(id:String?){isSpeaking=false}
            })
            scope.launch{delay(1200);startListening();startIdleWatch();startEmerg()}
        }}
    }
    fun speakOut(text:String){
        if(!ttsReady||text.isBlank()) return; stopSR(); isSpeaking=true
        val id="bro_${System.currentTimeMillis()}"
        val b=android.os.Bundle().apply{putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,id)}
        tts?.speak(text.take(400),TextToSpeech.QUEUE_FLUSH,b,id)
    }
    fun startListening(){
        if(isSpeaking){return}; listening=false; sr?.destroy()
        sr=SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object:RecognitionListener{
            override fun onResults(res:android.os.Bundle?){
                listening=false
                val t=res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?:return
                if(t.length<2){scope.launch{delay(200);startListening()};return}
                onSpeech(t)
            }
            override fun onError(e:Int){listening=false;scope.launch{delay(if(e==SpeechRecognizer.ERROR_NETWORK)3000L else 500L);if(!isSpeaking)startListening()}}
            override fun onReadyForSpeech(p:android.os.Bundle?){listening=true}
            override fun onEndOfSpeech(){}; override fun onBeginningOfSpeech(){}; override fun onBufferReceived(p:ByteArray?){}
            override fun onPartialResults(p:android.os.Bundle?){}; override fun onEvent(p:Int,p1:android.os.Bundle?){}; override fun onRmsChanged(p:Float){}
        })
        try{sr?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault());putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)})}
        catch(e:Exception){scope.launch{delay(1000);startListening()}}
    }
    private fun stopSR(){try{sr?.stopListening();listening=false}catch(e:Exception){}}
    private fun onSpeech(text:String){
        val lower=text.lowercase().trim()
        val isWake=lower.contains("hey bro")||lower.contains("yo bro")||lower.contains("hey bot")
        if(wakeWordMode){
            if(isWake){activateWake();speakOut("Yeah?")}else scope.launch{delay(200);startListening()}
            return
        }
        if(System.currentTimeMillis()>wakeActiveUntil){deactivateWake();scope.launch{delay(200);startListening()};return}
        extendWake(); resetIdle()
        val cmd=text.replace(Regex("^(hey bro|yo bro|hey bot)[,\\s]*",RegexOption.IGNORE_CASE),"").trim().ifEmpty{text}
        if(Regex("(?:stop|disable|turn off) voice|voice off",RegexOption.IGNORE_CASE).containsMatchIn(cmd)){speakOut("Okay, going quiet.");wakeWordMode=true;scope.launch{delay(500);startListening()};return}
        scope.launch{
            try{
                val handled=AgentActions.handle(this@BroService,cmd)
                if(!handled){
                    val reply=withContext(Dispatchers.IO){BroBrain.ask(this@BroService,cmd)}
                    handleRobotTags(reply); speakOut(reply)
                }
            }catch(e:Exception){speakOut("Something went wrong.");scope.launch{delay(400);startListening()}}
        }
    }
    private fun activateWake(){wakeWordMode=false;wakeActiveUntil=System.currentTimeMillis()+30_000L;resetIdle()}
    private fun extendWake(){wakeActiveUntil=System.currentTimeMillis()+30_000L}
    private fun deactivateWake(){
        wakeWordMode=true;wakeActiveUntil=0L
        val prefs=getSharedPreferences(BroAIApp.PREFS,MODE_PRIVATE)
        if(espOK&&prefs.getBoolean(BroAIApp.S_AUTO_NAV,true)){isIdle=true;startAutoNav()}
    }
    private fun resetIdle(){lastActive=System.currentTimeMillis();if(isIdle){isIdle=false;stopAutoNav()}}
    private fun startIdleWatch(){
        idleJob?.cancel()
        idleJob=scope.launch{while(isActive){delay(5000)
            val prefs=getSharedPreferences(BroAIApp.PREFS,MODE_PRIVATE)
            if(prefs.getBoolean(BroAIApp.S_AUTO_NAV,true)&&espOK&&System.currentTimeMillis()-lastActive>30_000L&&!isIdle){isIdle=true;startAutoNav()}
        }}
    }
    // ── Auto nav — forward/avoid loop mirrors HTML runAutoNav() ──
    private fun startAutoNav(){
        Log.d("BroAI","[AutoNav] started"); navJob?.cancel(); startNavVision()
        navJob=scope.launch{
            while(isActive&&isIdle&&espOK){
                try{
                    val sd=fetchSensors(); val us=sd.optInt("ultrasonic",100)
                    val ir1=sd.optBoolean("ir1",false); val ir2=sd.optBoolean("ir2",false)
                    if(us<25||ir1||ir2){
                        espSend(if(Math.random()>0.5)"left" else "right",40)
                        delay(700+(Math.random()*400).toLong()); espSend("stop")
                    } else espSend("forward",42)
                    delay(1800+(Math.random()*800).toLong())
                }catch(e:Exception){delay(2000)}
            }
            espSend("stop")
        }
    }
    // ── Vision learning during nav ─────────────────────────────
    private fun startNavVision(){
        navVisionJob?.cancel()
        navVisionJob=scope.launch{
            delay(3000)
            while(isActive&&isIdle){
                delay(8000); if(!isIdle) break
                val prefs=getSharedPreferences(BroAIApp.PREFS,MODE_PRIVATE)
                val kG=prefs.getString(BroAIApp.K_GEMINI,"")?:""
                if(kG.isNotEmpty()){
                    val narration=withContext(Dispatchers.IO){BroBrain.navNarrate(this@BroService,"exploring a room autonomously")}
                    BroBrain.selfLearned.add(0,"[Nav] ${narration.take(80)}")
                    if(BroBrain.selfLearned.size>50) BroBrain.selfLearned.removeAt(BroBrain.selfLearned.size-1)
                    speakOut(narration)
                }
            }
        }
    }
    private fun stopAutoNav(){navJob?.cancel();navVisionJob?.cancel();isIdle=false;if(espOK)scope.launch(Dispatchers.IO){espSend("stop")}}
    private fun espSend(action:String,speed:Int=0){
        if(!espOK||espIP.isEmpty()) return
        scope.launch(Dispatchers.IO){try{
            val b=JSONObject().apply{put("action",action);if(speed>0)put("speed",speed)}
            espHttp.newCall(Request.Builder().url("http://$espIP/command").post(b.toString().toRequestBody("application/json".toMediaType())).build()).execute().close()
        }catch(e:Exception){}}
    }
    private suspend fun fetchSensors():JSONObject=withContext(Dispatchers.IO){try{
        val r=espHttp.newCall(Request.Builder().url("http://$espIP/sensors").build()).execute()
        JSONObject(r.body?.string()?:"{}") }catch(e:Exception){JSONObject()}}
    private fun handleRobotTags(reply:String){
        if(!espOK) return; val t=reply.uppercase()
        when{
            t.contains("[MOVE_FORWARD]") ->espSend("forward",50)
            t.contains("[MOVE_BACKWARD]")->espSend("backward",50)
            t.contains("[TURN_LEFT]")   ->espSend("left",50)
            t.contains("[TURN_RIGHT]")  ->espSend("right",50)
            t.contains("[STOP]")        ->espSend("stop")
            t.contains("[TURN_90L]")    ->{espSend("left",40);scope.launch{delay(800);espSend("stop")}}
            t.contains("[TURN_90R]")    ->{espSend("right",40);scope.launch{delay(800);espSend("stop")}}
            t.contains("[TURN_180]")    ->{espSend("right",40);scope.launch{delay(1600);espSend("stop")}}
        }
        when{t.contains("[HEAD_LEFT]")->espSend("servo_head_45");t.contains("[HEAD_RIGHT]")->espSend("servo_head_135");t.contains("[HEAD_CENTER]")->espSend("servo_head_90")}
    }
    // ── Emergency listener — mirrors HTML listenEmerg() ──────────
    private fun startEmerg(){scope.launch{delay(2000);startEmergSR()}}
    private fun startEmergSR(){
        emergSR?.destroy()
        emergSR=SpeechRecognizer.createSpeechRecognizer(this)
        emergSR?.setRecognitionListener(object:RecognitionListener{
            override fun onResults(r:android.os.Bundle?){
                val t=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase()?:return
                val words=listOf("help","emergency","sos","danger","intruder","attack","fire","mayday","call police")
                words.firstOrNull{t.contains(it)}?.let{speakOut("Emergency detected! Do you need help?")}
                scope.launch{delay(8000);startEmergSR()}
            }
            override fun onError(e:Int){scope.launch{delay(10_000L);startEmergSR()}}
            override fun onEndOfSpeech(){scope.launch{delay(8000);startEmergSR()}}
            override fun onReadyForSpeech(p:android.os.Bundle?){}; override fun onBeginningOfSpeech(){}; override fun onBufferReceived(p:ByteArray?){}
            override fun onPartialResults(p:android.os.Bundle?){}; override fun onEvent(p:Int,p1:android.os.Bundle?){}; override fun onRmsChanged(p:Float){}
        })
        try{emergSR?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault())})}
        catch(e:Exception){scope.launch{delay(12_000L);startEmergSR()}}
    }
    override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{super.onStartCommand(i,f,id);return START_STICKY}
    override fun onDestroy(){
        super.onDestroy(); instance=null; scope.cancel(); tts?.stop(); tts?.shutdown()
        sr?.destroy(); emergSR?.destroy(); wake?.release()
        val s=Intent(this,BroService::class.java)
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)startForegroundService(s) else startService(s)
    }
    override fun onBind(i:Intent):IBinder?{super.onBind(i);return null}
}