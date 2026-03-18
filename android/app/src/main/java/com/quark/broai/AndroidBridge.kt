package com.quark.broai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.webkit.JavascriptInterface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.quark.broai.services.BroService
import com.quark.broai.services.BroAccessibilityService
import com.quark.broai.services.BroOverlayService

class AndroidBridge(private val ctx: Context) {

    @JavascriptInterface
    fun isAndroid(): Boolean = true

    @JavascriptInterface
    fun getVersion(): String = "10.0"

    // ── Settings ─────────────────────────────────────────────
    @JavascriptInterface
    fun getSetting(key: String): Boolean =
        ctx.getSharedPreferences(BroAIApp.PREFS, Context.MODE_PRIVATE).getBoolean(key, true)

    @JavascriptInterface
    fun setSetting(key: String, value: Boolean) {
        ctx.getSharedPreferences(BroAIApp.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
        when (key) {
            BroAIApp.K_MASTER -> {
                if (value) startService() else stopService()
            }
            BroAIApp.K_OVERLAY -> {
                if (value) ctx.startService(Intent(ctx, BroOverlayService::class.java))
                else ctx.stopService(Intent(ctx, BroOverlayService::class.java))
            }
        }
    }

    @JavascriptInterface
    fun getAllSettings(): String {
        val p = ctx.getSharedPreferences(BroAIApp.PREFS, Context.MODE_PRIVATE)
        return """{
            "master":     ${p.getBoolean(BroAIApp.K_MASTER,      true)},
            "bgWake":     ${p.getBoolean(BroAIApp.K_BG_WAKE,     true)},
            "boot":       ${p.getBoolean(BroAIApp.K_BOOT,        true)},
            "calls":      ${p.getBoolean(BroAIApp.K_CALLS,       true)},
            "messages":   ${p.getBoolean(BroAIApp.K_MESSAGES,    true)},
            "accessible": ${p.getBoolean(BroAIApp.K_ACCESSIBLE,  true)},
            "emergency":  ${p.getBoolean(BroAIApp.K_EMERGENCY,   true)},
            "wakelock":   ${p.getBoolean(BroAIApp.K_WAKELCK,     true)},
            "overlay":    ${p.getBoolean(BroAIApp.K_OVERLAY,     false)},
            "location":   ${p.getBoolean(BroAIApp.K_LOCATION,    true)},
            "screenRead": ${p.getBoolean(BroAIApp.K_SCREEN_READ, true)},
            "smartHome":  ${p.getBoolean(BroAIApp.K_SMART_HOME,  false)}
        }"""
    }

    // ── Service control ───────────────────────────────────────
    @JavascriptInterface
    fun startService() {
        val i = Intent(ctx, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
        else ctx.startService(i)
    }

    @JavascriptInterface
    fun stopService() { ctx.stopService(Intent(ctx, BroService::class.java)) }

    @JavascriptInterface
    fun isServiceRunning(): Boolean = BroService.instance != null

    // ── VOLUME control ────────────────────────────────────────
    @JavascriptInterface
    fun volumeUp() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }

    @JavascriptInterface
    fun volumeDown() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    @JavascriptInterface
    fun volumeMute() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
    }

    @JavascriptInterface
    fun getVolume(): Int {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    // ── BRIGHTNESS control ────────────────────────────────────
    @JavascriptInterface
    fun setBrightness(level: Int) {
        // level 0-255
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.System.canWrite(ctx)) return
            Settings.System.putInt(ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun brightnessUp() { setBrightness((getBrightness() + 40).coerceAtMost(255)) }

    @JavascriptInterface
    fun brightnessDown() { setBrightness((getBrightness() - 40).coerceAtLeast(0)) }

    @JavascriptInterface
    fun getBrightness(): Int = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) { 128 }

    // ── TORCH ─────────────────────────────────────────────────
    @JavascriptInterface
    fun torchOn() { setTorch(true) }

    @JavascriptInterface
    fun torchOff() { setTorch(false) }

    private fun setTorch(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val cm = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val id = cm?.cameraIdList?.firstOrNull {
                cm.getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, on)
        } catch (e: Exception) {}
    }

    // ── CALLS ─────────────────────────────────────────────────
    @JavascriptInterface
    fun makeCall(number: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) return
        ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.replace(" ", "")}"
        )).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    @JavascriptInterface
    fun answerCall() {
        BroAccessibilityService.instance?.answerCall()
    }

    @JavascriptInterface
    fun rejectCall() {
        BroAccessibilityService.instance?.endCall()
    }

    // ── SMS ───────────────────────────────────────────────────
    @JavascriptInterface
    fun sendSms(number: String, body: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms?.sendTextMessage(number, null, body, null, null)
        } catch (e: Exception) {}
    }

    // ── CONTACTS ──────────────────────────────────────────────
    @JavascriptInterface
    fun lookupContact(name: String): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return ""
        return ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else ""
        } ?: ""
    }

    @JavascriptInterface
    fun getAllContacts(): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return "[]"
        val list = mutableListOf<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)?.replace("\"","") ?: continue
                val p = c.getString(1) ?: continue
                list.add("{\"name\":\"$n\",\"phone\":\"$p\"}")
            }
        }
        return "[${list.take(500).joinToString(",")}]"
    }

    // ── OPEN APP ──────────────────────────────────────────────
    @JavascriptInterface
    fun openApp(name: String): Boolean {
        val pm = ctx.packageManager
        val app = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .firstOrNull { pm.getApplicationLabel(it).toString().contains(name, true) }
            ?: return false
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK } ?: return false
        ctx.startActivity(intent)
        return true
    }

    @JavascriptInterface
    fun getInstalledApps(): String {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { "\"${pm.getApplicationLabel(it).toString().replace("\"","")}\"" }
        return "[${apps.joinToString(",")}]"
    }

    // ── LOCATION ──────────────────────────────────────────────
    @JavascriptInterface
    fun getLocation(): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return "{}"
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return "{}"
            return "{\"lat\":${loc.latitude},\"lng\":${loc.longitude},\"accuracy\":${loc.accuracy}}"
        } catch (e: Exception) { return "{}" }
    }

    // ── SCREEN READER ─────────────────────────────────────────
    @JavascriptInterface
    fun readScreen(): String {
        return BroAccessibilityService.instance?.readScreen() ?: "Nothing on screen"
    }

    @JavascriptInterface
    fun readScreenAndSpeak() {
        val text = BroAccessibilityService.instance?.readScreen() ?: "Nothing on screen"
        BroService.speak(text.take(200))
    }

    // ── ACCESSIBILITY ─────────────────────────────────────────
    @JavascriptInterface
    fun scrollDown()   { BroAccessibilityService.instance?.scrollDown() }

    @JavascriptInterface
    fun scrollUp()     { BroAccessibilityService.instance?.scrollUp() }

    @JavascriptInterface
    fun goBack()       { BroAccessibilityService.instance?.goBack() }

    @JavascriptInterface
    fun goHome()       { BroAccessibilityService.instance?.goHome() }

    @JavascriptInterface
    fun recentApps()   { BroAccessibilityService.instance?.recentApps() }

    @JavascriptInterface
    fun typeText(text: String) { BroAccessibilityService.instance?.typeText(text) }

    @JavascriptInterface
    fun tapAt(x: Float, y: Float) { BroAccessibilityService.instance?.tapAt(x, y) }

    // ── SMART HOME ────────────────────────────────────────────
    @JavascriptInterface
    fun smartHomeCmd(ip: String, endpoint: String, body: String): String {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder()
                .url("http://$ip/$endpoint")
                .post(body.toRequestBody(okhttp3.MediaType.parse("application/json")))
                .build()
            val res = client.newCall(req).execute()
            res.body?.string() ?: "ok"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    // ── WEB SEARCH / MAPS ─────────────────────────────────────
    @JavascriptInterface
    fun openMaps(place: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=${Uri.encode(place)}"))
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    @JavascriptInterface
    fun openBrowser(url: String) {
        var u = url; if (!u.startsWith("http")) u = "https://$u"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    // ── SET ALARM ─────────────────────────────────────────────
    @JavascriptInterface
    fun setAlarm(hour: Int, minute: Int, label: String) {
        ctx.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    // ── PERMISSION SHORTCUTS ──────────────────────────────────
    @JavascriptInterface
    fun openAccessibilitySettings() {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    @JavascriptInterface
    fun openNotificationSettings() {
        ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    @JavascriptInterface
    fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}"))
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    @JavascriptInterface
    fun openWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${ctx.packageName}"))
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }

    // ── SPEAK ─────────────────────────────────────────────────
    @JavascriptInterface
    fun speak(text: String) { BroService.speak(text) }

    // ── OVERLAY ───────────────────────────────────────────────
    @JavascriptInterface
    fun showOverlay() {
        ctx.startService(Intent(ctx, BroOverlayService::class.java))
    }

    @JavascriptInterface
    fun hideOverlay() {
        ctx.stopService(Intent(ctx, BroOverlayService::class.java))
    }
}

// Extension for OkHttp
fun String.toRequestBody(mediaType: String): okhttp3.RequestBody =
    okhttp3.RequestBody.create(okhttp3.MediaType.parse(mediaType), this)

fun String.toMediaType(): okhttp3.MediaType? = okhttp3.MediaType.parse(this)
