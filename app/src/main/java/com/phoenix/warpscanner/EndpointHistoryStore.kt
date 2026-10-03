package com.phoenix.warpscanner

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Long-term memory of endpoint quality across scans.
 * Remembers which endpoints verified well before (good) and which died
 * (bad), so one lucky scan cannot outrank a proven endpoint and dead
 * endpoints are not re-tried forever.
 */
object EndpointHistoryStore {

    private data class Rec(
        var good: Int = 0,
        var bad: Int = 0,
        var lastMs: Long? = null,
        var lastJitter: Long? = null,
        var lastSeen: Long = 0L
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "endpoint_history.json")

    private fun load(ctx: Context): MutableMap<String, Rec> {
        val out = mutableMapOf<String, Rec>()
        val f = file(ctx)
        if (!f.exists()) return out
        try {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                out[k] = Rec(
                    good = e.optInt("good", 0),
                    bad = e.optInt("bad", 0),
                    lastMs = if (e.isNull("lastMs")) null else e.optLong("lastMs"),
                    lastJitter = if (e.isNull("lastJitter")) null else e.optLong("lastJitter"),
                    lastSeen = e.optLong("lastSeen", 0L)
                )
            }
        } catch (_: Exception) { /* corrupted -> start fresh */ }
        return out
    }

    private fun save(ctx: Context, map: Map<String, Rec>) {
        try {
            val o = JSONObject()
            // Cap size: keep the 500 most recently seen.
            val keys = map.entries.sortedByDescending { it.value.lastSeen }.take(500)
            for ((k, r) in keys) {
                o.put(k, JSONObject().apply {
                    put("good", r.good)
                    put("bad", r.bad)
                    if (r.lastMs != null) put("lastMs", r.lastMs) else put("lastMs", JSONObject.NULL)
                    if (r.lastJitter != null) put("lastJitter", r.lastJitter) else put("lastJitter", JSONObject.NULL)
                    put("lastSeen", r.lastSeen)
                })
            }
            file(ctx).writeText(o.toString())
        } catch (_: Exception) { /* best effort */ }
    }

    /** A handshake-verified endpoint: proven good. */
    fun recordVerified(ctx: Context, endpoint: String, ms: Long?, jitter: Long?) {
        val m = load(ctx)
        val r = m.getOrPut(endpoint) { Rec() }
        r.good++
        r.lastMs = ms
        r.lastJitter = jitter
        r.lastSeen = System.currentTimeMillis()
        save(ctx, m)
    }

    /** An endpoint that failed handshake verification. */
    fun recordDead(ctx: Context, endpoint: String) {
        val m = load(ctx)
        val r = m.getOrPut(endpoint) { Rec() }
        r.bad++
        r.lastSeen = System.currentTimeMillis()
        save(ctx, m)
    }

    fun goodCount(ctx: Context, endpoint: String): Int =
        try { load(ctx)[endpoint]?.good ?: 0 } catch (_: Exception) { 0 }

    /**
     * Skip re-verifying endpoints that failed twice with no success —
     * likely blocked or dead, not worth a verify slot.
     */
    fun isKnownDead(ctx: Context, endpoint: String): Boolean {
        val r = try { load(ctx)[endpoint] } catch (_: Exception) { null } ?: return false
        return r.bad >= 2 && r.good == 0
    }
}
