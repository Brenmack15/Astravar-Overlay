package com.yazsras.astravar.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudCampaignClientTest {
    private val origin = "https://owner.example.test"
    private val scope = CloudBinding(origin, "isolated", "device:fixture")
    private val now = 10_000L
    private val credential = OwnerSyncCredential("fixture", "isolated", origin, now + 10_000, "a".repeat(44))
    private fun row(id: String, rev: Int = 1) = buildJsonObject {
        put("id", id); put("kind", "actor"); put("actorId", id); put("title", "Fixture")
        put("data", buildJsonObject { put("base", 10) }); put("life", "active"); put("revision", rev)
    }
    private fun page(rows: List<JsonObject>, after: String? = null, rev: Int = 1, grant: Int = 1) = buildJsonObject {
        put("authority", "cloud"); put("continuityId", scope.continuity); put("subject", scope.subject)
        put("epoch", 1); put("revision", rev); put("grantRevision", grant); put("grantExpiresAt", now + 3000)
        put("records", JsonArray(rows)); put("nextAfter", after?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun cache() = CloudSnapshotCache(ApplicationProvider.getApplicationContext<Context>(), null)

    @Test fun snapshotPagesPublishTogetherAndPermissionRemovalKeepsTheAuthorityFence(): Unit = runBlocking {
        val db = cache()
        var get: (String) -> JsonObject = { path ->
            if (path.contains("after=")) {
                assertTrue(path.endsWith("after=z1&atRevision=1&atEpoch=1&grantRevision=1")); page(listOf(row("z2")))
            } else page(listOf(row("z1")), "z1")
        }
        var status = 200
        val client = CloudCampaignClient(db, { CloudSession(credential, null) },
            OwnerConnectionTransport { _, path, _, _, body -> assertNull(body); OwnerSyncResponse(status, get(path)) }, { now })
        try {
            assertNull(client.workspace())
            assertThrows(OwnerSyncFailure::class.java) { runBlocking { client.refresh() } }
            client.refresh(openExisting = true)
            assertEquals(listOf("z1","z2"), client.records().map { it.cloudText("id") })
            get = { path -> if (path.contains("after=")) throw OwnerSyncFailure("NETWORK_UNAVAILABLE")
                else page(listOf(row("new",2)), "new", rev = 2) }
            assertThrows(OwnerSyncFailure::class.java) { runBlocking { client.refresh() } }
            assertEquals(listOf("z1","z2"), client.records().map { it.cloudText("id") })
            get = { buildJsonObject {} }; status = 403
            assertThrows(OwnerSyncFailure::class.java) { runBlocking { client.refresh() } }
            assertEquals(scope, client.workspace()!!.binding)
            assertFalse(client.workspace()!!.visible)
            assertThrows(OwnerSyncFailure::class.java) { runBlocking { client.records() } }
        } finally { db.close() }
    }

    @Test fun grantChangeInvalidatesPriorDataEvenIfReplacementIsInterrupted(): Unit = runBlocking {
        val db = cache()
        var first = true
        val client = CloudCampaignClient(db, { CloudSession(credential, null) }, OwnerConnectionTransport { _, path, _, _, _ ->
            if (path.contains("after=")) throw OwnerSyncFailure("NETWORK_UNAVAILABLE")
            OwnerSyncResponse(200, if (first) page(listOf(row("former"))) else page(listOf(row("permitted")), "permitted", grant=2))
        }, { now })
        try {
            client.refresh(openExisting = true)
            first = false
            assertThrows(OwnerSyncFailure::class.java) { runBlocking { client.refresh() } }
            assertFalse(client.workspace()!!.visible)
            assertTrue(db.records(scope, now).isEmpty())
        } finally { db.close() }
    }

    @Test fun delayedSnapshotsCannotReplaceNewerRecordsAndExpiredCacheCannotBeOpened() {
        val db = cache()
        try {
            val data = cloudPage(page(listOf(row("new",3)), rev=3), scope, null,null,now)
            db.stage("first",data); db.publish(scope,data.version,now,"first",true)
            val delayed = cloudPage(page(listOf(row("older",2)), rev=2),scope,null,null,now)
            db.stage("late",delayed)
            assertThrows(IllegalArgumentException::class.java) { db.publish(scope,delayed.version,now,"late",false) }
            assertEquals(listOf("new"), db.records(scope, now).map { it.cloudText("id") })
            assertTrue(db.records(scope, now+3000).isEmpty())
            assertTrue(db.records(scope.copy(subject="device:other"),now).isEmpty())
        } finally { db.close() }
    }

    @Test fun privateThumbnailPersistsOnlyUnderTheSameVerifiedGrant(): Unit = runBlocking {
        val db = cache()
        var grant = 1
        val image = byteArrayOf(1, 2, 3)
        val client = CloudCampaignClient(db, { CloudSession(credential, null) },
            OwnerConnectionTransport { _, _, _, _, _ -> OwnerSyncResponse(200, page(listOf(row("actor")), grant = grant)) },
            { now }, { _, target, kind, upload, bytes ->
                assertEquals("actor", target); assertEquals("actor", kind); assertNull(upload); assertNull(bytes); image
            })
        try {
            val selected = client.refresh(openExisting = true)
            assertArrayEquals(image, client.image(selected, "actor", "actor"))
            assertArrayEquals(image, client.cachedImage("actor"))
            grant++
            client.refresh()
            assertNull(client.cachedImage("actor"))
            assertThrows(OwnerSyncFailure::class.java) { runBlocking { client.image(selected, "actor", "actor") } }
        } finally { db.close() }
    }

    @Test fun nativeTransportAllowsOnlyConstructedCloudRoutes() {
        assertTrue(validCloudDevicePath("/v3/continuities/isolated/state?limit=20&after=item:A&atRevision=5&atEpoch=1&grantRevision=2"))
        assertFalse(validCloudDevicePath("/v3/continuities/isolated/../secret"))
        assertFalse(validCloudDevicePath("/v3/continuities/isolated/state?next=https://other.example.test"))
        assertFalse(validCloudDevicePath("/v3/continuities/isolated/grants"))
    }
}
