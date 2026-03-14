package com.quark.broai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.quark.broai.databinding.ActivityMainBinding
import com.quark.broai.services.BroService
import com.quark.broai.ui.MainFragment
import com.quark.broai.ui.SetupFragment

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

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(BroAIApp.K_SETUP, false)) showSetup() else checkPerms()
    }

    private fun showSetup() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, SetupFragment()).commit()
    }

    fun onSetupComplete() { checkPerms() }

    private fun checkPerms() {
        val missing = PERMS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), PC)
        else checkOverlay()
    }

    private fun checkOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission")
                .setMessage("Bro AI needs overlay permission for full power.")
                .setPositiveButton("Allow") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Skip") { _, _ -> launch() }
                .show()
        } else launch()
    }

    private fun launch() {
        val i = Intent(this, BroService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, MainFragment()).commit()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == PC) checkOverlay()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this) &&
            supportFragmentManager.findFragmentById(R.id.fragmentContainer) !is MainFragment)
            launch()
    }
}
