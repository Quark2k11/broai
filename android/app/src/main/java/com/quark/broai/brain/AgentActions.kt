package com.quark.broai.brain

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.quark.broai.services.BroAccessibilityService
import com.quark.broai.services.BroService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AgentActions {

    suspend fun handle(ctx: Context, cmd: String): Boolean = withContext(Dispatchers.Main) {
        val q = cmd.lowercase()

        // Call
        Regex("(?:call|ring|dial) (.+)", RegexOption.IGNORE_CASE).find(cmd)?.let {
            val name = it.groupValues[1].trim()
            val num  = resolveContact(ctx, name) ?: extractNum(name)
            if (num != null) { BroService.speak("Calling $name"); makeCall(ctx, num) }
            else BroService.speak("No number found for $name")
            return@withContext true
        }

        // SMS
        Regex("(?:text|sms|message) (.+?) (?:saying|that|to say) (.+)", RegexOption.IGNORE_CASE).find(cmd)?.let {
            val num = resolveContact(ctx, it.groupValues[1].trim()) ?: extractNum(it.groupValues[1])
            if (num != null) { sendSms(ctx, num, it.groupValues[2].trim()); BroService.speak("Message sent") }
            else BroService.speak("Contact not found")
            return@withContext true
        }

        // Open app
        Regex("(?:open|launch|start) (.+)", RegexOption.IGNORE_CASE).find(cmd)?.let {
            if (openApp(ctx, it.groupValues[1].trim())) BroService.speak("Opening ${it.groupValues[1]}")
            else BroService.speak("App not found")
            return@withContext true
        }

        // Volume
        if (q.contains("volume up") || q.contains("louder"))   { vol(ctx, AudioManager.ADJUST_RAISE); BroService.speak("Volume up");   return@withContext true }
        if (q.contains("volume down") || q.contains("quieter")) { vol(ctx, AudioManager.ADJUST_LOWER); BroService.speak("Volume down"); return@withContext true }
        if (q.contains("mute"))                                  { vol(ctx, AudioManager.ADJUST_MUTE);  BroService.speak("Muted");       return@withContext true }

        // Torch
        if (q.contains("torch on")  || q.contains("flashlight on")  || q.contains("light on"))  { torch(ctx, true);  BroService.speak("Flashlight on");  return@withContext true }
        if (q.contains("torch off") || q.contains("flashlight off") || q.contains("light off")) { torch(ctx, false); BroService.speak("Flashlight off"); return@withContext true }

        // Web search
        Regex("(?:search|google|look up) (.+)", RegexOption.IGNORE_CASE).find(cmd)?.let {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com/search?q=${Uri.encode(it.groupValues[1])}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            BroService.speak("Searching"); return@withContext true
        }

        // Maps navigation
        Regex("(?:navigate to|take me to|directions to) (.+)", RegexOption.IGNORE_CASE).find(cmd)?.let {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(it.groupValues[1])}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            BroService.speak("Navigating to ${it.groupValues[1]}"); return@withContext true
        }

        // Set alarm
        Regex("set alarm.*?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(cmd)?.let {
            var h = it.groupValues[1].toIntOrNull() ?: 7
            val m = it.groupValues[2].toIntOrNull() ?: 0
            if (it.groupValues[3].lowercase() == "pm" && h < 12) h += 12
            ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            BroService.speak("Alarm set"); return@withContext true
        }

        // Scroll
        if (q.contains("scroll down")) { BroAccessibilityService.instance?.scrollDown(); return@withContext true }
        if (q.contains("scroll up"))   { BroAccessibilityService.instance?.scrollUp();   return@withContext true }

        // Navigation
        if (q == "go back" || q == "back")   { BroAccessibilityService.instance?.goBack();     BroService.speak("Going back"); return@withContext true }
        if (q == "go home" || q == "home")   { BroAccessibilityService.instance?.goHome();     BroService.speak("Going home"); return@withContext true }
        if (q.contains("recent apps"))       { BroAccessibilityService.instance?.recentApps(); return@withContext true }

        // Type text
        Regex("(?:type|write|enter) (.+)", RegexOption.IGNORE_CASE).find(cmd)?.let {
            BroAccessibilityService.instance?.typeText(it.groupValues[1])
            BroService.speak("Typing"); return@withContext true
        }

        // Read screen
        if (q.contains("read screen") || q.contains("what is on screen")) {
            val t = BroAccessibilityService.instance?.readScreen() ?: "Nothing on screen"
            BroService.speak(t.take(150)); return@withContext true
        }

        // Answer / reject call
        if (q.contains("answer") || q.contains("pick up"))              { BroAccessibilityService.instance?.answerCall(); return@withContext true }
        if (q.contains("reject") || q.contains("decline") || q.contains("hang up")) { BroAccessibilityService.instance?.endCall(); return@withContext true }

        // Goals
        Regex("(?:my goal is|set goal|add goal) (.+)", RegexOption.IGNORE_CASE).find(cmd)?.let {
            BroBrain.addGoal(it.groupValues[1].trim()); BroService.speak("Goal saved"); return@withContext true
        }
        Regex("(?:i completed|mark complete|done with) (.+)", RegexOption.IGNORE_CASE).find(cmd)?.let {
            BroBrain.completeGoal(it.groupValues[1].trim()); BroService.speak("Goal completed! Great work!"); return@withContext true
        }
        if (q.contains("what are my goals") || q.contains("show goals")) {
            val g = BroBrain.goals
            BroService.speak(if (g.isEmpty()) "No active goals." else "Your goals: ${g.joinToString(", ")}")
            return@withContext true
        }

        false
    }

    private fun resolveContact(ctx: Context, name: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun extractNum(t: String) = Regex("[+\\d][\\d\\-() ]{6,}").find(t)?.value?.trim()

    private fun makeCall(ctx: Context, num: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${num.replace(" ", "")}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    private fun sendSms(ctx: Context, num: String, body: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(SmsManager::class.java)
                      else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms?.sendTextMessage(num, null, body, null, null)
        } catch (e: Exception) { BroService.speak("SMS failed") }
    }

    private fun openApp(ctx: Context, name: String): Boolean {
        val pm  = ctx.packageManager
        val app = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .firstOrNull { pm.getApplicationLabel(it).toString().contains(name, true) } ?: return false
        ctx.startActivity(pm.getLaunchIntentForPackage(app.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK } ?: return false)
        return true
    }

    private fun vol(ctx: Context, dir: Int) {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun torch(ctx: Context, on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, on)
        } catch (e: Exception) {}
    }
}
