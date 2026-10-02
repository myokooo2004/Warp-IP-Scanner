package com.phoenix.warpscanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Persistent backup list of known-good endpoints: copy, export CSV, clear. */
class BackupFragment : Fragment() {

    private lateinit var adapter: BackupAdapter
    private var entries: MutableList<BackupEntry> = mutableListOf()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) exportLegacy() else toast("Storage permission denied")
        }

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? =
        inf.inflate(R.layout.fragment_backup, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        val rv = v.findViewById<RecyclerView>(R.id.rvBackup)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = BackupAdapter(
            onCopy = { e ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("endpoint", e.endpoint))
                toast(e.endpoint + " copied")
            },
            onDelete = { e ->
                BackupStore.remove(requireContext(), e.ip, e.port)
                refresh()
            }
        )
        rv.adapter = adapter

        v.findViewById<TextView>(R.id.btnExport).setOnClickListener { export() }
        v.findViewById<TextView>(R.id.btnClear).setOnClickListener {
            BackupStore.clear(requireContext())
            refresh()
            toast("Backup cleared")
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = BackupStore.load(requireContext())
        adapter.items = entries
        view?.findViewById<TextView>(R.id.tvBackupCount)?.text = "Saved (${entries.size})"
    }

    private fun buildCsv(): String {
        val sb = StringBuilder("ip,port,latency_ms,saved_at\n")
        for (e in entries) {
            sb.append(e.ip).append(',')
                .append(e.port).append(',')
                .append(e.ms ?: "").append(',')
                .append(e.savedAt).append('\n')
        }
        return sb.toString()
    }

    /** Save the CSV straight into the Download folder (no share sheet). */
    private fun export() {
        if (entries.isEmpty()) {
            toast("Nothing to export")
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            exportMediaStore()
        } else {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                exportLegacy()
            } else {
                permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun exportMediaStore() {
        val name = "warp-endpoints-${System.currentTimeMillis()}.csv"
        try {
            val resolver = requireContext().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { it.write(buildCsv().toByteArray()) }
                ?: throw IllegalStateException("cannot open output")
            toast("Saved to Download/$name")
        } catch (e: Exception) {
            toast("Export error: ${e.message}")
        }
    }

    private fun exportLegacy() {
        val name = "warp-endpoints-${System.currentTimeMillis()}.csv"
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(buildCsv())
            toast("Saved to Download/$name")
        } catch (e: Exception) {
            toast("Export error: ${e.message}")
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
            val del: TextView = v.findViewById(R.id.btnDelete)
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
