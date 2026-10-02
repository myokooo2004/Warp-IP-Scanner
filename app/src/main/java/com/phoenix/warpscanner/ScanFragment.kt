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
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** Quick scan: preset WARP pools, stable-only results sorted by ms. */
class ScanFragment : Fragment() {

    private lateinit var engine: ScanEngine
    private lateinit var adapter: ResultsAdapter
    private lateinit var vm: ScanViewModel

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
        vm = ViewModelProvider(requireActivity())[ScanViewModel::class.java]
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
                btnLogToggle.text = "Show log"
            } else {
                svLog.visibility = View.VISIBLE
                btnLogToggle.text = "Hide log"
            }
        }

        val rv = v.findViewById<RecyclerView>(R.id.rvResults)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // Restore results from previous scans (survives tab switches).
        adapter.items = vm.results.toList()
        updateCount()

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
                toast("Stopped")
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

    private fun updateCount() {
        tvCount.text = if (vm.results.isEmpty()) "Results (0)"
        else "Results (${vm.results.size}) — lowest ms first"
    }

    private fun startScan() {
        vm.clear()
        adapter.items = emptyList()
        updateCount()
        logLines.clear()
        tvLog.text = ""
        svLog.visibility = View.VISIBLE
        btnLogToggle.text = "Hide log"
        val target = etTarget.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 10
        btnScan.text = "■ Stop"
        progressBar.visibility = View.VISIBLE
        tvProgress.text = "Starting…"
        appendLog("starting scan…")

        engine.scan(
            ScanHelper.quickScanArgs(selectedPorts(), target),
            onResult = { r ->
                if (ScanHelper.isStable(r)) {
                    val isNew = vm.add(r)
                    // Persist every stable hit to the backup list automatically.
                    BackupStore.add(
                        requireContext(),
                        BackupEntry(r.ip, r.port, r.latencyMs, System.currentTimeMillis())
                    )
                    activity?.runOnUiThread {
                        if (isNew) {
                            adapter.items = vm.results.toList()
                            updateCount()
                        }
                        appendLog("✓ ${r.endpoint} ${r.latencyMs ?: "?"} ms")
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
                    appendLog(if (ok) "scan done" else "Error: ${err ?: "unknown"}")
                    if (!ok) toast("Error: ${err ?: "unknown"}")
                    else if (vm.results.isEmpty()) toast("No stable endpoint found")
                    else toast("Found ${vm.results.size} — saved to Backup")
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
            toast("A scan is running — wait for it to finish")
            return
        }
        val cidr = ScanHelper.cidr24(r.ip)
        toast("Scanning $cidr…")
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
                    if (ok) toast("$cidr done — added to Backup")
                    else toast("Error: ${err ?: "unknown"}")
                }
            }
        )
    }

    private fun setIdle() {
        btnScan.text = "SCAN"
        progressBar.visibility = View.GONE
    }

    private fun toast(m: String) =
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        engine.cancel()
        super.onDestroyView()
    }
}
