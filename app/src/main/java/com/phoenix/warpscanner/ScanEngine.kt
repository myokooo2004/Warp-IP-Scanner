package com.phoenix.warpscanner

import android.content.Context
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

    /** Copy the engine binary from assets to a private executable location. */
    fun ensureBinary(): File {
        val binDir = File(ctx.filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        val bin = File(binDir, "cf-scanner")
        if (!bin.exists() || bin.length() == 0L) {
            try {
                ctx.assets.open("cf-scanner").use { input ->
                    bin.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                throw IllegalStateException("engine မပါဘူး — APK အသစ်ပြန် install လုပ်ပါ")
            }
        }
        bin.setExecutable(true)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", bin.absolutePath)).waitFor()
        } catch (_: Exception) { /* setExecutable already tried */ }
        if (!bin.canExecute()) throw IllegalStateException("engine binary is not executable")
        return bin
    }

    fun cancel() {
        running = false
        try { process?.destroyForcibly() } catch (_: Exception) {}
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
                                    lossPct = o.optInt("loss_pct", 0)
                                )
                            )
                            "finished" -> onProgress(
                                "ပြီးပြီ — ${o.optInt("found", 0)} ခု တွေ့တယ်"
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
            }
        }.start()
    }
}
