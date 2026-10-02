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

    fun rangeScanArgs(cidrsCsv: String, portsCsv: String, target: Int): List<String> = listOf(
        "scan", "--mode", "warp",
        "--custom-cidrs", cidrsCsv,
        "--ports", portsCsv,
        "--target", target.toString(),
        "--network-profile", "blocked"
    )

    /** "8.34.146.207" -> "8.34.146.0/24" for the per-endpoint neighbor scan. */
    fun cidr24(ip: String): String {
        val base = ip.substringBeforeLast(".", ip)
        return if (base == ip) ip else "$base.0/24"
    }
}
