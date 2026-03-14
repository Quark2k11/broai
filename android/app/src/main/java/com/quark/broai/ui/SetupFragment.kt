package com.quark.broai.ui

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.quark.broai.BroAIApp
import com.quark.broai.MainActivity
import com.quark.broai.databinding.FragmentSetupBinding

class SetupFragment : Fragment() {
    private var _b: FragmentSetupBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSetupBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        b.btnStart.setOnClickListener {
            val name = b.etName.text.toString().trim()
            val gem  = b.etGemini.text.toString().trim()
            val groq = b.etGroq.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "Enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (gem.isEmpty() && groq.isEmpty()) {
                Toast.makeText(requireContext(), "Add at least one API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requireContext().getSharedPreferences(BroAIApp.PREFS, Context.MODE_PRIVATE).edit().apply {
                putString(BroAIApp.K_NAME,   name)
                putString(BroAIApp.K_GEMINI, gem)
                putString(BroAIApp.K_GROQ,   groq)
                putBoolean(BroAIApp.K_SETUP, true)
            }.apply()
            (activity as? MainActivity)?.onSetupComplete()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
