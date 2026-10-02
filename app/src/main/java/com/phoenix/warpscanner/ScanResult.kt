package com.phoenix.warpscanner

/** One working WARP endpoint found by the scanner engine. */
data class ScanResult(
    val ip: String,
    val port: Int,
    /** Round-trip ms of the WireGuard handshake probe; null if the engine did not report it. */
    val latencyMs: Long?,
    /** Packet-loss percent across the engine's handshake probes (0 = perfectly stable). */
    val lossPct: Int
) {
    val endpoint: String get() = "$ip:$port"
}
