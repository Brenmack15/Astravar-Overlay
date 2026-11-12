package com.yazsras.astravar.ui

import com.yazsras.astravar.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GenesisCraftRecovery(val pending: GenesisPendingCraft?, val outcome: GenesisCraftingOutcome?)

/** Serializes app surfaces across the durable intent write and the authoritative Room transaction. */
internal object GenesisCraftSubmission {
    private val mutex = Mutex()

    suspend fun recover(environment: GenesisConnectedEnvironment, projectId: String, restored: GenesisPendingCraft?): GenesisCraftRecovery = mutex.withLock {
        require(restored == null || restored.projectId == projectId) { "The restored craft belongs to another invention." }
        val durable = environment.preferences.pendingCraft(projectId)
        check(restored == null || durable == null || restored == durable) {
            "Another craft is awaiting recovery. Close and reopen this invention to review its saved outcome."
        }
        val token = durable ?: restored
        GenesisCraftRecovery(token, token?.let { completed(environment, it) })
    }

    suspend fun submit(environment: GenesisConnectedEnvironment, review: GenesisCraftingReview, token: GenesisPendingCraft): GenesisCraftingOutcome = mutex.withLock {
        require(token.projectId == review.project.id && token.reviewFingerprint == review.fingerprint) { "The pending craft differs from this review." }
        environment.preferences.rememberPendingCraft(token)
        environment.authority.crafting.accept(review, token.commandId)
    }

    /** Recheck under the submission lock: a concurrent activity may have finished since lookup. */
    suspend fun reviewAgain(environment: GenesisConnectedEnvironment, token: GenesisPendingCraft): GenesisCraftingOutcome? = mutex.withLock {
        requireMatchingIntent(environment, token)
        completed(environment, token).also { outcome ->
            if (outcome == null) environment.preferences.clearPendingCraft(token)
        }
    }

    suspend fun acknowledge(environment: GenesisConnectedEnvironment, token: GenesisPendingCraft): GenesisCraftingOutcome = mutex.withLock {
        requireMatchingIntent(environment, token)
        requireNotNull(completed(environment, token)) { "The craft has no saved receipt. Review the pending outcome before continuing." }
            .also { environment.preferences.clearPendingCraft(token) }
    }

    private suspend fun requireMatchingIntent(environment: GenesisConnectedEnvironment, token: GenesisPendingCraft) {
        val durable = environment.preferences.pendingCraft(token.projectId)
        check(durable == null || durable == token) { "Another craft is awaiting recovery. Close and reopen this invention." }
    }

    private suspend fun completed(environment: GenesisConnectedEnvironment, token: GenesisPendingCraft) =
        environment.authority.crafting.completed(token.projectId, token.reviewFingerprint, token.commandId)
}
