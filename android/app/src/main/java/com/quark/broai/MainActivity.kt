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
        setupWebView()
        checkPerms()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        b.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                builtInZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                databaseEnabled = true
                setGeolocationEnabled(true)
                javaScriptCanOpenWindowsAutomatically = true
                // Chrome user agent so all browser APIs work including SpeechRecognition
                userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            // Auto grant mic, camera, location permissions
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread { request.grant(request.resources) }
                }
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String, callback: GeolocationPermissions.Callback
                ) { callback.invoke(origin, true, false) }
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    android.util.Log.d("BroAI_JS", msg.message())
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("BroAI", "Page loaded: $url")
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith("tel:") || url.startsWith("sms:") || url.startsWith("mailto:")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                    return false
                }
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    android.util.Log.e("BroAI", "Error: ${error.description}")
                }
            }
            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            // Load the correct GitHub Pages URL
            loadUrl("https://quark2k11.github.io/broai/")
        }
    }

    private fun checkPerms() {
        val missing = PERMS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), PC)
        else startBroService()
    }

    private fun startBroService() {
        val i = Intent(this, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == PC) startBroService()
    }
    override fun onBackPressed() { if (b.webView.canGoBack()) b.webView.goBack() else super.onBackPressed() }
    override fun onResume()  { super.onResume();  b.webView.onResume() }
    override fun onPause()   { super.onPause();   b.webView.onPause() }
}
