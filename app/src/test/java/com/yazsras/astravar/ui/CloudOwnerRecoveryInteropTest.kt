package com.yazsras.astravar.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.room.MultiInstanceInvalidationService
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
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
import java.io.ByteArrayOutputStream

@OptIn(androidx.room.ExperimentalRoomApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w440dp-h1000dp", application = AstravarApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudOwnerRecoveryInteropTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(label: String) {
        val match = hasText(label) and hasClickAction() and isEnabled()
        waitFor(match)
        val node = compose.onNode(match)
        try { node.performScrollTo() } catch (_: AssertionError) { /* A fixed header has no scroll parent. */ }
        node.performClick()
    }

    private fun envelope(id: String, title: String): Pair<String, String> {
        val body = "Selected private synthetic reference $id"
        val reference = CampaignArchiveRecord(id, title, "Correspondence", body,
            sourceUrl = "https://example.invalid/$id", sourceRevision = "source-version-4",
            contentSha256 = campaignHash(body), visibility = "player")
        val payload = CampaignArchivePayload("local-fixture", title, 1, "https://example.invalid/archive",
            "archive-version-2", listOf(reference))
        val text = campaignJson.encodeToString(payload)
        val sha = campaignHash(text)
        return sha to campaignJson.encodeToString(CampaignArchiveEnvelope("astravar-campaign-archive",1,sha,text))
    }

    private fun deliver(activity: ComponentActivity, expected: String, uri: Uri?): Intent {
        val request = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(expected, request.intent.action)
        if (expected == Intent.ACTION_OPEN_DOCUMENT) {
            assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(request.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty().contains("application/json"))
        }
        compose.runOnIdle {
            assertTrue(activity.activityResultRegistry.dispatchResult(request.requestCode,
                if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, uri?.let { Intent().setData(it) }))
        }
        return request.intent
    }

    @Test fun fourTabsPreserveAccessibleHeaderOrderAndSelectedFileIsRecoveredOnlyOnTheDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as AstravarApp
        val serviceIntent = Intent(app, MultiInstanceInvalidationService::class.java)
        val service = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(serviceIntent,
            ComponentName(app, MultiInstanceInvalidationService::class.java), service.get().onBind(serviceIntent))
        val resolver = shadowOf(context.contentResolver)
        val (chosenHash, chosen) = envelope("kept", "Chosen correspondence")
        val (otherHash, other) = envelope("other", "Other correspondence")
        val chosenUri = Uri.parse("content://fixture-recovery/document/chosen.json")
        val otherUri = Uri.parse("content://fixture-recovery/document/other.json")
        val badUri = Uri.parse("content://fixture-recovery/document/invalid.json")
        resolver.registerInputStreamSupplier(chosenUri) { chosen.byteInputStream() }
        resolver.registerInputStreamSupplier(otherUri) { other.byteInputStream() }
        resolver.registerInputStreamSupplier(badUri) { chosen.replace(chosenHash,"0".repeat(64)).byteInputStream() }
        val output = ByteArrayOutputStream()
        val saveUri = Uri.parse("content://fixture-recovery/document/recovery.json")
        resolver.registerOutputStream(saveUri, output)
        resolver.registerInputStreamSupplier(saveUri) { output.toByteArray().inputStream() }

        AscensionWorkerFixture.open(context).use { fixture ->
            val snapshot = runBlocking { fixture.refresh() }
            val cloud = fixture.dm("get_cloud_state", buildJsonObject { put("limit", 100) })
            val db = CampaignLedgerDb.open(context)
            val dao = db.archiveDao()
            val before = runBlocking { dao.allReferences() }
            val receiptsBefore = runBlocking { dao.allReceipts() }
            var activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            fun mount() { activity.get().setContent { AstravarTheme { CloudWorkspace(fixture.client, snapshot) } } }
            fun openImport() {
                click("Tools"); click("Local backup and recovery"); click("Campaign import")
                waitFor(hasText("Choose campaign archive"))
            }
            fun select(uri: Uri?) {
                click("Choose campaign archive"); deliver(activity.get(), Intent.ACTION_OPEN_DOCUMENT, uri)
            }
            try {
                mount()
                for (tab in rebuildDestinations) {
                    compose.onNodeWithTag("navigation-$tab").performClick().assertIsSelected()
                    val brand = compose.onNodeWithText("ASTRAVAR").getUnclippedBoundsInRoot()
                    val bell = compose.onNode(hasContentDescription("Notification center.",substring=true) and hasClickAction())
                    val find = compose.onNode(hasText("Find") and hasClickAction())
                    val tools = compose.onNode(hasText("Tools") and hasClickAction())
                    val b = bell.getUnclippedBoundsInRoot(); val f = find.getUnclippedBoundsInRoot(); val t = tools.getUnclippedBoundsInRoot()
                    assertTrue("Brand, notification center, Find, Tools", brand.right <= b.left && b.right <= f.left && f.right <= t.left)
                    for (bounds in listOf(b,f,t)) assertTrue("Accessible touch target", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
                    click("Find"); compose.onNode(hasSetTextAction()).assertIsDisplayed(); click("Find")
                    click("Tools"); waitFor(hasText("Owner connection")); click("Close")
                    compose.onNodeWithTag("navigation-$tab").assertIsSelected()
                }
                fixture.deviceOffline = true
                click("Tools"); click("Check connection now")
                waitFor(hasText("Cloud offline or unreachable"))
                click("Local backup and recovery"); click("Campaign import")
                select(null)
                compose.onAllNodesWithText("Confirm local import").assertCountEquals(0)
                select(badUri)
                waitFor(hasText("Confirm local import"))
                compose.onNodeWithText("Confirm local import").assertIsNotEnabled()
                // The review dialog is on top of the local recovery route.
                compose.onAllNodes(hasText("Close") and hasClickAction()).onLast().performClick()
                select(otherUri)
                waitFor(hasText("Other correspondence"))
                compose.onNodeWithText("Package SHA-256\n$otherHash").assertExists()
                compose.onAllNodes(hasText("Close") and hasClickAction()).onLast().performClick()
                select(chosenUri)
                waitFor(hasText("Chosen correspondence"))
                compose.onNodeWithText("Package SHA-256\n$chosenHash").assertExists()
                compose.onNodeWithText("Confirm local import").assertIsNotEnabled()
                assertEquals(before, runBlocking { dao.allReferences() })
                assertEquals(receiptsBefore, runBlocking { dao.allReceipts() })
                click("Save verified pre-import backup")
                val create = deliver(activity.get(), Intent.ACTION_CREATE_DOCUMENT, saveUri)
                assertEquals("astravar-pre-import-${chosenHash.take(12)}.json",create.getStringExtra(Intent.EXTRA_TITLE))
                waitFor(hasText("Backup verified for this review."))
                val backup = runBlocking { requireNotNull(dao.backup(chosenHash)) }
                assertEquals(backup.snapshotJson, output.toString("UTF-8"))
                assertEquals(backup.snapshotSha256, campaignHash(output.toString("UTF-8")))
                compose.onNodeWithText("Confirm local import").assertIsNotEnabled()
                click("I reviewed the archive and want to add approved records locally.")
                click("Confirm local import")
                waitFor(hasText("Import saved. Independent database readback passed."))
                val applied = runBlocking { requireNotNull(dao.receipt(chosenHash)) }
                assertEquals("APPLIED",applied.state)
                assertEquals("source-version-4", runBlocking { dao.allReferences().single { it.externalId == "kept" }.sourceRevision })
                assertNull(runBlocking { dao.receipt(otherHash) })
                activity.pause().stop().destroy()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                mount(); openImport()
                click("Verify saved import")
                waitFor(hasText("Database readback matches every imported identity, relationship and content hash."))
                select(chosenUri)
                waitFor(hasText("Already imported on this device.", substring=true))
                compose.onNodeWithText("Confirm local import").assertIsNotEnabled()
                compose.onAllNodes(hasText("Close") and hasClickAction()).onLast().performClick()
                click("Review rollback")
                compose.onNodeWithText("Package $chosenHash").assertExists()
                compose.onNodeWithText("Confirm rollback").assertIsNotEnabled()
                click("I understand this restores the verified pre-import campaign backup.")
                click("Confirm rollback")
                compose.waitUntil(15_000) { runBlocking { dao.receipt("rollback:$chosenHash") != null } }
                assertEquals(before,runBlocking { dao.allReferences() })
                assertEquals(applied,runBlocking { dao.receipt(chosenHash) })
                assertEquals("ROLLED_BACK",runBlocking { dao.receipt("rollback:$chosenHash")?.state })
                assertEquals(receiptsBefore.size + 2, runBlocking { dao.allReceipts().size })
                assertEquals(cloud,fixture.dm("get_cloud_state",buildJsonObject { put("limit",100) }))
                fixture.deviceOffline = false
                assertEquals(snapshot.version,runBlocking { fixture.refresh().version })
            } finally { activity.pause().stop().destroy();db.close();service.destroy() }
        }
    }
}
