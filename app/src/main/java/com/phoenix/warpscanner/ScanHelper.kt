package com.phoenix.warpscanner

/** Shared scan recipes and stability rules. */
object ScanHelper {

    /** Preset WARP pools offered on the quick-scan screen. */
    val PRESET_POOLS = listOf(
        "162.159.192.0/24",
        "162.159.193.0/24",
        "188.114.96.0/24",
        "8.34.146.0/24",
        "8.35.211.0/24",
        "8.39.204.0/24"
    )

    val PRESET_PORTS = listOf("500", "2408", "1701", "4500")

    /** Default CIDRs pre-filled on the Range tab (known-good 8.x ranges). */
    const val DEFAULT_RANGE_CIDRS = "8.34.146.0/24,8.35.211.0/24,8.39.204.0/24"

    /**
     * Stability rule: keep an endpoint only when EVERY handshake probe
     * answered (0% loss). Lossy endpoints make unstable tunnels, so they
     * are hidden from the results entirely.
     */
    fun isStable(r: ScanResult): Boolean = r.lossPct == 0

    /** Sort key: lowest latency first, unknown latency last. */
    fun sortByMs(list: MutableList<ScanResult>) {
        list.sortWith(compareBy({ it.latencyMs ?: Long.MAX_VALUE }, { it.ip }))
    }

    fun quickScanArgs(portsCsv: String, target: Int): List<String> = listOf(
        "scan", "--mode", "warp",
        "--count", "300",
        "--ports", portsCsv,
        "--target", target.toString(),
        "--network-profile", "blocked"
    )

    /**
     * NOTE: --custom-cidrs is CDN-only in the engine; WARP mode rejects it.
     * So the CIDR is enumerated into explicit --warp-endpoints (bare IPs —
     * the engine pairs them with --ports). Enumeration is capped to keep
     * the command line sane.
     */
    fun rangeScanArgs(cidrsCsv: String, portsCsv: String, target: Int): List<String> {
        val endpoints = cidrsCsv.split(",")
            .flatMap { enumerateHosts(it.trim()) }
            .take(1024)
        val args = mutableListOf("scan", "--mode", "warp")
        if (endpoints.isNotEmpty()) {
            // Explicit endpoints: --count caps how many of them get probed.
            args += listOf("--warp-endpoints", endpoints.joinToString(","))
            args += listOf("--count", endpoints.size.toString())
        } else {
            // Enumeration failed: fall back to a count-limited pool scan.
            args += listOf("--count", "300")
        }
        args += listOf(
            "--ports", portsCsv,
            "--target", target.toString(),
            "--network-profile", "blocked"
        )
        return args
    }

    /** "192.168.1.0/24" -> ["192.168.1.1", …, "192.168.1.254"] (network/broadcast excluded). */
    fun enumerateHosts(cidr: String): List<String> {
        return try {
            val parts = cidr.split("/")
            val base = parts[0].split(".").map { it.toInt() }
            if (base.size != 4 || base.any { it !in 0..255 }) return emptyList()
            val prefix = parts.getOrNull(1)?.toIntOrNull() ?: 32
            if (prefix !in 0..32) return emptyList()
            val baseInt = (base[0].toLong() shl 24) or (base[1].toLong() shl 16) or
                    (base[2].toLong() shl 8) or base[3].toLong()
            val hostBits = 32 - prefix
            val total = 1L shl hostBits
            if (total > 4096) return emptyList()
            val mask = if (hostBits == 32) 0L else (-1L shl hostBits) and 0xFFFFFFFFL
            val net = baseInt and mask
            (0 until total)
                .map { net + it }
                .filter { !(total > 2 && (it == net || it == net + total - 1)) }
                .map { h ->
                    "${(h shr 24) and 0xFF}.${(h shr 16) and 0xFF}.${(h shr 8) and 0xFF}.${h and 0xFF}"
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Phase-2 handshake verification: full WireGuard handshake (using the
     * user's own .conf keys) against exactly these endpoints. Only endpoints
     * that complete the handshake are reported by the engine.
     */
    fun verifyArgs(endpoints: List<ScanResult>, confPath: String): List<String> = listOf(
        "scan", "--mode", "warp",
        "--warp-endpoints", endpoints.joinToString(",") { it.endpoint },
        "--warp-verify",
        "--warp-wgconf-file", confPath,
        "--count", endpoints.size.toString(),
        "--target", endpoints.size.toString(),
        "--network-profile", "blocked"
    )

    /** "8.34.146.207" -> "8.34.146.0/24" for the per-endpoint neighbor scan. */
    fun cidr24(ip: String): String {
        val base = ip.substringBeforeLast(".", ip)
        return if (base == ip) ip else "$base.0/24"
    }
}
