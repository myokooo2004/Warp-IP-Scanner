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

    /**
     * Publicly-listed Cloudflare WARP ranges. Operators block these first
     * (they are on Cloudflare's public IP list), so endpoints inside them
     * rank below unlisted ones (block-resistance).
     */
    private val LISTED_WARP_CIDRS = listOf(
        "162.159.192.0/24",
        "162.159.193.0/24",
        "188.114.96.0/24",
        "188.114.97.0/24",
        "188.114.98.0/24",
        "188.114.99.0/24"
    )

    private fun ipToLong(ip: String): Long? {
        val p = ip.split(".")
        if (p.size != 4) return null
        val b = p.map { it.toIntOrNull() ?: return null }
        if (b.any { it !in 0..255 }) return null
        return (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or
                (b[2].toLong() shl 8) or b[3].toLong()
    }

    private fun cidrContains(cidr: String, ip: String): Boolean {
        val slash = cidr.indexOf('/')
        if (slash < 0) return false
        val base = ipToLong(cidr.substring(0, slash)) ?: return false
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        val addr = ipToLong(ip) ?: return false
        val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
        return (base and mask) == (addr and mask)
    }

    /** True when the IP sits inside a publicly-listed WARP range. */
    fun isListed(ip: String): Boolean = LISTED_WARP_CIDRS.any { cidrContains(it, ip) }

    /**
     * Staged rank for the one-tap pipeline (lower is better):
     *  1. unlisted before listed (block-resistance),
     *  2. then ms + jitter (stability),
     *  3. then past success count (proven endpoints win ties),
     *  4. then IP as a stable tiebreak.
     */
    fun ranked(
        candidates: List<ScanResult>,
        goodCount: (String) -> Int = { 0 }
    ): List<ScanResult> =
        candidates.sortedWith(
            compareBy(
                { if (isListed(it.ip)) 1 else 0 },
                { (it.latencyMs ?: Long.MAX_VALUE) + (it.jitterMs ?: 0L) },
                { -goodCount(it.endpoint) },
                { it.ip }
            )
        )

    /**
     * /24 diversity: at most one endpoint per /24 in the final list, so a
     * single /24 block cannot wipe out every verified endpoint. Takes the
     * best-ranked endpoint of each /24, then the top [n].
     */
    fun diverseTop(ranked: List<ScanResult>, n: Int): List<ScanResult> {
        val seen24 = mutableSetOf<String>()
        val out = mutableListOf<ScanResult>()
        for (r in ranked) {
            val c24 = cidr24(r.ip)
            if (seen24.add(c24)) {
                out.add(r)
                if (out.size >= n) break
            }
        }
        // Not enough distinct /24s: fill from the remaining ranked candidates.
        if (out.size < n) {
            for (r in ranked) {
                if (out.none { it.endpoint == r.endpoint }) {
                    out.add(r)
                    if (out.size >= n) break
                }
            }
        }
        return out
    }

    fun quickScanArgs(portsCsv: String, target: Int): List<String> = listOf(
        "scan", "--mode", "warp",
        "--count", "300",
        "--ports", portsCsv,
        "--target", target.toString(),
        // Premium-smooth: capped concurrency keeps CPU/battery low and the
        // UI responsive; the scan just takes a little longer.
        "--concurrency", "16",
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
            // Premium-smooth: capped concurrency, same as quick scan.
            "--concurrency", "16",
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
