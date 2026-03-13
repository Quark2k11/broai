package com.quark.broai.ui
import android.content.Context; import android.os.Bundle; import android.view.*; import android.widget.Toast; import androidx.fragment.app.Fragment
import com.quark.broai.BroAIApp; import com.quark.broai.MainActivity; import com.quark.broai.databinding.FragmentSetupBinding
class SetupFragment : Fragment() {
    private var _b:FragmentSetupBinding?=null; private val b get()=_b!!
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{_b=FragmentSetupBinding.inflate(i,c,false);return b.root}
    override fun onViewCreated(v:View,s:Bundle?){super.onViewCreated(v,s)
        b.btnStart.setOnClickListener{val n=b.etName.text.toString().trim();val g=b.etGemini.text.toString().trim();val gr=b.etGroq.text.toString().trim()
            if(n.isEmpty()){Toast.makeText(requireContext(),"Enter your name",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            if(g.isEmpty()&&gr.isEmpty()){Toast.makeText(requireContext(),"Add at least one API key",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            requireContext().getSharedPreferences(BroAIApp.PREFS,Context.MODE_PRIVATE).edit().apply{putString(BroAIApp.K_NAME,n);putString(BroAIApp.K_GEMINI,g);putString(BroAIApp.K_GROQ,gr);putBoolean(BroAIApp.K_SETUP,true)}.apply()
            (activity as? MainActivity)?.onSetupComplete()}}
    override fun onDestroyView(){super.onDestroyView();_b=null}
}