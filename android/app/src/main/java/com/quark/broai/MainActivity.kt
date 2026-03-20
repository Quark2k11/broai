package com.quark.broai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.quark.broai.databinding.ActivityMainBinding
import com.quark.broai.services.BroService

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val PC = 100
    private val PERMS = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
    }.toTypedArray()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "BroAI/10.0 Android"
                setSupportZoom(false)
                builtInZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                databaseEnabled = true
                setGeolocationEnabled(true)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { request.grant(request.resources) }
                }
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String, callback: GeolocationPermissions.Callback
                ) { callback.invoke(origin, true, false) }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // Inject bridge connector after page loads
                    // This connects HTML voice button to Android mic
                    val js = """
                        (function() {
                            // Override toggleVoice to connect to Android service
                            var origToggleVoice = window.toggleVoice;
                            window.toggleVoice = function() {
                                if (typeof AndroidBridge !== 'undefined') {
                                    if (window.botVoiceOn) {
                                        AndroidBridge.micDisable();
                                    } else {
                                        AndroidBridge.micEnable();
                                    }
                                }
                                if (origToggleVoice) origToggleVoice();
                            };
                            console.log('[BroAI] Android bridge connected');
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js, null)
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    if (!url.startsWith("file://") && !url.startsWith("https://") && !url.startsWith("http://")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                    return false
                }
            }

            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/broai_v10.html")
        }

        checkPerms()
    }

    private fun checkPerms() {
        val missing = PERMS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), PC)
        else startBroService()
    }

    fun startBroService() {
        val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(BroAIApp.K_MASTER, true)) return
        val i = Intent(this, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == PC) startBroService()
    }

    override fun onBackPressed() {
        if (b.webView.canGoBack()) b.webView.goBack() else super.onBackPressed()
    }

    override fun onResume()  { super.onResume();  b.webView.onResume() }
    override fun onPause()   { super.onPause();   b.webView.onPause() }
}
