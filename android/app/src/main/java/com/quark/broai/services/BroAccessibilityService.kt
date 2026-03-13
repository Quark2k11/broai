package com.quark.broai.services
import android.accessibilityservice.AccessibilityService; import android.accessibilityservice.GestureDescription
import android.graphics.Path; import android.os.Build; import android.os.Bundle
import android.view.accessibility.AccessibilityEvent; import android.view.accessibility.AccessibilityNodeInfo
class BroAccessibilityService : AccessibilityService() {
    companion object { var instance:BroAccessibilityService?=null }
    override fun onServiceConnected(){super.onServiceConnected();instance=this}
    override fun onInterrupt(){}; override fun onAccessibilityEvent(e:AccessibilityEvent?){}
    override fun onDestroy(){super.onDestroy();instance=null}
    fun scrollDown(){rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)}
    fun scrollUp(){rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)}
    fun goBack(){performGlobalAction(GLOBAL_ACTION_BACK)}
    fun goHome(){performGlobalAction(GLOBAL_ACTION_HOME)}
    fun recentApps(){performGlobalAction(GLOBAL_ACTION_RECENTS)}
    fun answerCall(){performGlobalAction(GLOBAL_ACTION_KEYCODE_HEADSETHOOK)}
    fun endCall(){performGlobalAction(GLOBAL_ACTION_BACK)}
    fun typeText(text:String){
        val root=rootInActiveWindow?:return
        val t=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?:findEdit(root)?:return
        t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)})
    }
    fun readScreen():String{val root=rootInActiveWindow?:return"Nothing on screen";return extractText(root).take(400)}
    private fun findEdit(n:AccessibilityNodeInfo):AccessibilityNodeInfo?{if(n.isEditable)return n;for(i in 0 until n.childCount){val r=findEdit(n.getChild(i)?:continue);if(r!=null)return r};return null}
    private fun extractText(n:AccessibilityNodeInfo):String{val sb=StringBuilder();if(!n.text.isNullOrEmpty())sb.append(n.text).append(" ");for(i in 0 until n.childCount)sb.append(extractText(n.getChild(i)?:continue));return sb.toString()}
    fun tapAt(x:Float,y:Float):Boolean{if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N)return false;val p=Path().apply{moveTo(x,y)};return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,100)).build(),null,null)}
}