package com.quark.broai.services
import android.os.Build; import android.telecom.Call; import android.telecom.CallScreeningService; import androidx.annotation.RequiresApi
@RequiresApi(Build.VERSION_CODES.N)
class BroCallService : CallScreeningService() {
    override fun onScreenCall(d:Call.Details){
        val n=d.handle?.schemeSpecificPart?:"Unknown"
        BroService.speak("Incoming call from $n. Say answer or reject.")
        respondToCall(d,CallResponse.Builder().setDisallowCall(false).setRejectCall(false).build())
    }
}