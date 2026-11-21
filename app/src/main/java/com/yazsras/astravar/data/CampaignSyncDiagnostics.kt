package com.yazsras.astravar.data

import android.content.Context
import kotlinx.serialization.json.*

/** Installation-local metadata only. It never stores records, credentials or provider error text. */
internal enum class CampaignSyncStage {
    CREDENTIAL_VALIDATION, GRANT_READ, GRANT_UPDATE, PENDING_FETCH, LOCAL_COMMIT,
    LOCAL_COMMIT_RECOVERY, PUBLICATION_UPLOAD, PUBLICATION_ACKNOWLEDGEMENT,
    PUBLICATION_READBACK, PUBLICATION_VALIDATION, PUBLICATION_RECOVERY, LOCAL_SNAPSHOT,
    SNAPSHOT_CONFIRMATION, RECORD_READBACK, DECISION_UPLOAD, RECEIPT_READBACK, COMPLETE,
    WAITING_FOR_PENDING_WORK
}
internal fun campaignSyncFailureCode(code: String): String = code.takeIf { it in setOf(
    "AUTHENTICATION_REQUIRED", "CREDENTIAL_REVOKED_OR_EXPIRED", "CREDENTIAL_SCOPE_OR_EXPIRY",
    "CREDENTIAL_STORAGE_UNAVAILABLE", "CREDENTIAL_PERSISTENCE_FAILED", "CAMPAIGN_GRANT_EXPIRED",
    "CAMPAIGN_NOT_AUTHORIZED", "PRIVATE_GATEWAY_AUTH_REQUIRED", "SERVICE_AUTHORIZATION_REQUIRED",
    "ACCESS_DENIED", "READ_SCOPE_DENIED", "WRITE_SCOPE_DENIED", "AUTO_SYNC_SCOPE_DENIED",
    "UNAUTHORIZED_RECORD_FIELDS", "REVISION_CONFLICT", "GRANT_REVISION_CONFLICT", "GRANT_CHANGED",
    "MIRROR_SNAPSHOT_CONFLICT", "COMMAND_ID_REUSED", "RECORD_NOT_PUBLISHED", "PUBLISHED_RECORD_MISSING",
    "PUBLICATION_NOT_FOUND", "REMOVED_RECORD_REAPPEARED", "QUOTA_REACHED", "REMOTE_UNAVAILABLE",
    "BODY_TOO_LARGE", "INVALID_RECORD_FIELDS", "RECORD_TOO_LARGE", "INVALID_RESPONSE",
    "MALFORMED_SERVICE_RESPONSE", "NETWORK_UNAVAILABLE", "NETWORK_TIMEOUT", "TLS_FAILURE",
    "PUBLICATION_ACKNOWLEDGEMENT_INVALID", "PUBLICATION_READBACK_INVALID", "PUBLICATION_REVISION_MISMATCH",
    "SYNC_INTERRUPTED", "AUTOMATIC_SYNC_PAUSED", "CONNECTION_UNVERIFIED", "LOCAL_STORAGE_FAILED"
) } ?: "REMOTE_REJECTED"

internal class CampaignSyncDiagnostics(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("astravar-campaign-sync-diagnostics", Context.MODE_PRIVATE)
    private fun binding(value: CampaignConnectionSettings) = OwnerSyncProtocol.hash("${value.origin}\n${value.campaignId}\n${value.deviceId}")
    private fun editor(value: CampaignConnectionSettings) = preferences.edit().also {
        if (preferences.getString("binding", null) != binding(value)) it.clear().putString("binding", binding(value))
    }
    private fun save(edit: android.content.SharedPreferences.Editor) {
        if (!edit.commit()) throw OwnerSyncFailure("LOCAL_STORAGE_FAILED")
    }
    fun stage(value: CampaignConnectionSettings, stage: CampaignSyncStage) = save(editor(value).putString("currentStage", stage.name))
    fun begin(value: CampaignConnectionSettings) = save(editor(value).putString("currentStage", CampaignSyncStage.CREDENTIAL_VALIDATION.name)
        .remove("failedStage").remove("failureCode"))
    fun time(value: CampaignConnectionSettings, key: String, now: Long) {
        require(key in times && now > 0)
        save(editor(value).putLong(key, now))
    }
    fun failed(value: CampaignConnectionSettings, code: String) {
        val current = if (preferences.getString("binding", null) == binding(value)) preferences.getString("currentStage", "") else ""
        save(editor(value).putString("failedStage", current).putString("failureCode", campaignSyncFailureCode(code)))
    }
    fun snapshot(value: CampaignConnectionSettings, pendingRecords: Int): JsonObject = buildJsonObject {
        val matches = preferences.getString("binding", null) == binding(value)
        times.forEach { put(it, if (matches) preferences.getLong(it, 0).coerceAtLeast(0) else 0) }
        listOf("currentStage", "failedStage").forEach { key ->
            val stored = if (matches) preferences.getString(key, "").orEmpty() else ""
            put(key, stored.takeIf { candidate -> CampaignSyncStage.entries.any { it.name == candidate } }.orEmpty())
        }
        val error = if (matches) preferences.getString("failureCode", "").orEmpty() else ""
        put("failureCode", if (error.isEmpty()) "" else campaignSyncFailureCode(error))
        put("pendingRecordCount", pendingRecords.coerceAtLeast(0))
    }
    companion object {
        val times = listOf("lastServerResponseAt", "lastUploadAttemptAt", "lastPublicationAcceptedAt", "lastIndependentVerificationAt", "lastFullSyncAt")
    }
}
