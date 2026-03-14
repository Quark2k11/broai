package com.quark.broai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import androidx.fragment.app.Fragment
import com.quark.broai.BroAIApp
import com.quark.broai.brain.BroBrain
import com.quark.broai.databinding.FragmentSettingsBinding
import com.quark.broai.services.BroService

class SettingsFragment : Fragment() {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        val p = requireContext().getSharedPreferences(BroAIApp.PREFS, Context.MODE_PRIVATE)
        b.etName.setText(p.getString(BroAIApp.K_NAME,    ""))
        b.etGemini.setText(p.getString(BroAIApp.K_GEMINI,  ""))
        b.etGroq.setText(p.getString(BroAIApp.K_GROQ,    ""))
        b.etMistral.setText(p.getString(BroAIApp.K_MISTRAL, ""))
        b.etOR.setText(p.getString(BroAIApp.K_OR,      ""))
        b.etHF.setText(p.getString(BroAIApp.K_HF,      ""))
        b.etWG.setText(p.getString(BroAIApp.K_WG,      ""))
        b.etGH.setText(p.getString(BroAIApp.K_GH,      ""))
        b.etESP.setText(p.getString(BroAIApp.K_ESP_IP,  ""))
        refreshGoals()
        b.btnSave.setOnClickListener {
            p.edit().apply {
                putString(BroAIApp.K_NAME,    b.etName.text.toString().trim())
                putString(BroAIApp.K_GEMINI,  b.etGemini.text.toString().trim())
                putString(BroAIApp.K_GROQ,    b.etGroq.text.toString().trim())
                putString(BroAIApp.K_MISTRAL, b.etMistral.text.toString().trim())
                putString(BroAIApp.K_OR,      b.etOR.text.toString().trim())
                putString(BroAIApp.K_HF,      b.etHF.text.toString().trim())
                putString(BroAIApp.K_WG,      b.etWG.text.toString().trim())
                putString(BroAIApp.K_GH,      b.etGH.text.toString().trim())
                putString(BroAIApp.K_ESP_IP,  b.etESP.text.toString().trim())
            }.apply()
            BroService.speak("Settings saved")
            parentFragmentManager.popBackStack()
        }
        b.btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.btnNotifAccess.setOnClickListener   { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        b.btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")))
        }
        b.btnGetGemini.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))) }
        b.btnGetGroq.setOnClickListener   { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }
    }

    private fun refreshGoals() {
        val g = BroBrain.goals
        b.tvGoals.text = if (g.isEmpty()) "No goals yet.\nSay: Hey Bro my goal is..."
                         else g.mapIndexed { i, goal -> "${i+1}. $goal" }.joinToString("\n")
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
