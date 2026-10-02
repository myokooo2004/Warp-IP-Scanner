package com.phoenix.warpscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Range scan: user-supplied CIDRs -> stable endpoints appended to the
 * persistent backup list (the "backup list" feature).
 */
class RangeFragment : Fragment() {

    private lateinit var engine: ScanEngine
    private lateinit var btnScan: Button
    private lateinit var tvProgress: TextView
    private var added = 0

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_range, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        engine = ScanEngine(requireContext())
        val etCidrs = v.findViewById<EditText>(R.id.etCidrs)
        val etPorts = v.findViewById<EditText>(R.id.etPorts)
        btnScan = v.findViewById(R.id.btnRangeScan)
        tvProgress = v.findViewById(R.id.tvRangeProgress)

        etCidrs.setText("8.34.146.0/24,8.35.211.0/24,8.39.204.0/24")
        etPorts.setText("500")

        btnScan.setOnClickListener {
            if (engine.running) {
                engine.cancel()
                btnScan.text = "Range စမယ်"
                return@setOnClickListener
            }
            val cidrs = etCidrs.text.toString().trim()
            if (cidrs.isEmpty()) {
                toast("CIDR ရိုက်ပါ (ဥပမာ 8.34.146.0/24)")
                return@setOnClickListener
            }
            val ports = etPorts.text.toString().trim().ifEmpty { "500" }
            added = 0
            btnScan.text = "■ ရပ်"
            tvProgress.text = "စတင်နေတယ်…"

            engine.scan(
                ScanHelper.rangeScanArgs(cidrs, ports, 50),
                onResult = { r ->
                    if (ScanHelper.isStable(r)) {
                        BackupStore.add(
                            requireContext(),
                            BackupEntry(r.ip, r.port, r.latencyMs, System.currentTimeMillis())
                        )
                        added++
                        activity?.runOnUiThread {
                            tvProgress.text = "တွေ့ပြီ $added ခု (backup ထဲသိမ်းနေတယ်…)"
                        }
                    }
                },
                onProgress = { msg ->
                    activity?.runOnUiThread { tvProgress.text = msg }
                },
                onDone = { ok, err ->
                    activity?.runOnUiThread {
                        btnScan.text = "Range စမယ်"
                        if (!ok) toast("အမှား: ${err ?: "unknown"}")
                        else toast("ပြီးပြီ — backup ထဲ $added ခု ပေါင်းထည့်ပြီးပြီ")
                    }
                }
            )
        }
    }

    private fun toast(m: String) =
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        engine.cancel()
        super.onDestroyView()
    }
}
