package com.quark.broai.services

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.view.ViewGroup

class BroOverlayService : Service() {

    private var wm: WindowManager? = null
    private var overlayView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    private fun showOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 120
        }

        overlayView = TextView(this).apply {
            text = "( o . o )"
            textSize = 14f
            setTextColor(Color.parseColor("#00FFFF"))
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.parseColor("#AA000820"))
            setOnTouchListener(object : android.view.View.OnTouchListener {
                var ox = 0f; var oy = 0f; var px = 0; var py = 0
                override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { ox = e.rawX; oy = e.rawY; px = params.x; py = params.y }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = (px + (ox - e.rawX)).toInt()
                            params.y = (py + (e.rawY - oy)).toInt()
                            wm?.updateViewLayout(overlayView, params)
                        }
                    }
                    return true
                }
            })
        }

        try { wm?.addView(overlayView, params) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { overlayView?.let { wm?.removeView(it) } } catch (e: Exception) {}
    }
}
