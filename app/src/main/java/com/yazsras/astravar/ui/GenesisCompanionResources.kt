package com.yazsras.astravar.ui

import androidx.compose.runtime.*
import com.yazsras.astravar.data.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

/** Visibility is based on physical source identities, never a resource's mutable display name. */
internal data class GenesisCompanionResourceAccess(
    val loaded: Boolean = true,
    val managed: Boolean = false,
    val activeSourceIds: Set<String> = emptySet(),
    val problem: String? = null
) {
    fun editable(sourceId: String?): Boolean = loaded && problem == null &&
        (!managed || sourceId != null && sourceId in activeSourceIds)
    fun explanation(sourceId: String?): String? = when {
        !loaded -> "Checking shared resource state…"
        problem != null -> problem
        !managed -> null
        sourceId == null -> "Add resources on the playable sheet so their identity and mechanics stay connected."
        sourceId !in activeSourceIds -> "Inactive source history. This counter is not configured on the playable sheet. Its saved values are retained."
        else -> "Shared resource. Change its name or capacity on the playable sheet; current availability is shared here."
    }
}

internal fun genesisCompanionResourceAccess(
    actorId: String, actor: GenesisActorAuthority?, saved: ReforgeSavedCharacter?
): GenesisCompanionResourceAccess {
    if (actor == null || !actor.playable) return GenesisCompanionResourceAccess()
    if (actor.actorId != actorId || saved?.character?.id != actorId)
        return GenesisCompanionResourceAccess(managed = true, problem = "The shared playable sheet is unavailable. Retained counters cannot be edited here.")
    if (actor.playableRevision != saved.revision)
        return GenesisCompanionResourceAccess(loaded = false, managed = true)
    if (saved.character.archived)
        return GenesisCompanionResourceAccess(managed = true, problem = "The playable sheet is archived. Restore it before changing shared resources.")
    return runCatching {
        rejectDuplicateJsonMembers(actor.resourceBindingsJson)
        val bindings = campaignJson.decodeFromString<Map<String, String>>(actor.resourceBindingsJson)
        require(bindings.values.distinct().size == bindings.size)
        val ids = saved.character.resources.map { genesisResourceId(actor, it.id) }
        require(ids.distinct().size == ids.size)
        GenesisCompanionResourceAccess(managed = true, activeSourceIds = ids.toSet())
    }.getOrElse {
        GenesisCompanionResourceAccess(managed = true, problem = "Resource associations need review on the playable sheet. Retained counters cannot be edited here.")
    }
}

@Composable internal fun rememberGenesisCompanionResourceAccess(actorId: String): GenesisCompanionResourceAccess {
    val environment = LocalGenesisConnectedEnvironment.current ?: return GenesisCompanionResourceAccess()
    val flow = remember(environment.authority, environment.local, actorId) {
        combine(environment.authority.actorAssociations, environment.local.observe(actorId)) { actors, saved ->
            genesisCompanionResourceAccess(actorId, actors.firstOrNull { it.actorId == actorId }, saved)
        }.catch {
            emit(GenesisCompanionResourceAccess(managed = true,
                problem = "Shared resource state could not be loaded. Reopen the sheet before editing counters."))
        }
    }
    return flow.collectAsState(initial = GenesisCompanionResourceAccess(loaded = false)).value
}
