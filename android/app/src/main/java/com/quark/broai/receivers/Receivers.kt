package com.quark.broai.receivers
import android.content.BroadcastReceiver; import android.content.Context; import android.content.Intent; import android.os.Build
import android.telephony.TelephonyManager; import com.quark.broai.services.BroService
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx:Context,i:Intent?){
        if(i?.action==Intent.ACTION_BOOT_COMPLETED||i?.action=="android.intent.action.QUICKBOOT_POWERON"){
            val s=Intent(ctx,BroService::class.java)
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)ctx.startForegroundService(s) else ctx.startService(s) } }
}
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx:Context,i:Intent?){
        val state=i?.getStringExtra(TelephonyManager.EXTRA_STATE)?:return
        when(state){
            TelephonyManager.EXTRA_STATE_RINGING->{val n=i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?:"Unknown";if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N)BroService.speak("Incoming call from $n. Say answer or reject.")}
            TelephonyManager.EXTRA_STATE_OFFHOOK->BroService.speak("Call connected.") } }
}