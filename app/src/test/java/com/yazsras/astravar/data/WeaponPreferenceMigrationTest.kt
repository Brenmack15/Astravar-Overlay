package com.yazsras.astravar.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class WeaponPreferenceMigrationTest {
    @Test(timeout=60000) fun legacyAcIsRemovedBeforeObservationAndAllWeaponOverlayPreferencesSurvive()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val old=PreferenceDataStoreFactory.create(scope=scope,produceFile={context.preferencesDataStoreFile("legacy-overlay-migration")})
        old.edit {
            it[intPreferencesKey("armor_class")]=23
            it[booleanPreferencesKey("advantage")]=true
            it[intPreferencesKey("portrait_x")]=75
            it[intPreferencesKey("distance")]=90
            it[stringPreferencesKey("elements")]="LIGHTNING"
            it[stringPreferencesKey("mode")]="ELEMENTAL"
            it[stringSetPreferencesKey("selected_payloads")]=setOf("payload")
            it[stringPreferencesKey("image")]="content://test/approved-artwork"
        }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val modernScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val modern=PreferenceDataStoreFactory.create(scope=modernScope,migrations=listOf(RetireTargetAc),produceFile={context.preferencesDataStoreFile("legacy-overlay-migration")})
        val expected=mutablePreferencesOf(
            booleanPreferencesKey("advantage") to true,intPreferencesKey("portrait_x") to 75,
            intPreferencesKey("distance") to 90,stringPreferencesKey("elements") to "LIGHTNING",
            stringPreferencesKey("mode") to "ELEMENTAL",stringSetPreferencesKey("selected_payloads") to setOf("payload"),
            stringPreferencesKey("image") to "content://test/approved-artwork"
        )
        try {assertEquals(expected,modern.data.first());assertFalse(modern.data.first().contains(intPreferencesKey("armor_class")))}
        finally {modernScope.coroutineContext[Job]!!.cancelAndJoin()}
        val reopenedScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val reopened=PreferenceDataStoreFactory.create(scope=reopenedScope,produceFile={context.preferencesDataStoreFile("legacy-overlay-migration")})
        try {assertEquals(expected,reopened.data.first())} finally {reopenedScope.coroutineContext[Job]!!.cancelAndJoin()}
    }
}
