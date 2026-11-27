package com.yazsras.astravar.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CloudImagePickerInteropTest {
    @get:Rule val compose = createAstravarComposeRule()

    private fun json(input: String) = Json.parseToJsonElement(input).jsonObject
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(matcher: SemanticsMatcher) {
        val ready = matcher and isEnabled()
        waitFor(ready)
        val node = compose.onNode(ready)
        try { node.performScrollTo() } catch (_: AssertionError) { /* The toolbar is fixed. */ }
        node.performClick()
    }
    private fun clickText(label: String) = click(hasText(label) and hasClickAction())

    private data class LocalImage(val uri: Uri, val expected: ByteArray)
    private fun makeSelection(context: Context, name: String, color: Int, format: Bitmap.CompressFormat): LocalImage {
        val image = Bitmap.createBitmap(18, 12, Bitmap.Config.ARGB_8888)
        val input = try {
            image.eraseColor(color)
            ByteArrayOutputStream().use { out ->
                assertTrue(image.compress(format, 91, out))
                out.toByteArray()
            }
        } finally { image.recycle() }
        val uri = Uri.parse("content://synthetic-image-provider/document/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { input.inputStream() }
        val expected = prepareCloudImage(context, uri)
        if (format == Bitmap.CompressFormat.JPEG) assertFalse(input.contentEquals(expected))
        return LocalImage(uri, expected)
    }
    private fun seed(f: AscensionWorkerFixture): List<String> {
        fun create(op: String, raw: String) {
            val result = f.command(op, json(raw))
            assertEquals(op, "COMMITTED", result.cloudText("status"))
        }
        for ((id,role) in listOf("hero" to "player","companion" to "companion"))
            create("define_actor","""{"actorId":"$id","name":"Subject $id","role":"$role","level":4,"rulesProfile":"legacy_2014","abilities":{"STR":10,"DEX":12,"CON":11,"INT":13,"WIS":11,"CHA":10}}""")
        create("define_form","""{"actorId":"hero","formId":"alternate","name":"Alternate anatomy","source":"Fixture","size":"large","grounded":true,"timing":"other","activationSeconds":0}""")
        for (category in listOf("person","being","faction","place"))
            create("define_connection","""{"entityId":"world-$category","name":"Known $category","category":"$category","actorId":null,"knownDescription":"Fixture world record","source":"Fixture"}""")
        create("define_item_definition","""{"definitionId":"catalog","name":"Inspection lens","category":"tool","description":"Fixture","rarity":null,"stackable":false,"wearable":false,"handheld":true,"attunement":"none","source":"Fixture"}""")
        create("acquire_item","""{"itemId":"holding","definitionId":"catalog","ownerId":"hero","quantity":1,"source":"Fixture"}""")
        create("define_attack","""{"actorId":"hero","attackId":"strike","name":"Practice strike","source":"Fixture","resolution":"attack","timing":"attack","delivery":"melee","fixedHit":2,"components":[]}""")
        create("propose_invention","""{"projectId":"project","title":"New instrument","actorId":"hero","intent":"Make an instrument.","requirements":[],"researchIds":[],"source":"Fixture"}""")
        create("record_blueprint_version","""{"blueprintId":"design","projectId":"project","title":"First drawing","specification":"Fixture schematic","supersedesId":null,"source":"Fixture"}""")
        return listOf("hero","companion","alternate","world-person","world-being","world-faction","world-place","catalog","holding","strike","project","design")
    }

    @Test fun normalDocumentPickerCancelsPreviewsAndSavesExactBytesOnEachCanonicalImageKind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = makeSelection(context, "one", Color.rgb(182, 38, 72), Bitmap.CompressFormat.JPEG)
        val replacement = makeSelection(context, "two", Color.argb(140, 18, 136, 107), Bitmap.CompressFormat.PNG)
        assertNotEquals(cloudDigest(first.expected), cloudDigest(replacement.expected))
        AscensionWorkerFixture.open(context, lifecycleMedia=true).use { f ->
            val targets = seed(f)
            fun snapshot() = runBlocking { requireNotNull(f.client.workspace()) }
            val mounted = mutableStateOf(runBlocking { f.refresh() })
            val generation = mutableIntStateOf(0)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { AstravarTheme { key(generation.intValue) { CloudWorkspace(f.client, mounted.value) } } }
                fun findImage(id: String, label: String, kind: String) {
                    val latest = snapshot()
                    compose.runOnIdle { mounted.value=latest;generation.intValue++ }
                    waitFor(hasText("Cloud · revision "+latest.version.revision))
                    clickText("Find")
                    val search = compose.onNode(hasSetTextAction())
                    search.performTextReplacement(label)
                    click(hasText(label) and hasText(cloudLabel(kind)) and hasClickAction())
                    click(hasTestTag("primary-image-$id"))
                }
                fun sendPickerResult(label: String, uri: Uri?) {
                    clickText(label)
                    val launched = requireNotNull(shadowOf(activity.get()).nextStartedActivityForResult)
                    val intent = launched.intent
                    assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                    assertTrue(intent.type == "image/*" || intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.contentEquals(arrayOf("image/*")) == true)
                    compose.runOnIdle {
                        assertTrue(activity.get().activityResultRegistry.dispatchResult(launched.requestCode,
                            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, uri?.let { Intent().setData(it) }))
                    }
                }
                fun shownBytes(id: String, kind: String) = runBlocking { f.client.image(snapshot(),id,kind) }
                fun associate(id: String, selection: LocalImage, replacing: Boolean): String {
                    val before = requireNotNull(f.record(id))
                    val kind = before.cloudText("kind"); val title = before.cloudText("title")
                    val start = snapshot()
                    val label = if(replacing) "Replace image" else "Choose image"
                    val prior = f.dm("get_cloud_primary_image",json("""{"id":"$id"}"""))
                    val priorCounts = f.signal("mediaProof").getValue("staged").jsonArray.size
                    findImage(id,title,kind)
                    sendPickerResult(label,null)
                    compose.onNodeWithText("Save image").assertIsNotEnabled()
                    assertEquals(priorCounts, f.signal("mediaProof").getValue("staged").jsonArray.size)
                    if (replacing) {
                        sendPickerResult(label, selection.uri)
                        waitFor(hasText("Save image") and isEnabled())
                        clickText("Cancel")
                        assertEquals(prior, f.dm("get_cloud_primary_image",json("""{"id":"$id"}""")))
                        assertEquals(priorCounts, f.signal("mediaProof").getValue("staged").jsonArray.size)
                        assertEquals(prior.cloudText("thumbnailSha256"), cloudDigest(shownBytes(id,kind)))
                        click(hasTestTag("primary-image-$id"))
                    }
                    sendPickerResult(label,selection.uri)
                    waitFor(hasText("Save image") and isEnabled())
                    compose.onNodeWithContentDescription("Image preview for $title").assertExists()
                    assertEquals(prior, f.dm("get_cloud_primary_image",json("""{"id":"$id"}""")))
                    clickText("Save image")
                    waitFor(hasText("Image change submitted for owner review."))
                    val inbox = runBlocking { f.client.projection(start,"review-inbox?limit=20") }
                    val pending = inbox.getValue("requests").jsonArray.single().jsonObject
                    assertEquals("assign_primary_image", pending.cloudText("operation"))
                    val command = pending.cloudText("commandId")
                    val digest = pending.cloudText("digest")
                    val detail = runBlocking { f.client.projection(start,approvalViewSuffix(command,digest)) }
                    val args = detail.obj("command").obj("args")
                    assertEquals(id, args.cloudText("targetId")); assertEquals(kind,args.cloudText("targetKind"))
                    assertEquals(cloudDigest(selection.expected),args.cloudText("sha256"))
                    assertEquals(prior, f.dm("get_cloud_primary_image",json("""{"id":"$id"}""")))
                    val ownerPreview = f.signal("ownerPreview",buildJsonObject { put("uploadId",args.getValue("uploadId")) })
                    assertEquals(200, ownerPreview.getValue("status").jsonPrimitive.int)
                    assertArrayEquals(selection.expected, Base64.getDecoder().decode(ownerPreview.cloudText("bytes")))
                    val confirmed = f.signal("ownerVerify",buildJsonObject{put("commandId",command);put("digest",digest)})
                    assertEquals("COMMITTED", confirmed.cloudText("status"))
                    assertEquals(confirmed, f.receipt(command))
                    assertEquals(confirmed, runBlocking { f.client.verify(command) })
                    assertEquals(before, f.record(id))
                    val link = f.dm("get_cloud_primary_image",json("""{"id":"$id"}"""))
                    assertEquals(id,link.cloudText("targetId"))
                    assertEquals(kind,link.cloudText("targetKind"))
                    assertEquals(cloudDigest(selection.expected), link.cloudText("sha256"))
                    assertEquals(args.cloudText("assetId"),link.cloudText("assetId"))
                    val thumbnail = shownBytes(id,kind)
                    assertEquals(cloudDigest(thumbnail),link.cloudText("thumbnailSha256"))
                    val original = requireNotNull(BitmapFactory.decodeByteArray(selection.expected,0,selection.expected.size))
                    val rendered = requireNotNull(BitmapFactory.decodeByteArray(thumbnail,0,thumbnail.size))
                    try {
                        assertEquals(original.width,rendered.width);assertEquals(original.height,rendered.height)
                        assertEquals(original.getPixel(4,4),rendered.getPixel(4,4))
                        assertEquals(if (replacing) 140 else 255, Color.alpha(rendered.getPixel(4,4)))
                    } finally { original.recycle();rendered.recycle() }
                    return link.cloudText("assetId")
                }
                val capabilities = runBlocking { f.client.capabilities() }
                val supportedKinds = capabilities.getValue("kinds").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertTrue(supportedKinds.containsAll(cloudMediaTargets))
                val assign = capabilities.getValue("operations").jsonArray.map { it.jsonObject }.single { it.cloudText("name") == "assign_primary_image" }
                val imageKinds = assign.obj("schema").obj("properties").obj("targetKind").getValue("enum").jsonArray
                assertEquals(cloudMediaTargets, imageKinds.map { it.jsonPrimitive.content }.toSet())
                assertEquals(cloudMediaTargets, targets.map { requireNotNull(f.record(it)).cloudText("kind") }.toSet())
                val originals = targets.associateWith { associate(it,first,false) }
                assertEquals(targets.size,originals.values.toSet().size)
                val newAsset = associate("hero",replacement,true)
                assertNotEquals(originals.getValue("hero"),newAsset)
                for (id in targets.filter { it != "hero" }) {
                    val link = f.dm("get_cloud_primary_image",json("""{"id":"$id"}"""))
                    assertEquals(originals.getValue(id),link.cloudText("assetId"))
                    assertEquals(cloudDigest(first.expected),link.cloudText("sha256"))
                }
                assertEquals(targets.size+1,f.signal("mediaProof").getValue("staged").jsonArray.size)
            } finally { activity.pause().stop().destroy() }
        }
    }
}
