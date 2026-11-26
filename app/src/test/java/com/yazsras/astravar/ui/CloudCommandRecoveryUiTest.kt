package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
class CloudCommandRecoveryUiTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun reopeningRestAfterLostResponseRetriesTheOriginalFacesAndExpenditure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fixture-outbox-${UUID.randomUUID()}.db"
        val expiry = System.currentTimeMillis() + 3_600_000L
        val vault = OwnerSyncCredential("device", "campaign", "https://fixture.invalid", expiry, "f".repeat(64))
        val commands = mutableListOf<String>()
        var revision = 0
        var askedForReceipt = false
        var receipt: JsonObject? = null
        fun profile() = buildJsonObject {
            put("currentHp", if (revision == 0) 9 else 14); put("maxHp", 20); put("rulesProfile", "legacy_2014")
        }
        fun record(id: String, kind: String, data: JsonObject) = buildJsonObject {
            put("id", id); put("kind", kind); put("actorId", "actor"); put("title", if (id == "hd") "Fixture die" else "Fixture actor")
            put("data", data); put("revision", revision + 1); put("life", "active")
        }
        fun page() = buildJsonObject {
            put("authority", "cloud"); put("continuityId", "campaign"); put("subject", "device:device")
            put("epoch", 1); put("revision", revision + 1); put("grantRevision", 1); put("grantExpiresAt", expiry)
            putJsonArray("records") {
                add(record("actor", "actor", profile())); add(record("hd", "resource_pool", buildJsonObject {
                    put("type", "hit_die"); put("dieSides", 8); put("current", if (revision == 0) 2 else 1)
                    put("maximum", 2); put("recovery", "edition_hit_dice")
                }))
            }; put("nextAfter", JsonNull)
        }
        val transport = OwnerConnectionTransport { _, path, _, _, body ->
            when {
                path.endsWith("/commands") -> {
                    commands += requireNotNull(body)
                    if (commands.size == 1) throw OwnerSyncFailure("NETWORK_UNAVAILABLE")
                    val command = Json.parseToJsonElement(body).jsonObject
                    assertEquals(commands.first(), body)
                    assertEquals(0, revision++)
                    val id = command.getValue("commandId").jsonPrimitive.content
                    receipt = buildJsonObject {
                        put("epoch", 1); put("revision", revision + 1); put("commandId", id); put("status", "COMMITTED")
                        put("digest", OwnerSyncProtocol.hash(OwnerSyncProtocol.canonical(command)))
                        putJsonObject("receipt") {
                            put("epoch", 1); put("revision", revision + 1); put("commandId", id); put("transactionId", "one-rest")
                            put("status", "COMMITTED"); putJsonArray("changes") {}
                            putJsonObject("outcome") { put("kind", "short_rest"); put("actorId", "actor"); put("actualHealing", 5) }
                        }
                    }; OwnerSyncResponse(200, requireNotNull(receipt))
                }
                "/receipts/" in path -> {
                    askedForReceipt = true
                    receipt?.let { OwnerSyncResponse(200, it) } ?: OwnerSyncResponse(404, buildJsonObject {
                        putJsonObject("error") { put("code", "COMMAND_UNKNOWN") }
                    })
                }
                path.endsWith("/sheet/actor") -> OwnerSyncResponse(200, buildJsonObject {
                    put("epoch", 1); put("revision", revision + 1); putJsonObject("sheet") {
                        put("currentHp", 9); put("maxHp", 20); putJsonObject("abilities") { putJsonObject("CON") { put("modifier", 0) } }
                    }
                })
                else -> OwnerSyncResponse(200, page())
            }
        }
        var cache = CloudSnapshotCache(context, name)
        var client by mutableStateOf(CloudCampaignClient(cache, { CloudSession(vault, null) }, transport))
        val snapshot = runBlocking { client.refresh(openExisting = true) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var shown by mutableStateOf(true)
        try {
            activity.get().setContent { if (shown) AstravarTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                CloudSpellRest("Rest", "actor", client, snapshot, true) { _, _ -> }
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("+").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("+").performScrollTo().performClick()
            compose.onNodeWithText("Actual d8 faces (1)").performScrollTo().performTextInput("5")
            compose.onNodeWithText("Complete short rest").performScrollTo().performClick()
            compose.onNodeWithText("Complete rest").performClick()
            fun retry() = compose.onNodeWithText("Retry original Complete rest")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry original Complete rest").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { shown = false }; compose.waitForIdle()
            cache.close()
            cache = CloudSnapshotCache(context, name)
            val restarted = CloudCampaignClient(cache, { CloudSession(vault, null) }, transport)
            assertNotNull(runBlocking { restarted.uncertain("complete_rest", "actor") })
            compose.runOnIdle { client = restarted; shown = true }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry original Complete rest").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Complete short rest").assertIsNotEnabled()
            assertEquals(1, commands.size)
            retry().performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Rest result").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(askedForReceipt); assertEquals(2, commands.size); assertEquals(1, revision)
            val original = Json.parseToJsonElement(commands.first()).jsonObject.getValue("args").jsonObject
            val die = original.getValue("hitDice").jsonArray.single().jsonObject
            assertEquals("player_physical", die.getValue("origin").jsonPrimitive.content)
            assertEquals(5, die.getValue("faces").jsonArray.single().jsonPrimitive.int)
            assertNull(runBlocking { client.uncertain() })
            assertEquals(1, runBlocking { client.record("hd") }!!.getValue("data").jsonObject.getValue("current").jsonPrimitive.int)
        } finally { activity.pause().stop().destroy(); cache.close(); context.deleteDatabase(name) }
    }
}
