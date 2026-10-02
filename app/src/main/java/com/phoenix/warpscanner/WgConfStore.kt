package com.phoenix.warpscanner

import android.content.Context
import java.io.File

/**
 * Stores the user's own WARP WireGuard .conf (private key included) in
 * app-private storage only. The key is never logged, never uploaded, and
 * never leaves the device — it is passed to the bundled engine as a file
 * path for --warp-verify handshakes.
 */
object WgConfStore {

    private const val NAME = "warp-verify.conf"

    private fun file(ctx: Context): File = File(ctx.filesDir, NAME)

    fun exists(ctx: Context): Boolean {
        val f = file(ctx)
        return f.exists() && f.length() > 0
    }

    fun path(ctx: Context): String = file(ctx).absolutePath

    /** @return true if the text looks like a WireGuard .conf and was saved */
    fun save(ctx: Context, text: String): Boolean {
        val t = text.trim()
        if (t.length < 32) return false
        val hasInterface = t.lines().any { it.trim().equals("[Interface]", ignoreCase = true) }
        val hasKey = t.lines().any { it.trim().startsWith("PrivateKey", ignoreCase = true) }
        if (!hasInterface || !hasKey) return false
        return try {
            file(ctx).writeText(t)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) { /* best effort */ }
    }
}
