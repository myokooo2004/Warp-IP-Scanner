package com.phoenix.warpscanner

import android.content.Context
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

/**
 * Runs the bundled cf-scanner engine (static binary in assets/) and parses
 * its NDJSON output. Only endpoints with 0% packet loss are treated as
 * stable; everything else is dropped before it reaches the UI.
 */
class ScanEngine(private val ctx: Context) {

    private var process: Process? = null

    @Volatile
    var running = false
        private set

    /**
     * The engine ships as a native library (jniLibs/arm64-v8a/libcf-scanner.so)
     * so it lands in applicationInfo.nativeLibraryDir at install time —
     * the one place Android reliably allows executing bundled binaries.
     * (Executing from the app's private files dir fails with EACCES on
     * modern Android.)
     */
    fun ensureBinary(): File {
        val libFile = File(ctx.applicationInfo.nativeLibraryDir, "libcf-scanner.so")
        if (!libFile.exists() || libFile.length() == 0L) {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            throw IllegalStateException("engine not found for this device ABI ($abi)")
        }
        try { libFile.setExecutable(true) } catch (_: Exception) { /* already executable */ }
        return libFile
    }

    fun cancel() {
        running = false
        try { process?.destroyForcibly() } catch (_: Exception) {}
    }

    /**
     * PARTIAL_WAKE_LOCK held for the whole scan. The scanner runs as a
     * background subprocess while the screen may be off — without this,
     * Doze can stall the CPU mid-scan and corrupt latency/jitter.
     * Released in the thread's finally, even on cancel/failure.
     */
    private fun acquireScanWakeLock(): PowerManager.WakeLock? {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WarpScanner:ScanWakeLock")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(15 * 60 * 1000L)
                }
        } catch (_: Exception) { null }
    }

    /**
     * @param args engine CLI args, e.g. ["scan","--mode","warp","--count","300",...]
     * Callbacks are invoked on background threads; post to the UI thread yourself.
     */
    fun scan(
        args: List<String>,
        onResult: (ScanResult) -> Unit,
        onProgress: (String) -> Unit,
        onDone: (ok: Boolean, err: String?) -> Unit
    ) {
        running = true
        Thread {
            val wakeLock = acquireScanWakeLock()
            try {
                val bin = ensureBinary()
                val cmd = mutableListOf(bin.absolutePath).apply { addAll(args) }
                process = ProcessBuilder(cmd).start()
                val p = process ?: throw IllegalStateException("process failed to start")

                val errThread = Thread {
                    try {
                        p.errorStream.bufferedReader().forEachLine { line ->
                            val t = line.trim()
                            if (t.contains("checked")) onProgress(t)
                        }
                    } catch (_: Exception) { /* stream closed on cancel */ }
                }
                errThread.isDaemon = true
                errThread.start()

                p.inputStream.bufferedReader().forEachLine { line ->
                    val t = line.trim()
                    if (!t.startsWith("{")) return@forEachLine
                    try {
                        val o = JSONObject(t)
                        when (o.optString("type")) {
                            "result" -> onResult(
                                ScanResult(
                                    ip = o.getString("ip"),
                                    port = o.getInt("port"),
                                    latencyMs = if (o.isNull("latency_ms")) null else o.optLong("latency_ms"),
                                    lossPct = o.optInt("loss_pct", 0),
                                    jitterMs = if (o.isNull("jitter_ms")) null else o.optLong("jitter_ms")
                                )
                            )
                            "finished" -> onProgress(
                                "done — found ${o.optInt("found", 0)}"
                            )
                        }
                    } catch (_: Exception) { /* ignore malformed line */ }
                }

                val code = p.waitFor()
                errThread.join(2000)
                running = false
                onDone(code == 0, if (code == 0) null else "engine exit=$code")
            } catch (e: Exception) {
                running = false
                onDone(false, e.message ?: "unknown error")
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
            }
        }.start()
    }
}
