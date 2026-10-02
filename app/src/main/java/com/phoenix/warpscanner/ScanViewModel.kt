package com.phoenix.warpscanner

import androidx.lifecycle.ViewModel

/** Activity-scoped: quick-scan results survive tab switches and are saved to backup. */
class ScanViewModel : ViewModel() {
    val results = mutableListOf<ScanResult>()
    private val seen = mutableSetOf<String>()

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
