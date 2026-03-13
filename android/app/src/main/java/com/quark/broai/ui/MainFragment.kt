package com.quark.broai.ui
import android.os.Bundle; import android.view.*; import androidx.fragment.app.Fragment
import com.quark.broai.R; import com.quark.broai.databinding.FragmentMainBinding; import com.quark.broai.services.BroService
class MainFragment : Fragment() {
    private var _b:FragmentMainBinding?=null; private val b get()=_b!!
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{_b=FragmentMainBinding.inflate(i,c,false);return b.root}
    override fun onViewCreated(v:View,s:Bundle?){super.onViewCreated(v,s)
        b.btnTalk.setOnClickListener{BroService.instance?.startListening();b.tvStatus.text="Listening..."}
        b.btnSettings.setOnClickListener{parentFragmentManager.beginTransaction().replace(R.id.fragmentContainer,SettingsFragment()).addToBackStack(null).commit()}}
    override fun onDestroyView(){super.onDestroyView();_b=null}
}