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
 * Stable-tab rows: rank, IP:port, "✓ N times" history badge, ms badge,
 * per-row copy. Row tap also copies (consistent with the Backup tab).
 */
class StableAdapter(
    private val goodCount: (String) -> Int,
    private val onExplore: (ScanResult) -> Unit
) : RecyclerView.Adapter<StableAdapter.VH>() {

    var items: List<ScanResult> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

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

    private fun copyEndpoint(ctx: Context, endpoint: String, copyBtn: Button) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("endpoint", endpoint))
        copyBtn.text = "✓"
        Toast.makeText(ctx, "$endpoint copy ကူးပြီးပြီ", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ copyBtn.text = "⧉" }, 1500)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = items[pos]
        val ctx = h.itemView.context
        h.rank.text = (pos + 1).toString()
        h.ip.text = r.endpoint
        val tags = mutableListOf<String>()
        if (pos == 0) tags.add("★ BEST")
        if (!ScanHelper.isListed(r.ip)) tags.add("UNLISTED")
        if (r.jitterMs != null) tags.add("±${r.jitterMs}ms")
        val n = goodCount(r.endpoint)
        if (n > 0) tags.add("✓ $n times") else tags.add("✓ new")
        h.tag.visibility = View.VISIBLE
        h.tag.text = tags.joinToString(" · ")
        val ms = r.latencyMs
        h.ms.text = if (ms != null) "$ms ms" else "? ms"
        h.ms.setBackgroundColor(
            when {
                ms == null -> Color.parseColor("#3A3A3E")
                ms < 100 -> Color.parseColor("#1B5E20")
                ms < 160 -> Color.parseColor("#7A5C00")
                else -> Color.parseColor("#8A3B12")
            }
        )
        h.copy.text = "⧉"
        h.copy.setOnClickListener { copyEndpoint(ctx, r.endpoint, h.copy) }
        h.itemView.setOnClickListener { copyEndpoint(ctx, r.endpoint, h.copy) }
        // "/24" explores this winner's neighborhood in the Range tab.
        h.range.visibility = View.VISIBLE
        h.range.setOnClickListener { onExplore(r) }
    }
}
