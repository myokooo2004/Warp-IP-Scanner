package com.phoenix.warpscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

/**
 * Result rows: rank, IP:port, ms badge, copy button, "/24" neighbor-scan button.
 * Copy shows a ✓ feedback then resets (re-tappable).
 */
class ResultsAdapter(
    private val onNeighborScan: (ScanResult) -> Unit
) : RecyclerView.Adapter<ResultsAdapter.VH>() {

    var items: List<ScanResult> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    /** Endpoints that exhausted their verify draws this run — rendered greyed. */
    var failedEndpoints: Set<String> = emptySet()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.tvRank)
        val ip: TextView = v.findViewById(R.id.tvIp)
        val tag: TextView = v.findViewById(R.id.tvTag)
        val ms: TextView = v.findViewById(R.id.tvMs)
        val copy: Button = v.findViewById(R.id.btnCopy)
        val range: Button = v.findViewById(R.id.btnRange)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_result, p, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = items[pos]
        val ctx = h.itemView.context
        val failed = r.endpoint in failedEndpoints
        h.rank.text = (pos + 1).toString()
        h.rank.alpha = if (failed) 0.45f else 1f
        h.ip.text = r.endpoint
        h.ip.alpha = if (failed) 0.45f else 1f
        val tags = mutableListOf<String>()
        if (failed) {
            tags.add("verify failed")
        } else {
            if (!ScanHelper.isListed(r.ip)) tags.add("UNLISTED")
            if (r.jitterMs != null) tags.add("±${r.jitterMs}ms")
        }
        if (tags.isEmpty()) {
            h.tag.visibility = View.GONE
        } else {
            h.tag.visibility = View.VISIBLE
            h.tag.text = tags.joinToString(" · ")
        }
        val ms = r.latencyMs
        h.ms.text = if (failed) "✗ failed" else if (ms != null) "$ms ms" else "? ms"
        h.ms.setBackgroundColor(
            when {
                ms == null -> Color.parseColor("#3A3A3E")
                ms < 100 -> Color.parseColor("#1B5E20")
                ms < 160 -> Color.parseColor("#7A5C00")
                else -> Color.parseColor("#8A3B12")
            }
        )

        h.copy.text = "⧉"
        h.copy.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("endpoint", r.endpoint))
            h.copy.text = "✓"
            Toast.makeText(ctx, r.endpoint + " copy ကူးပြီးပြီ", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ h.copy.text = "⧉" }, 1500)
        }

        h.range.setOnClickListener { onNeighborScan(r) }

        h.copy.visibility = if (failed) View.GONE else View.VISIBLE
        h.range.visibility = if (failed) View.GONE else View.VISIBLE
    }
}
