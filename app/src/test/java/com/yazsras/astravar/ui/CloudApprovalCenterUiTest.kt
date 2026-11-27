package com.yazsras.astravar.ui

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudApprovalCenterUiTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun globalBellReturnsToTheSameActorAndTabAfterExactOwnerReview() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        val credential = OwnerSyncCredential("fixture", "qa-native-inbox", "https://owner.example.test", now + 3_600_000, "a".repeat(44))
        val cache = CloudSnapshotCache(app, null)
        val digest = "a".repeat(64)
        val decided = AtomicBoolean(false)
        val visited = CopyOnWriteArrayList<String>()
        val header = buildJsonObject {
            put("authority", "cloud"); put("continuityId", credential.campaignId); put("subject", "device:fixture")
            put("epoch", 1); put("revision", 1); put("grantRevision", 2); put("grantExpiresAt", now + 3_600_000)
        }
        fun actor(score: Int) = buildJsonObject {
            put("id", "fixture-actor"); put("kind", "actor"); put("title", "Selected actor")
            put("actorId", "fixture-actor"); put("revision", 1); put("life", "active")
            putJsonObject("data") { put("role", "player"); putJsonObject("abilities") { put("INT", score) } }
        }
        val summary = buildJsonObject {
            put("commandId", "review-one"); put("digest", digest); put("operation", "set_actor_ability")
            put("subject", "sites-mcp"); put("submittedAt", now); put("readiness", "READY")
        }
        val client = CloudCampaignClient(cache, { CloudSession(credential, null) }, OwnerConnectionTransport { _, path, _, _, body ->
            assertNull("The device must only read during review.", body)
            visited += path
            val response = when {
                path.endsWith("/capabilities") -> buildJsonObject { putJsonArray("operations") {} }
                path.contains("/state?") -> buildJsonObject {
                    put("nextAfter", JsonNull); putJsonArray("records") { add(actor(12)) }
                }
                path.endsWith("/review-status") -> buildJsonObject { put("actionableCount", if (decided.get()) 0 else 1) }
                path.contains("/review-inbox?") -> buildJsonObject {
                    val n = if (decided.get()) 0 else 1
                    put("actionableCount", n); put("pendingCount", n); put("nextAfter", JsonNull)
                    putJsonArray("requests") { if (n == 1) add(summary) }
                }
                path.endsWith("/review-inbox/review-one?digest=$digest") -> {
                    val detail = if (decided.get()) buildJsonObject {
                        put("status", "REFUSED"); put("reason", "OWNER_REFUSED")
                        putJsonObject("receipt") { put("transactionId", "txn-one"); put("revision", 1); put("epoch", 1) }
                    } else buildJsonObject {
                        put("status", "PENDING_APPROVAL"); put("reviewReason", "AUTO_SYNC_REVIEW_REQUIRED")
                        putJsonObject("command") { put("expectedRevision", 1); putJsonObject("args") { put("ability", "INT"); put("score", 16) } }
                        putJsonObject("current") { put("fixture-actor", actor(12)) }
                        putJsonObject("plan") {
                            putJsonArray("changes") { addJsonObject { put("before", actor(12)); put("after", actor(16)) } }
                            putJsonObject("result") { put("score", 16) }; putJsonArray("edges") {}; putJsonArray("access") {}
                        }
                    }
                    JsonObject(summary + detail)
                }
                path.endsWith("/sheet/fixture-actor") -> buildJsonObject { putJsonObject("sheet") {} }
                path.contains("/records/") -> buildJsonObject {
                    put("view", path.substringAfter("/records/").substringBefore('?')); put("nextAfter", JsonNull)
                    for (field in listOf("events", "groups", "items", "records")) putJsonArray(field) {}
                }
                else -> error("Unexpected route $path")
            }
            OwnerSyncResponse(200, JsonObject(header + response))
        }, { now }, mediaTransport = { _, _, _, _, _ -> throw OwnerSyncFailure("MEDIA_HTTP_404") })
        val snapshot = runBlocking { client.refresh(true) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { AstravarTheme { CloudWorkspace(client, snapshot) } }
            awaitText("Selected actor")
            val badge = "Notification center. 1 request to review"
            for (tab in listOf("Character", "Inventory", "Connections", "Chronicle")) {
                compose.onNodeWithTag("navigation-$tab").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(badge).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(badge).performClick()
                awaitText("1 ready for approval · 0 require reconciliation")
                if (tab != "Chronicle") {
                    compose.onNodeWithText("Close").performClick()
                    compose.onNodeWithTag("navigation-$tab").assertIsSelected()
                }
            }
            compose.onNodeWithText("Set actor ability").performClick()
            awaitText("Verify")
            compose.onNodeWithText("Current · 12").assertExists()
            compose.onNodeWithText("Proposed · 16").assertExists()
            compose.onNodeWithText("Refuse").performClick()
            val intent = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent?.action)
            assertEquals("https://owner.example.test/owner/ascension/qa-native-inbox#request=review-one&digest=$digest&intent=refuse", intent?.dataString)
            // A separate authenticated browser decision changes the readback.
            decided.set(true)
            activity.pause().resume()
            awaitText("Authoritative revision 1")
            compose.onNodeWithText("Refused").assertExists()
            compose.onNodeWithText("Back to requests").performClick()
            awaitText("No pending owner requests.")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithTag("navigation-Chronicle").assertIsSelected()
            compose.onNodeWithContentDescription("Notification center. 0 requests to review").assertExists()
            compose.onNodeWithText("Tools").performClick()
            compose.onNodeWithText("Pending owner reviews").performClick()
            awaitText("No pending owner requests.")
            compose.onNodeWithText("Close").performClick()
            awaitText("Owner connection")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithTag("navigation-Character").performClick()
            awaitText("Selected actor")
            assertTrue(visited.any { it.contains("/review-inbox/review-one?digest=$digest") })
            assertFalse(visited.any { it.contains("/approvals/") || it.endsWith("/commands") })
        } finally { activity.pause().stop().destroy(); cache.close() }
    }

    private fun awaitText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
}
