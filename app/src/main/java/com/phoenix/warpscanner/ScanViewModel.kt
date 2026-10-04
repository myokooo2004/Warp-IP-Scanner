package com.phoenix.warpscanner

import androidx.lifecycle.ViewModel

/** Activity-scoped: quick-scan results survive tab switches and are saved to backup. */
class ScanViewModel : ViewModel() {
    val results = mutableListOf<ScanResult>()
    private val seen = mutableSetOf<String>()

    /**
     * /24 CSV of the latest verified scan (rank order), e.g.
     * "8.34.70.0/24,8.39.214.0/24". The Range tab auto-fills its CIDR
     * field from this unless the user typed their own CIDRs.
     */
    var verifiedCidrs: String = ""

    /** True once the user manually edits the Range tab CIDR field. */
    var rangeCidrsManual: Boolean = false

    /**
     * Deep-link from the Stable tab: "explore this winner's /24".
     * When set, the Range tab pre-fills CIDR+port and auto-starts on open.
     * Consumed (cleared) by RangeFragment.
     */
    var pendingExploreCidr: String? = null
    var pendingExplorePort: String? = null

    /** @return true if this endpoint is new */
    fun add(r: ScanResult): Boolean {
        if (!seen.add(r.endpoint)) return false
        results.add(r)
        results.sortWith(
            compareBy(
                { if (ScanHelper.isListed(it.ip)) 1 else 0 },
                { (it.latencyMs ?: Long.MAX_VALUE) + (it.jitterMs ?: 0L) },
                { it.ip }
            )
        )
        return true
    }

    /** Final order after a scan: staged rank with history tiebreak. */
    fun sortStaged(goodCount: (String) -> Int) {
        val ranked = ScanHelper.ranked(results, goodCount)
        results.clear()
        results.addAll(ranked)
    }

    fun clear() {
        results.clear()
        seen.clear()
    }
}
