package com.yazsras.astravar.data

import kotlinx.serialization.json.*

// Only schema labels and machine codes cross the private bridge. Never include exception text or values.
internal class CampaignConflict(
    val code: String, val operation: Int, val field: String? = null,
    val observedRevision: Long? = null
) : IllegalArgumentException(code) {
    fun json() = buildJsonObject {
        put("code", code)
        put("operation", operation)
        field?.let { put("field", it) }
        observedRevision?.let { put("observedRevision", it) }
    }
    companion object {
        fun domain(error: RuntimeException, index: Int): CampaignConflict {
            if (error is CampaignConflict) return error
            val code = when (error.message) {
                "Acquiring holder changed." -> "HOLDER_REVISION_CHANGED"
                "Acquisition placement already exists.", "Physical item identity is unavailable." -> "OWNERSHIP_CONFLICT"
                "Acquisition fields conflict with its placement." -> "PLACEMENT_MISMATCH"
                "Managed equipment requires an explicit equipped definition." -> "EQUIPMENT_REVIEW_REQUIRED"
                "Insufficient currency or balance exceeds the supported limit." -> "CURRENCY_BOUNDS"
                "This denomination's opening balance is unknown. No currency changed." -> "UNKNOWN_BALANCE"
                "Record changed or is outside selected campaign." -> "BASELINE_CHANGED"
                else -> "DOMAIN_VALIDATION"
            }
            return CampaignConflict(code, index)
        }
    }
}
