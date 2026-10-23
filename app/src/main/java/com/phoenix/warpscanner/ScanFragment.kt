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

/**
 * One-tap scan of ONE range — 162.159.192.0/24 on port 500 (user spec
 * 2026-10-23): the whole /24 is enumerated, stable (0%-loss) answers
 * become candidates, and candidates are handshake-verified in rank
 * order until the user-chosen count N ("Show … verified") is reached.
 * A candidate that fails a verify draw is retried in later draws and
 * replaced by the next candidate (backfill); after 3 failed draws it
 * is shown greyed as failed — nothing disappears silently. When the
 * candidate pool runs dry before N, the range is re-probed with fresh
 * engine draws (refill, up to 3). The final status line is the funnel:
 * probed / candidates / verified / failed counts.
 * No log box: a single status line shows the current stage.
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
    private lateinit var etVerifyCount: EditText
    private lateinit var tvConfStatus: TextView
    private lateinit var tvConfImport: TextView

    // --- Scan state machine (single scan run) ---
    private var scanN = 3
    private val pool = LinkedHashMap<String, ScanResult>()
    private val triedDraws = mutableMapOf<String, Int>()
    private val verifiedSet = mutableSetOf<String>()
    private val failedList = mutableListOf<ScanResult>()
    private var rankedCache: List<ScanResult> = emptyList()
    private var refillsLeft = 0
    private var rangeDraws = 0

    /** Set while the user pressed Stop; prevents phase 2 from starting. */
    @Volatile private var cancelled = false

    private companion object {
        const val PREFS = "scan_prefs"
        const val KEY_VERIFY_COUNT = "verify_count"
        const val MAX_REFILLS = 3
        const val MAX_VERIFY_DRAWS = 3
        const val RANGE_HOSTS = 254
    }

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
        etVerifyCount = v.findViewById(R.id.etVerifyCount)
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

        // Persisted "Show N verified" choice (default 3).
        val savedN = requireContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VERIFY_COUNT, 3)
        etVerifyCount.setText(savedN.toString())

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

    /** The scan range is fixed to port 500 (the only WARP port open on the user's line). */
    private fun selectedPorts(): String = "500"

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

    /** Start a scan run: reset state, then probe the range. */
    private fun startScan() {
        if (!WgConfStore.exists(requireContext())) {
            toast("Import your WARP .conf first")
            return
        }
        cancelled = false
        vm.clear()
        adapter.items = emptyList()
        adapter.failedEndpoints = emptySet()
        updateCount()
        scanN = etVerifyCount.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 3
        etVerifyCount.setText(scanN.toString())
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_VERIFY_COUNT, scanN).apply()
        pool.clear()
        triedDraws.clear()
        verifiedSet.clear()
        failedList.clear()
        rankedCache = emptyList()
        refillsLeft = MAX_REFILLS
        rangeDraws = 0
        btnScan.text = "■ Stop"
        progressBar.visibility = View.VISIBLE
        runRangeDraw()
    }

    /**
     * One full enumeration draw over 162.159.192.0/24 (fresh engine
     * process = fresh sockets/flows). Stable (0%-loss) answers join the
     * candidate pool; the first draw feeds ranking + verify, later
     * draws are refills when the pool ran dry before N verified.
     */
    private fun runRangeDraw() {
        if (cancelled) return
        rangeDraws++
        setStatus("Probing 162.159.192.0/24 · draw $rangeDraws…")
        engine.scan(
            ScanHelper.rangeScanArgs(ScanHelper.frontCidrsCsv, selectedPorts(), RANGE_HOSTS),
            onResult = { r ->
                if (ScanHelper.isStable(r)) {
                    synchronized(pool) {
                        if (!pool.containsKey(r.endpoint)) pool[r.endpoint] = r
                    }
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    if (!ok && pool.isEmpty()) {
                        finishScan(ok = false, err = err)
                    } else {
                        rankPool()
                        verifyStep()
                    }
                }
            }
        )
    }

    /** Rank the candidate pool (front range exempt from known-dead burial). */
    private fun rankPool() {
        val ctx = requireContext()
        rankedCache = synchronized(pool) {
            ScanHelper.ranked(
                pool.values.filter {
                    // The front range is exempt from the known-dead
                    // burial: its dead marks are manufactured by
                    // bad-flow runs (one shared socket per endpoint
                    // per run), so it is always re-judged on this
                    // run's own evidence.
                    ScanHelper.rangeTier(it.ip) == 0 ||
                        !EndpointHistoryStore.isKnownDead(ctx, it.endpoint)
                },
                goodCount = { EndpointHistoryStore.goodCount(ctx, it) }
            )
        }
    }

    /**
     * Advance verification: take the next untried (or retryable)
     * candidates in rank order — enough to fill N — and verify them in
     * one batch draw. When nobody is left to try and N is still unmet,
     * refill with a fresh range draw (bounded), else finish.
     */
    private fun verifyStep() {
        if (cancelled) return
        if (verifiedSet.size >= scanN) {
            finishScan(ok = true, err = null)
            return
        }
        val failedEndpoints = failedList.map { it.endpoint }.toSet()
        val need = scanN - verifiedSet.size
        val working = rankedCache.filter {
            it.endpoint !in verifiedSet &&
                it.endpoint !in failedEndpoints &&
                (triedDraws[it.endpoint] ?: 0) < MAX_VERIFY_DRAWS
        }.take(need)
        if (working.isEmpty()) {
            if (refillsLeft > 0) {
                refillsLeft--
                runRangeDraw()
            } else {
                finishScan(ok = true, err = null)
            }
            return
        }
        verifyBatch(working)
    }

    /**
     * One verify batch = one fresh engine process (fresh sockets/flows)
     * over the working candidates. An endpoint counts as verified if it
     * completes the full handshake in ANY draw — the scanner-side twin
     * of the VPN's flow re-roll. Endpoints that fail get one strike per
     * batch; at 3 strikes they move to the failed list (shown greyed)
     * and are recorded dead — a single dead flow can no longer bury a
     * healthy IP, and no candidate vanishes without a trace.
     */
    private fun verifyBatch(working: List<ScanResult>) {
        setStatus("Verifying handshake ${verifiedSet.size}/$scanN…")
        val ctx = requireContext()
        engine.scan(
            ScanHelper.verifyArgs(working, WgConfStore.path(ctx)),
            onResult = { r ->
                // The engine only reports endpoints that completed the handshake.
                if (verifiedSet.add(r.endpoint)) {
                    val isNew = vm.add(r)
                    EndpointHistoryStore.recordStableResult(ctx, r.endpoint, r.latencyMs, r.jitterMs, alive = true)
                    BackupStore.add(
                        ctx,
                        BackupEntry(ip = r.ip, port = r.port, ms = r.latencyMs, jitterMs = r.jitterMs, savedAt = System.currentTimeMillis())
                    )
                    activity?.runOnUiThread {
                        if (isNew) refreshResults()
                        setStatus("Verifying handshake ${verifiedSet.size}/$scanN…")
                    }
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    if (!ok) {
                        finishScan(ok = false, err = err)
                        return@runOnUiThread
                    }
                    for (c in working) {
                        if (c.endpoint !in verifiedSet) {
                            val t = (triedDraws[c.endpoint] ?: 0) + 1
                            triedDraws[c.endpoint] = t
                            if (t >= MAX_VERIFY_DRAWS) {
                                failedList.add(c)
                                EndpointHistoryStore.recordStableResult(ctx, c.endpoint, null, null, alive = false)
                            }
                        }
                    }
                    refreshResults()
                    verifyStep()
                }
            }
        )
    }

    /** Verified rows first (rank order), then this run's failed candidates. */
    private fun refreshResults() {
        adapter.failedEndpoints = failedList.map { it.endpoint }.toSet()
        adapter.items = vm.results.toList() +
            failedList.map { it.copy(latencyMs = null, jitterMs = null) }
        updateCount()
    }

    /** Final bookkeeping + the funnel status line. */
    private fun finishScan(ok: Boolean, err: String?) {
        val ctx = requireContext()
        // Final order: staged rank with history tiebreak — best on top.
        vm.sortStaged { EndpointHistoryStore.goodCount(ctx, it) }
        refreshResults()
        setIdle()
        val funnel = "Probed $RANGE_HOSTS · Candidates ${pool.size} · " +
            "Verified ${verifiedSet.size}/$scanN · ${failedList.size} failed"
        when {
            cancelled -> setStatus("Stopped")
            !ok -> {
                setStatus("Scan error · $funnel")
                toast("Error: ${err ?: "unknown"}")
            }
            vm.results.isEmpty() -> {
                setStatus("No handshake-verified endpoint · $funnel")
                toast("No handshake-verified endpoint")
            }
            else -> {
                setStatus("✓ ${vm.results.size} verified — saved to Backup · $funnel")
                toast("✓ ${vm.results.size} verified")
                // Feed the Range tab: /24s of the verified endpoints (rank order).
                vm.verifiedCidrs = vm.results
                    .map { ScanHelper.cidr24(it.ip) }
                    .distinct()
                    .joinToString(",")
            }
        }
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
