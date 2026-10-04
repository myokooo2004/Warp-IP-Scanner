package com.phoenix.warpscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Stable tab: re-verifies every Backup IP (0% loss + WireGuard handshake)
 * in the background and ranks the 10 most stable by accumulated history.
 * Every run feeds [EndpointHistoryStore] (deduped: one recorded event per
 * IP per 30 minutes); endpoints dead 3 recorded times in a row are removed
 * from Backup with a report. Shown only when this tab is tapped — the Scan
 * tab flow is untouched.
 */
class StableFragment : Fragment() {

    private lateinit var engine: ScanEngine
    private lateinit var adapter: StableAdapter
    private lateinit var btnScan: Button
    private lateinit var btnCopyAll: Button
    private lateinit var btnPublish: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var rowHead: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvDead: TextView
    private lateinit var rv: RecyclerView

    @Volatile private var cancelled = false
    private var running = false

    private var lastTop: List<ScanResult> = emptyList()
    private var lastDead: List<String> = emptyList()
    private var lastRemoved: Int = 0
    private var deadExpanded = false

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_stable, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        engine = ScanEngine(requireContext())
        adapter = StableAdapter(
            goodCount = {
                try { EndpointHistoryStore.goodCount(requireContext(), it) }
                catch (_: Exception) { 0 }
            },
            onExplore = { exploreRange(it) }
        )
        rv = v.findViewById(R.id.rvStable)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        btnScan = v.findViewById(R.id.btnStableScan)
        btnCopyAll = v.findViewById(R.id.btnCopyAll)
        btnPublish = v.findViewById(R.id.btnPublish)
        progress = v.findViewById(R.id.pbStable)
        tvStatus = v.findViewById(R.id.tvStableStatus)
        rowHead = v.findViewById(R.id.rowStableHead)
        tvEmpty = v.findViewById(R.id.tvStableEmpty)
        tvDead = v.findViewById(R.id.tvDead)

        btnScan.setOnClickListener {
            if (running) {
                cancelled = true
                engine.cancel()
            } else {
                startReverify()
            }
        }
        btnCopyAll.setOnClickListener { copyAll() }
        btnPublish.setOnClickListener { publishTop10() }
        tvDead.setOnClickListener { toggleDead() }
    }

    override fun onDestroyView() {
        try { engine.cancel() } catch (_: Exception) {}
        super.onDestroyView()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun startReverify() {
        val ctx = requireContext()
        val backup = BackupStore.load(ctx)
        if (backup.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rowHead.visibility = View.GONE
            tvDead.visibility = View.GONE
            tvStatus.text = ""
            return
        }
        if (!WgConfStore.exists(ctx)) {
            toast("Import your WARP .conf first")
            return
        }
        cancelled = false
        running = true
        deadExpanded = false
        btnScan.text = "■ CANCEL"
        progress.visibility = View.VISIBLE
        progress.max = backup.size
        progress.progress = 0
        tvEmpty.visibility = View.GONE
        rowHead.visibility = View.GONE
        tvDead.visibility = View.GONE
        tvStatus.text = "Re-verifying 0/${backup.size}…"

        val targets = backup.map { ScanResult(it.ip, it.port, null, 0) }
        val alive = java.util.Collections.synchronizedList(mutableListOf<ScanResult>())
        val confPath = WgConfStore.path(ctx)
        engine.scan(
            ScanHelper.verifyArgs(targets, confPath),
            onResult = { r ->
                alive.add(r)
                activity?.runOnUiThread {
                    if (cancelled) return@runOnUiThread
                    progress.progress = alive.size
                    tvStatus.text = "Re-verifying ${alive.size}/${targets.size}…"
                }
            },
            onProgress = {},
            onDone = { ok, err ->
                // Capture the activity reference: the fragment may be gone.
                val act = activity
                act?.runOnUiThread { finish(act, ok, err, backup, alive.toList()) }
            }
        )
    }

    private fun finish(
        act: android.app.Activity,
        ok: Boolean,
        err: String?,
        backup: List<BackupEntry>,
        alive: List<ScanResult>
    ) {
        running = false
        btnScan.text = "Stable IP Scanner"
        progress.visibility = View.GONE
        val ctx = act.applicationContext
        when {
            cancelled -> {
                tvStatus.text = "Stopped"
                return
            }
            !ok -> {
                tvStatus.text = "Error: ${err ?: "unknown"}"
                Toast.makeText(ctx, "Error: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val now = System.currentTimeMillis()
        val aliveByEp = alive.associateBy { it.endpoint }
        // Every run feeds the long-term memory (deduped inside the store:
        // at most one recorded event per IP per 30 minutes).
        for (b in backup) {
            val r = aliveByEp[b.endpoint]
            if (r != null) {
                EndpointHistoryStore.recordStableResult(ctx, b.endpoint, r.latencyMs, r.jitterMs, alive = true)
                // Refresh Backup's ms/jitter with the fresh measurement (keeps min ms).
                BackupStore.add(ctx, BackupEntry(b.ip, b.port, r.latencyMs, r.jitterMs, now))
            } else {
                EndpointHistoryStore.recordStableResult(ctx, b.endpoint, null, null, alive = false)
            }
        }

        val ranked = ScanHelper.stableRanked(alive) {
            EndpointHistoryStore.goodCount(ctx, it)
        }.take(10)
        lastTop = ranked
        adapter.items = ranked

        val aliveSet = aliveByEp.keys
        val dead = backup.filter { it.endpoint !in aliveSet }
        lastDead = dead.map { it.endpoint }

        // 3 consecutive recorded failures -> auto-remove from Backup (reported, never silent).
        var removed = 0
        for (d in dead) {
            if (EndpointHistoryStore.consecutiveFails(ctx, d.endpoint) >= EndpointHistoryStore.DEAD_STRIKES) {
                BackupStore.remove(ctx, d.ip, d.port)
                removed++
            }
        }

        rowHead.visibility = if (ranked.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility = View.GONE
        lastRemoved = removed
        updateDeadSection(dead.size, removed)
        val bits = mutableListOf("✓ ${backup.size} re-verified", "${alive.size} alive", "${dead.size} dead")
        if (removed > 0) bits.add("$removed removed from Backup (dead ×${EndpointHistoryStore.DEAD_STRIKES})")
        bits.add("just now")
        tvStatus.text = bits.joinToString(" · ")
        if (ranked.isEmpty()) {
            tvStatus.text = "No alive endpoint — ${dead.size} dead"
            Toast.makeText(ctx, "No alive endpoint", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "✓ Stable top ${ranked.size}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDeadSection(deadCount: Int, removed: Int) {
        if (deadCount == 0 && removed == 0) {
            tvDead.visibility = View.GONE
            return
        }
        tvDead.visibility = View.VISIBLE
        val head = buildString {
            append("✕ Dead ($deadCount) — excluded from ranking")
            if (removed > 0) append(" · $removed removed from Backup (dead ×${EndpointHistoryStore.DEAD_STRIKES})")
        }
        tvDead.text = if (deadExpanded && lastDead.isNotEmpty()) {
            head + "\n" + lastDead.joinToString("\n")
        } else {
            "$head · tap to view"
        }
    }

    private fun toggleDead() {
        deadExpanded = !deadExpanded
        updateDeadSection(lastDead.size, lastRemoved)
    }

    /**
     * "Explore this winner's /24": deep-link into the Range tab with the
     * endpoint's /24 + port pre-filled, auto-starting the neighbor scan.
     */
    private fun exploreRange(r: ScanResult) {
        val vm = androidx.lifecycle.ViewModelProvider(requireActivity())[ScanViewModel::class.java]
        vm.pendingExploreCidr = ScanHelper.cidr24(r.ip)
        vm.pendingExplorePort = r.port.toString()
        try {
            requireActivity()
                .findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
                .selectedItemId = R.id.nav_range
        } catch (_: Exception) {
            toast("Could not open Range tab")
        }
    }

    private fun copyAll() {
        if (lastTop.isEmpty()) {
            toast("Nothing to copy")
            return
        }
        val text = lastTop.joinToString("\n") { it.endpoint }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("stable_endpoints", text))
        btnCopyAll.text = "✓ COPIED"
        toast("${lastTop.size} endpoints copy ကူးပြီးပြီ")
        Handler(Looper.getMainLooper()).postDelayed({ btnCopyAll.text = "⧉ COPY ALL" }, 1500)
    }

    /**
     * Publish the currently displayed top-10 as a JSON document for the
     * companion VPN app: scrollable monospace preview in a dialog, then
     * copy to clipboard for a manual paste into endpoints.json on GitHub.
     */
    private fun publishTop10() {
        if (lastTop.isEmpty()) {
            toast("Run a Stable scan first")
            return
        }
        val json = buildPublishJson()
        val tv = TextView(requireContext()).apply {
            text = json
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 20, 32, 20)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(requireContext()).apply { addView(tv) }
        AlertDialog.Builder(requireContext())
            .setTitle("Publish top ${lastTop.size}")
            .setView(scroll)
            .setPositiveButton("Copy JSON") { _, _ ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("stable_top10", json))
                toast("Copied — paste into endpoints.json on GitHub")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun buildPublishJson(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val eps = JSONArray()
        for (r in lastTop.take(10)) {
            eps.put(JSONObject().apply {
                put("ip", r.ip)
                put("port", r.port)
                put("ms", r.latencyMs ?: 0L)
                if (r.jitterMs != null) put("jitter_ms", r.jitterMs) else put("jitter_ms", JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("v", 1)
            put("updated_at", sdf.format(Date()))
            put("isp", "MPT")
            put("endpoints", eps)
        }.toString(2)
    }
}
