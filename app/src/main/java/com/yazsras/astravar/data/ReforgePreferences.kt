package com.yazsras.astravar.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One asynchronous, device-local file. It never changes character mechanics or old app preferences. */
private val Context.reforgePlayerPreferences: DataStore<Preferences> by preferencesDataStore(name = "astravar-reforge-preferences")

class ReforgePreferences(private val store: DataStore<Preferences>) {
    companion object {
        private val ACTIVE_PROFILE = stringPreferencesKey("active_profile_id")
        private val SELECTED_CAMPAIGN = stringPreferencesKey("selected_campaign_id")
        val QUICK_ACTIONS = listOf("health", "attack", "spells", "inventory", "conditions", "rest", "resources", "dice")
        val DEFAULT_QUICK_ACTIONS = listOf("health", "attack", "spells", "inventory")
        const val MAX_QUICK_ACTIONS = 4

        internal fun decodeQuickActions(value: String?): List<String> = value?.split(',')
            ?.filter { it in QUICK_ACTIONS }?.distinct()?.take(MAX_QUICK_ACTIONS)
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_QUICK_ACTIONS
        fun open(context: Context): ReforgePreferences = ReforgePreferences(context.applicationContext.reforgePlayerPreferences)

        /** Stable explicit choice wins; absent/stale choices prefer the established primary profile. */
        fun chooseProfile(savedId: String?, availableIds: Collection<String>): String? = when {
            savedId != null && savedId in availableIds -> savedId
            "local-player" in availableIds -> "local-player"
            else -> availableIds.firstOrNull()
        }
    }
    val activeProfileId: Flow<String?> = store.data.map { it[ACTIVE_PROFILE] }
    val selectedCampaignId: Flow<String?> = store.data.map { it[SELECTED_CAMPAIGN] }

    /** Navigation context only: selecting a campaign never assigns an actor to it. */
    suspend fun selectCampaign(id: String?) {
        id?.let(::checkId)
        store.edit { preferences ->
            if (id == null) preferences.remove(SELECTED_CAMPAIGN)
            else preferences[SELECTED_CAMPAIGN] = id
        }
    }

    suspend fun selectProfile(profileId: String) {
        checkId(profileId)
        store.edit { it[ACTIVE_PROFILE] = profileId }
    }

    /** Device-local navigation preference, never a character revision or gameplay mutation. */
    fun quickActions(profileId: String): Flow<List<String>> {
        checkId(profileId)
        val key = stringPreferencesKey("quick_actions_${reforgeHash(profileId)}")
        return store.data.map { decodeQuickActions(it[key]) }
    }

    suspend fun saveQuickActions(profileId: String, actions: List<String>) {
        checkId(profileId)
        require(actions.size in 1..MAX_QUICK_ACTIONS && actions.distinct() == actions && actions.all { it in QUICK_ACTIONS }) {
            "Choose one to four distinct quick actions."
        }
        val key = stringPreferencesKey("quick_actions_${reforgeHash(profileId)}")
        store.edit { it[key] = actions.joinToString(",") }
    }
}
