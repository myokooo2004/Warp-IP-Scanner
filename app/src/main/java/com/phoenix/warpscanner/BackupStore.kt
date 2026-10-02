package com.phoenix.warpscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BackupEntry(
    val ip: String,
    val port: Int,
    val ms: Long?,
    val savedAt: Long
) {
    val endpoint: String get() = "$ip:$port"
}

/** Persistent backup list of known-good endpoints (simple JSON file). */
object BackupStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "backup_endpoints.json")

    fun load(ctx: Context): MutableList<BackupEntry> {
        val out = mutableListOf<BackupEntry>()
        val f = file(ctx)
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    BackupEntry(
                        ip = o.getString("ip"),
                        port = o.getInt("port"),
                        ms = if (o.isNull("ms")) null else o.getLong("ms"),
                        savedAt = o.optLong("savedAt", 0L)
                    )
                )
            }
        } catch (_: Exception) { /* corrupted file -> start fresh */ }
        out.sortWith(compareBy({ it.ms ?: Long.MAX_VALUE }, { it.ip }))
        return out
    }

    private fun save(ctx: Context, list: List<BackupEntry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject().apply {
                    put("ip", e.ip)
                    put("port", e.port)
                    if (e.ms != null) put("ms", e.ms) else put("ms", JSONObject.NULL)
                    put("savedAt", e.savedAt)
                })
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) { /* best effort */ }
    }

    /** Add or refresh an entry; keeps the lowest observed ms per endpoint. */
    fun add(ctx: Context, entry: BackupEntry) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.ip == entry.ip && it.port == entry.port }
        if (i >= 0) {
            val old = list[i]
            val bestMs = when {
                old.ms == null -> entry.ms
                entry.ms == null -> old.ms
                else -> minOf(old.ms, entry.ms)
            }
            list[i] = old.copy(ms = bestMs, savedAt = entry.savedAt)
        } else {
            list.add(entry)
        }
        list.sortWith(compareBy({ it.ms ?: Long.MAX_VALUE }, { it.ip }))
        save(ctx, list)
    }

    fun remove(ctx: Context, ip: String, port: Int) {
        val list = load(ctx)
        list.removeAll { it.ip == ip && it.port == port }
        save(ctx, list)
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }

    /** Write the backup list as CSV into the app cache for sharing. */
    fun exportCsv(ctx: Context): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "warp-endpoints.csv")
        val sb = StringBuilder("ip,port,latency_ms,saved_at\n")
        for (e in load(ctx)) {
            sb.append(e.ip).append(',')
                .append(e.port).append(',')
                .append(e.ms ?: "").append(',')
                .append(e.savedAt).append('\n')
        }
        out.writeText(sb.toString())
        return out
    }
}
