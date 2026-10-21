package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicit opt-in because this appends ONE disposable fictional event to the existing public Worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SandboxLiveSyncTest {
    @Test fun actualKotlinHttpsClientPersistsCommandAndVerifiesLiveReceiptAndReadback() = runBlocking {
        assumeTrue("Live public sandbox acceptance is debug-only", BuildConfig.DEBUG)
        assumeTrue("Live public sandbox tests require ASTRAVAR_LIVE_SANDBOX=true",
            System.getenv("ASTRAVAR_LIVE_SANDBOX") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sandbox-live-http-test.db"
        val origin = PublicSandbox.DEFAULT_ORIGIN
        val transport = SandboxHttpTransport()
        var database = Room.databaseBuilder(context, SandboxSyncDb::class.java, name).build()
        try {
            var repository = SandboxSyncRepository(database, transport)
            repository.configure(origin)
            repository.refresh()
            // If an earlier run lost its response, recover its original UUID before preparing anything.
            if (repository.commands(origin).first().any { it.status == "PENDING" }) repository.retryPending()
            val before = repository.endpoint(origin).first()!!
            val command = repository.queueSynthetic("new_fact")
            assertEquals(before.remoteRevision, command.expectedRevision)
            assertEquals("PENDING", repository.commands(origin).first().first { it.commandId == command.commandId }.status)
            database.close()
            database = Room.databaseBuilder(context, SandboxSyncDb::class.java, name).build()
            repository = SandboxSyncRepository(database, transport)
            assertEquals(command.body, repository.commands(origin).first().first { it.commandId == command.commandId }.body)
            repository.retryPending()
            val saved = repository.commands(origin).first().first { it.commandId == command.commandId }
            assertEquals("COMMITTED", saved.status)
            val receipt = JSONObject(saved.receiptJson!!)
            assertEquals(command.fingerprint, receipt.getString("payloadFingerprint"))
            val event = repository.events(origin).first().single { it.commandId == command.commandId }
            assertEquals(receipt.getString("eventId"), event.eventId)
            // Exercise actual HTTP exact-retry semantics even after repository has marked it committed.
            val retry = transport.request(origin, PublicSandbox.BASE + "/milestones", command.body)
            assertEquals(200, retry.status)
            assertEquals(receipt.toString(), retry.json.toString())
            val fetched = transport.request(origin,
                "/api/v1/campaigns/${PublicSandbox.CAMPAIGN}/commands/${command.commandId}/receipt", null)
            assertEquals(200, fetched.status)
            assertEquals(receipt.toString(), fetched.json.toString())
            repository.refresh()
            assertEquals(1, repository.events(origin).first().count { it.commandId == command.commandId })
            println("LIVE_KOTLIN_SANDBOX_VERIFIED origin=$origin commandId=${command.commandId} eventId=${event.eventId} " +
                "priorRevision=${receipt.getLong("priorRevision")} resultingRevision=${receipt.getLong("resultingRevision")} " +
                "fingerprint=${command.fingerprint}; NOT_PHYSICAL_ANDROID; SYNTHETIC_ONLY")
        } finally {
            // The test database intentionally remains available for recovery after an interrupted run.
            database.close()
        }
    }
}
