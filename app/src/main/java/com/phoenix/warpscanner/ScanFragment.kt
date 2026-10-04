package com.phoenix.warpscanner

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * One-tap staged scan:
 *  1. Probe   — fast scan -> 0%-loss candidates.
 *  2. Rank    — unlisted-first, then ms+jitter, then past success;
 *               known-dead skipped, /24-diverse top 3.
 *  3. Verify  — full WireGuard handshake (with the user's own .conf)
 *               against exactly those 3 -> only verified endpoints shown/saved.
 * Best endpoint lands on top automatically. No log box: a single status
 * line shows the current stage.
 */
class ScanFragment : Fragment() {

    private lateinit var engine: ScanEngine
    private lateinit var adapter: ResultsAdapter
    private lateinit var vm: ScanViewModel

    private lateinit var btnScan: Button
    private lateinit var btnCopyTop: Button
    private lateinit var tvProgress: TextView
    private lateinit var tvCount: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var chipGroup: ChipGroup
    private lateinit var etTarget: EditText
    private lateinit var tvConfStatus: TextView
    private lateinit var tvConfImport: TextView

    /** Set while the user pressed Stop; prevents phase 2 from starting. */
    @Volatile private var cancelled = false

    private val pickConf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = requireContext().contentResolver
                .openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
            if (WgConfStore.save(requireContext(), text)) {
                updateConfRow()
                toast("WARP config imported")
            } else {
                toast("Not a valid WireGuard .conf")
            }
        } catch (_: Exception) {
            toast("Import failed")
        }
    }

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_scan, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        engine = ScanEngine(requireContext())
        vm = ViewModelProvider(requireActivity())[ScanViewModel::class.java]
        adapter = ResultsAdapter(onNeighborScan = { r -> neighborScan(r) })

        btnScan = v.findViewById(R.id.btnScan)
        btnCopyTop = v.findViewById(R.id.btnCopyTop)
        tvProgress = v.findViewById(R.id.tvProgress)
        tvCount = v.findViewById(R.id.tvCount)
        progressBar = v.findViewById(R.id.progressBar)
        chipGroup = v.findViewById(R.id.chipPorts)
        etTarget = v.findViewById(R.id.etTarget)
        tvConfStatus = v.findViewById(R.id.tvConfStatus)
        tvConfImport = v.findViewById(R.id.tvConfImport)

        btnCopyTop.setOnClickListener { copyWinner() }

        updateConfRow()
        tvConfImport.setOnClickListener { pickConf.launch("*/*") }

        val rv = v.findViewById<RecyclerView>(R.id.rvResults)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // Restore verified results from previous scans (survives tab switches).
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
                cancelled = true
                engine.cancel()
                setIdle()
                setStatus("Stopped")
                toast("Stopped")
            } else startScan()
        }
    }

    private fun updateConfRow() {
        val ok = WgConfStore.exists(requireContext())
        tvConfStatus.text = if (ok) "WARP config: ✓ loaded" else "WARP config: not loaded"
        tvConfStatus.setTextColor(
            if (ok) resources.getColor(R.color.accent, null)
            else resources.getColor(R.color.gray, null)
        )
        tvConfImport.text = if (ok) "Replace" else "Import .conf"
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
        tvCount.text = "✓ Verified (${vm.results.size})"
        btnCopyTop.visibility = if (vm.results.isEmpty()) View.GONE else View.VISIBLE
    }

    /** One-tap winner export: copies the #1 endpoint for pasting into the VPN app. */
    private fun copyWinner() {
        val top = vm.results.firstOrNull() ?: return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("endpoint", top.endpoint))
        toast("${top.endpoint} copy ကူးပြီးပြီ")
    }

    private fun setStatus(msg: String) {
        tvProgress.text = msg
    }

    /**
     * One-tap staged pipeline:
     *  1. Probe   — fast scan of ~300 endpoints (engine).
     *  2. Filter  — keep 0%-loss endpoints only.
     *  3. Rank    — unlisted-first, then ms+jitter, then past success;
     *               skip known-dead; keep /24 diversity.
     *  4. Verify  — WireGuard handshake on the top candidates.
     * The best endpoint lands at the top automatically.
     */
    private fun startScan() {
        if (!WgConfStore.exists(requireContext())) {
            toast("Import your WARP .conf first")
            return
        }
        cancelled = false
        vm.clear()
        adapter.items = emptyList()
        updateCount()
        val target = etTarget.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 10
        btnScan.text = "■ Stop"
        progressBar.visibility = View.VISIBLE
        setStatus("Stage 1/4 · Probing endpoints…")

        // Stage 1: fast candidates, collected silently.
        val candidates = mutableListOf<ScanResult>()
        engine.scan(
            ScanHelper.quickScanArgs(selectedPorts(), target),
            onResult = { r ->
                if (ScanHelper.isStable(r)) {
                    synchronized(candidates) {
                        if (candidates.none { it.endpoint == r.endpoint }) candidates.add(r)
                    }
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    if (!ok) {
                        setIdle()
                        setStatus("Scan error")
                        toast("Error: ${err ?: "unknown"}")
                        return@runOnUiThread
                    }
                    // Stage 2+3: filter, rank, diversify.
                    setStatus("Stage 2/4 · Filtering · 3/4 · Ranking…")
                    val ctx = requireContext()
                    val ranked = synchronized(candidates) {
                        ScanHelper.ranked(
                            candidates.filter { !EndpointHistoryStore.isKnownDead(ctx, it.endpoint) },
                            goodCount = { EndpointHistoryStore.goodCount(ctx, it) }
                        )
                    }
                    val pick = ScanHelper.diverseTop(ranked, 3)
                    startVerify(pick)
                }
            }
        )
    }

    /** Stage 4: full handshake verification of exactly the top candidates. */
    private fun startVerify(pick: List<ScanResult>) {
        if (pick.isEmpty()) {
            setIdle()
            setStatus("No stable endpoint found")
            toast("No stable endpoint found")
            return
        }
        setStatus("Stage 4/4 · Verifying handshake 0/${pick.size}…")
        var done = 0
        val ctx = requireContext()
        val verifiedNow = mutableSetOf<String>()
        engine.scan(
            ScanHelper.verifyArgs(pick, WgConfStore.path(ctx)),
            onResult = { r ->
                // The engine only reports endpoints that completed the handshake.
                done++
                verifiedNow.add(r.endpoint)
                val isNew = vm.add(r)
                EndpointHistoryStore.recordStableResult(ctx, r.endpoint, r.latencyMs, r.jitterMs, alive = true)
                BackupStore.add(
                    ctx,
                    BackupEntry(ip = r.ip, port = r.port, ms = r.latencyMs, jitterMs = r.jitterMs, savedAt = System.currentTimeMillis())
                )
                activity?.runOnUiThread {
                    if (isNew) {
                        adapter.items = vm.results.toList()
                        updateCount()
                    }
                    setStatus("Stage 4/4 · Verifying handshake $done/${pick.size}…")
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                activity?.runOnUiThread {
                    // Candidates that went through a completed verify but never
                    // reported = dead. (Not on cancel/error: that proves nothing.)
                    if (ok && !cancelled) {
                        for (c in pick) {
                            if (c.endpoint !in verifiedNow) {
                                EndpointHistoryStore.recordStableResult(ctx, c.endpoint, null, null, alive = false)
                            }
                        }
                    }
                    // Final order: staged rank with history tiebreak — best on top.
                    vm.sortStaged { EndpointHistoryStore.goodCount(ctx, it) }
                    adapter.items = vm.results.toList()
                    updateCount()
                    setIdle()
                    when {
                        cancelled -> setStatus("Stopped")
                        !ok -> {
                            setStatus("Verify error")
                            toast("Error: ${err ?: "unknown"}")
                        }
                        vm.results.isEmpty() -> {
                            setStatus("No handshake-verified endpoint")
                            toast("No handshake-verified endpoint")
                        }
                        else -> {
                            setStatus("✓ ${vm.results.size} verified — saved to Backup")
                            toast("✓ ${vm.results.size} verified")
                            // Feed the Range tab: /24s of the verified endpoints (rank order).
                            vm.verifiedCidrs = vm.results
                                .map { ScanHelper.cidr24(it.ip) }
                                .distinct()
                                .joinToString(",")
                        }
                    }
                }
            }
        )
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
                        BackupEntry(ip = hit.ip, port = hit.port, ms = hit.latencyMs, jitterMs = hit.jitterMs, savedAt = System.currentTimeMillis())
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
        cancelled = true
        engine.cancel()
        super.onDestroyView()
    }
}
