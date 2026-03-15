package com.quark.broai.services

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import com.quark.broai.BroAIApp

@RequiresApi(Build.VERSION_CODES.N)
class BroCallService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val prefs = getSharedPreferences(BroAIApp.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(BroAIApp.K_CALLS, true)) {
            val num = details.handle?.schemeSpecificPart ?: "Unknown"
            BroService.speak("Incoming call from $num. Say Hey Bro answer it or reject.")
        }
        respondToCall(details, CallResponse.Builder()
            .setDisallowCall(false).setRejectCall(false).build())
    }
}
