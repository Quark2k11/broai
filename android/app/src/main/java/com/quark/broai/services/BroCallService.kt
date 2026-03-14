package com.quark.broai.services

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class BroCallService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val num = details.handle?.schemeSpecificPart ?: "Unknown"
        BroService.speak("Incoming call from $num. Say answer or reject.")
        respondToCall(details, CallResponse.Builder()
            .setDisallowCall(false).setRejectCall(false).build())
    }
}
