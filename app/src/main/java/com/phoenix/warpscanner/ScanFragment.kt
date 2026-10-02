package com.phoenix.warpscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** Quick scan: preset WARP pools, stable-only results sorted by ms. */
class ScanFragment : Fragment() {

    private lateinit var engine: ScanEngine
    private lateinit var adapter: ResultsAdapter
    private val results = mutableListOf<ScanResult>()
    private val seen = mutableSetOf<String>()

    private lateinit var btnScan: Button
    private lateinit var tvProgress: TextView
    private lateinit var tvCount: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var chipGroup: ChipGroup
    private lateinit var etTarget: EditText
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnLogToggle: Button
    private val logLines = mutableListOf<String>()

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_scan, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        engine = ScanEngine(requireContext())
        adapter = ResultsAdapter(onNeighborScan = { r -> neighborScan(r) })

        btnScan = v.findViewById(R.id.btnScan)
        tvProgress = v.findViewById(R.id.tvProgress)
        tvCount = v.findViewById(R.id.tvCount)
        progressBar = v.findViewById(R.id.progressBar)
        chipGroup = v.findViewById(R.id.chipPorts)
        etTarget = v.findViewById(R.id.etTarget)
        tvLog = v.findViewById(R.id.tvLog)
        svLog = v.findViewById(R.id.svLog)
        btnLogToggle = v.findViewById(R.id.btnLogToggle)

        btnLogToggle.setOnClickListener {
            if (svLog.visibility == View.VISIBLE) {
                svLog.visibility = View.GONE
                btnLogToggle.text = "Log ပြမယ်"
            } else {
                svLog.visibility = View.VISIBLE
                btnLogToggle.text = "Log ဖျောက်မယ်"
            }
        }

        val rv = v.findViewById<RecyclerView>(R.id.rvResults)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        for (p in ScanHelper.PRESET_PORTS) {
            val chip = Chip(requireContext()).apply {
                text = p
                isCheckable = true
                isChecked = (p == "500")
            }
            chipGroup.addView(chip)
        }

        btnScan.setOnClickListener {
            if (engine.running) {
                engine.cancel()
                setIdle()
                toast("ရပ်လိုက်ပြီ")
            } else startScan()
        }
    }

    private fun selectedPorts(): String {
        val out = mutableListOf<String>()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as Chip
            if (chip.isChecked) out.add(chip.text.toString())
        }
        return if (out.isEmpty()) "500" else out.joinToString(",")
    }

    private fun startScan() {
        results.clear(); seen.clear()
        adapter.items = results
        tvCount.text = "ရလဒ် (0)"
        logLines.clear()
        tvLog.text = ""
        svLog.visibility = View.VISIBLE
        btnLogToggle.text = "Log ဖျောက်မယ်"
        val target = etTarget.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 10
        btnScan.text = "■ ရပ်"
        progressBar.visibility = View.VISIBLE
        tvProgress.text = "စတင်နေတယ်…"
        appendLog("scan စမယ်…")

        engine.scan(
            ScanHelper.quickScanArgs(selectedPorts(), target),
            onResult = { r ->
                if (ScanHelper.isStable(r)) {
                    val key = r.endpoint
                    activity?.runOnUiThread {
                        if (seen.add(key)) {
                            results.add(r)
                            ScanHelper.sortByMs(results)
                            adapter.items = results.toList()
                            tvCount.text = "ရလဒ် (${results.size}) — ms အနိမ့်ဆုံးအပေါ်"
                        }
                        appendLog("✓ $key ${r.latencyMs ?: "?"} ms")
                    }
                }
            },
            onProgress = { msg ->
                activity?.runOnUiThread {
                    tvProgress.text = msg
                    appendLog(msg)
                }
            },
            onDone = { ok, err ->
                activity?.runOnUiThread {
                    setIdle()
                    appendLog(if (ok) "scan ပြီးပြီ" else "အမှား: ${err ?: "unknown"}")
                    if (!ok) toast("အမှား: ${err ?: "unknown"}")
                    else if (results.isEmpty()) toast("တည်ငြိမ်တဲ့ endpoint မတွေ့ဘူး")
                    else toast("${results.size} ခု တွေ့တယ်")
                }
            }
        )
    }

    /** Append a line to the on-screen log (keeps the last 80, auto-scrolls). */
    private fun appendLog(msg: String) {
        logLines.add(msg)
        if (logLines.size > 80) logLines.removeAt(0)
        tvLog.text = logLines.joinToString("\n")
        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
    }

    /** Per-endpoint "/24" button: neighbor-scan that IP's /24 into the backup list. */
    private fun neighborScan(r: ScanResult) {
        if (engine.running) {
            toast("scan တစ်ခု run နေတယ် — ပြီးမှလုပ်ပါ")
            return
        }
        val cidr = ScanHelper.cidr24(r.ip)
        toast("$cidr စစ်နေတယ်…")
        engine.scan(
            ScanHelper.rangeScanArgs(cidr, selectedPorts(), 30),
            onResult = { hit ->
                if (ScanHelper.isStable(hit)) {
                    BackupStore.add(
                        requireContext(),
                        BackupEntry(hit.ip, hit.port, hit.latencyMs, System.currentTimeMillis())
                    )
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                activity?.runOnUiThread {
                    if (ok) toast("$cidr ပြီးပြီ — backup ထဲပေါင်းထည့်ပြီးပြီ")
                    else toast("အမှား: ${err ?: "unknown"}")
                }
            }
        )
    }

    private fun setIdle() {
        btnScan.text = "SCAN စမယ်"
        progressBar.visibility = View.GONE
    }

    private fun toast(m: String) =
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        engine.cancel()
        super.onDestroyView()
    }
}
