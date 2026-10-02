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

    /** @return true if this endpoint is new */
    fun add(r: ScanResult): Boolean {
        if (!seen.add(r.endpoint)) return false
        results.add(r)
        ScanHelper.sortByMs(results)
        return true
    }

    fun clear() {
        results.clear()
        seen.clear()
    }
}
