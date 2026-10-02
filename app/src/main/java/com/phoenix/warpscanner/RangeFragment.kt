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
import androidx.lifecycle.ViewModelProvider
import androidx.core.widget.doAfterTextChanged

/**
 * Range scan: user-supplied CIDRs -> stable endpoints appended to the
 * persistent backup list (the "backup list" feature).
 */
class RangeFragment : Fragment() {

    private lateinit var engine: ScanEngine
    private lateinit var btnScan: Button
    private lateinit var tvProgress: TextView
    private lateinit var etCidrs: EditText
    private lateinit var vm: ScanViewModel
    private var added = 0

    /** True while the CIDR field is being set programmatically (not by the user). */
    private var programmaticCidr = false

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_range, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        engine = ScanEngine(requireContext())
        vm = ViewModelProvider(requireActivity())[ScanViewModel::class.java]
        etCidrs = v.findViewById(R.id.etCidrs)
        val etPorts = v.findViewById<EditText>(R.id.etPorts)
        btnScan = v.findViewById(R.id.btnRangeScan)
        tvProgress = v.findViewById(R.id.tvRangeProgress)

        refreshCidrField()
        etPorts.setText("500")

        // Manual edits take ownership of the field: auto-fill stops touching it.
        etCidrs.doAfterTextChanged {
            if (!programmaticCidr) vm.rangeCidrsManual = true
        }

        btnScan.setOnClickListener {
            if (engine.running) {
                engine.cancel()
                btnScan.text = "Scan range"
                return@setOnClickListener
            }
            val cidrs = etCidrs.text.toString().trim()
            if (cidrs.isEmpty()) {
                toast("Enter a CIDR (e.g. 8.34.146.0/24)")
                return@setOnClickListener
            }
            val ports = etPorts.text.toString().trim().ifEmpty { "500" }
            added = 0
            btnScan.text = "■ Stop"
            tvProgress.text = "Starting…"

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
                            tvProgress.text = "Found $added — saving to Backup…"
                        }
                    }
                },
                onProgress = { msg ->
                    activity?.runOnUiThread { tvProgress.text = msg }
                },
                onDone = { ok, err ->
                    activity?.runOnUiThread {
                        btnScan.text = "Scan range"
                        if (!ok) toast("Error: ${err ?: "unknown"}")
                        else toast("Done — added $added to Backup")
                    }
                }
            )
        }
    }

    /**
     * Fill the CIDR field from the latest verified scan (/24s, rank order).
     * Never overwrites the user's own manual input.
     */
    private fun refreshCidrField() {
        if (vm.rangeCidrsManual) return
        val cidrs = vm.verifiedCidrs.ifEmpty { ScanHelper.DEFAULT_RANGE_CIDRS }
        programmaticCidr = true
        etCidrs.setText(cidrs)
        programmaticCidr = false
    }

    private fun toast(m: String) =
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        engine.cancel()
        super.onDestroyView()
    }
}
