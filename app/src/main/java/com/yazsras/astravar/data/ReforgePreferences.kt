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
        fun open(context: Context): ReforgePreferences = ReforgePreferences(context.applicationContext.reforgePlayerPreferences)

        /** Stable explicit choice wins; absent/stale choices prefer the established primary profile. */
        fun chooseProfile(savedId: String?, availableIds: Collection<String>): String? = when {
            savedId != null && savedId in availableIds -> savedId
            "local-player" in availableIds -> "local-player"
            else -> availableIds.firstOrNull()
        }
    }
    val activeProfileId: Flow<String?> = store.data.map { it[ACTIVE_PROFILE] }

    suspend fun selectProfile(profileId: String) {
        checkId(profileId)
        store.edit { it[ACTIVE_PROFILE] = profileId }
    }
}
