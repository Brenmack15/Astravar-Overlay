package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudMediaLifecycleInteropTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.ids(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun textNode(label: String) = compose.onNodeWithText(label)
    private fun awaitNode(matcher: SemanticsMatcher) {
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(label: String) {
        awaitNode(hasText(label) and isEnabled())
        val node = textNode(label)
        try { node.performScrollTo() } catch (_: AssertionError) { /* Fixed controls need no scrolling. */ }
        node.performClick()
    }
    private fun select(id: String) {
        val tag = "archive-record-$id"
        awaitNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        click("Select record")
    }

    @Test fun creationReviewedReplacementRemovalAndTrueDeleteKeepOnlySharedBytesAcrossRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AscensionWorkerFixture.open(context, lifecycleMedia = true).use { f ->
            fun snapshot() = runBlocking { requireNotNull(f.client.workspace()) }
            fun association(target: String) = runBlocking { f.client.projection(snapshot(), "media/primary/$target/association") }
            fun image(target: String) = runBlocking { f.client.image(snapshot(), target, "item_definition") }
            fun cached(target: String) = runBlocking { f.client.cachedImage(target) }
            fun proof(upload: String) = Base64.getDecoder().decode(f.signal("ownerPreview", json("""{"uploadId":"$upload"}""")).cloudText("bytes"))
            fun decision(pending: JsonObject) = f.signal("ownerVerify", buildJsonObject {
                put("commandId", pending.getValue("commandId")); put("digest", pending.getValue("digest"))
            }).also { assertEquals("COMMITTED", it.cloudText("status")) }
            fun verify(pending: JsonObject) = runBlocking { f.client.verify(pending.cloudText("commandId")) }
            fun args(target: String, upload: String, hash: String) = json(
                """{"targetId":"$target","targetKind":"item_definition","uploadId":"$upload-upload","assetId":"$upload-asset","sha256":"$hash"}""")
            fun define(id: String, title: String) = runBlocking {
                f.client.submit("define_item_definition", json("""{"definitionId":"$id","name":"$title","category":"tool","description":"Synthetic specimen","rarity":null,"stackable":false,"wearable":false,"handheld":true,"attunement":"none","source":"Isolated fixture"}"""), snapshot(), "create-$id")
            }.also { assertEquals("COMMITTED", it.cloudText("status")) }
            fun inbox() = runBlocking { f.client.projection(snapshot(), "review-inbox?limit=20") }
                .getValue("requests").jsonArray.single().jsonObject
            fun stage(creation: String, data: ByteArray) = f.dm("stage_cloud_image", buildJsonObject {
                put("targetId", "disposable"); put("targetKind", "item_definition"); put("uploadId", "first-upload")
                put("creationCommandId", creation); put("sha256", cloudDigest(data)); put("bytesBase64", Base64.getEncoder().encodeToString(data))
            })
            fun sample(value: Int) = Base64.getDecoder().decode(f.signal("sample", json("""{"shade":$value}""")).cloudText("bytes"))
            runBlocking { f.refresh() }
            val made = define("disposable", "Disposable optics")
            define("survivor", "Surviving optics")
            val detachable = define("detach-target", "Separate optics")
            val first = sample(20)
            val second = sample(180)
            val wrong = assertThrows(IllegalStateException::class.java) { stage("create-survivor", first) }
            assertTrue(wrong.message.orEmpty().contains("CREATED_IMAGE_TARGET_UNVERIFIED"))
            assertTrue(f.signal("mediaProof").ids("staged").isEmpty())
            val source = stage("create-disposable", first)
            assertEquals("CANDIDATE_READY", source.cloudText("status"))
            assertEquals(made.obj("receipt").cloudText("transactionId"), source.obj("creation").cloudText("transactionId"))
            assertEquals(source, stage("create-disposable", first))
            val set = f.command("assign_primary_image", args("disposable", "first", cloudDigest(first)), "first-art")
            assertEquals("PENDING_APPROVAL", set.cloudText("status"))
            assertArrayEquals(first, proof("first-upload"))
            decision(set)
            verify(set)
            f.signal("sharedFixture")
            val original = image("disposable")
            assertEquals(association("disposable").cloudText("thumbnailSha256"), cloudDigest(original))
            for (target in listOf("survivor", "detach-target")) assertArrayEquals(original, image(target))

            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val inArchive = mutableStateOf(false)
            val mounted = mutableStateOf(snapshot())
            val generation = mutableIntStateOf(0)
            try {
                activity.get().setContent {
                    AstravarTheme {
                        key(generation.intValue) {
                            if (inArchive.value) CloudWorkspace(f.client, mounted.value)
                            else CreatedEntityArtwork(f.client, mounted.value, true, detachable.obj("receipt"))
                        }
                    }
                }
                awaitNode(hasTestTag("primary-image-detach-target"))
                compose.onNodeWithTag("primary-image-detach-target").performClick()
                click("Remove image")
                click("Keep image")
                assertEquals(0, runBlocking { f.client.reviewCount() })
                click("Remove image")
                click("Request removal")
                awaitNode(hasText("Image change submitted for owner review."))
                val refused = inbox()
                val refusal = f.signal("ownerRefuse", buildJsonObject {
                    put("commandId", refused.getValue("commandId")); put("digest", refused.getValue("digest"))
                })
                assertEquals("REFUSED", refusal.cloudText("status"))
                click("Check image decision")
                awaitNode(hasText("Image change · Refused."))
                assertArrayEquals(original, image("detach-target"))
                click("Remove image")
                click("Request removal")
                awaitNode(hasText("Image change submitted for owner review."))
                val detached = inbox()
                assertNotEquals(refused.cloudText("commandId"), detached.cloudText("commandId"))
                assertEquals("remove_primary_image", detached.cloudText("operation"))
                assertArrayEquals(original, image("detach-target"))
                decision(detached)
                val absent = assertThrows(OwnerSyncFailure::class.java) { image("detach-target") }
                assertEquals("MEDIA_HTTP_404", absent.code)
                assertNull(cached("detach-target"))
                assertArrayEquals(original, cached("survivor"))
                verify(detached)
                assertEquals(JsonNull, association("detach-target")["assetId"])
                assertEquals("first-asset", association("survivor").cloudText("assetId"))
                val afterRemoval = snapshot()
                val upload = runBlocking { f.client.image(afterRemoval, "disposable", "item_definition", "second-upload", second) }
                assertEquals("CANDIDATE_READY", json(upload.decodeToString()).cloudText("status"))
                val replacement = runBlocking { f.client.submit("assign_primary_image", args("disposable", "second", cloudDigest(second)), afterRemoval,"second-art") }
                assertEquals("PENDING_APPROVAL", replacement.cloudText("status"))
                assertArrayEquals(original, image("disposable"))
                assertArrayEquals(second, proof("second-upload"))
                decision(replacement)
                val restoredImage = verify(replacement)
                assertEquals(restoredImage, f.receipt("second-art"))
                assertNull(cached("disposable"))
                val changed = image("disposable")
                assertNotEquals(cloudDigest(original), cloudDigest(changed))
                assertEquals(cloudDigest(changed), association("disposable").cloudText("thumbnailSha256"))
                assertArrayEquals(original, image("survivor"))

                fun mountArchive() {
                    val fresh = snapshot()
                    compose.runOnIdle { mounted.value = fresh; inArchive.value = true; generation.intValue++ }
                    awaitNode(hasText("Cloud · revision "+fresh.version.revision))
                    click("Tools")
                    click("Archive manager")
                    awaitNode(hasText("Archived"))
                }
                fun submitLifecycle(label: String, op: String): JsonObject {
                    click(label)
                    click("Submit")
                    awaitNode(hasText("Transaction result"))
                    val pending = inbox()
                    assertEquals(op, pending.cloudText("operation"))
                    assertEquals("PENDING_APPROVAL", verify(pending).cloudText("status"))
                    val accepted = decision(pending)
                    val reviewed = verify(pending)
                    assertEquals(accepted, reviewed)
                    assertEquals(reviewed, f.receipt(pending.cloudText("commandId")))
                    return reviewed
                }
                val content = requireNotNull(f.record("disposable")).obj("data")
                mountArchive()
                click("Active"); select("disposable")
                submitLifecycle("Review archive", "archive_records")
                assertNull(runBlocking { f.client.record("disposable") })
                assertEquals(content, runBlocking { f.client.projection(snapshot(), "archive?id=disposable") }.obj("record").obj("data"))
                mountArchive()
                select("disposable")
                submitLifecycle("Review restore", "restore_records")
                assertEquals(content, requireNotNull(f.record("disposable")).obj("data"))
                assertArrayEquals(changed, image("disposable"))
                mountArchive()
                click("Active"); select("disposable"); select("second-asset")
                click("Permanent delete")
                awaitNode(hasText("Review deletion request"))
                textNode("Review deletion request").assertIsNotEnabled()
                click("I understand that existing backups and exported copies may retain this content.")
                val deleted = submitLifecycle("Review deletion request", "permanently_delete_records").obj("receipt")
                assertEquals("COMPLETE", deleted.obj("assetCleanup").cloudText("status"))
                assertEquals(setOf("disposable", "second-asset"), deleted.ids("deletedIds").toSet())
                assertNull(cached("disposable"))
                assertNull(f.record("disposable"))
                assertNull(f.record("second-asset"))
                val retained = f.signal("mediaProof")
                assertEquals(2, retained.getValue("objects").jsonPrimitive.int)
                assertEquals(setOf("detach-target", "first-asset", "survivor"), retained.ids("active").toSet())
                assertEquals(setOf("disposable", "second-asset"), retained.ids("tombstones").toSet())
                assertEquals(listOf("first-upload"),retained.ids("staged"))
                assertArrayEquals(original, image("survivor"))
            } finally {
                activity.pause().stop().destroy()
            }
            f.reopenCache()
            assertNull(cached("disposable"))
            assertNull(cached("detach-target"))
            assertArrayEquals(original, cached("survivor"))
            assertNull(runBlocking { f.client.record("disposable") })
        }
    }
}
