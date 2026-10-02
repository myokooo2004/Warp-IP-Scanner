package com.phoenix.warpscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Persistent backup list of known-good endpoints: copy, export CSV, clear. */
class BackupFragment : Fragment() {

    private lateinit var adapter: BackupAdapter
    private var entries: MutableList<BackupEntry> = mutableListOf()

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_backup, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.rvBackup)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = BackupAdapter(
            onCopy = { e ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("endpoint", e.endpoint))
                toast(e.endpoint + " copy ကူးပြီးပြီ")
            },
            onDelete = { e ->
                BackupStore.remove(requireContext(), e.ip, e.port)
                refresh()
            }
        )
        rv.adapter = adapter

        v.findViewById<Button>(R.id.btnExport).setOnClickListener { export() }
        v.findViewById<Button>(R.id.btnClear).setOnClickListener {
            BackupStore.clear(requireContext())
            refresh()
            toast("backup ရှင်းလိုက်ပြီ")
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = BackupStore.load(requireContext())
        adapter.items = entries
        view?.findViewById<TextView>(R.id.tvBackupCount)?.text = "သိမ်းထားတာ (${entries.size})"
    }

    private fun export() {
        if (entries.isEmpty()) {
            toast("export လုပ်စရာ မရှိဘူး")
            return
        }
        try {
            val csv = BackupStore.exportCsv(requireContext())
            val uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".fileprovider",
                csv
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "CSV ပို့မယ်"))
        } catch (e: Exception) {
            toast("export အမှား: ${e.message}")
        }
    }

    private fun toast(m: String) =
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    class BackupAdapter(
        private val onCopy: (BackupEntry) -> Unit,
        private val onDelete: (BackupEntry) -> Unit
    ) : RecyclerView.Adapter<BackupAdapter.VH>() {

        var items: List<BackupEntry> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ip: TextView = v.findViewById(R.id.tvIp)
            val ms: TextView = v.findViewById(R.id.tvMs)
            val del: Button = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_backup, p, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.ip.text = e.endpoint
            h.ms.text = if (e.ms != null) "${e.ms} ms" else ""
            h.itemView.setOnClickListener { onCopy(e) }
            h.del.setOnClickListener { onDelete(e) }
        }
    }
}
