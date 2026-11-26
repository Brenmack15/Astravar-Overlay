package com.yazsras.astravar.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w440dp-h940dp")
class CloudRestoredFormsInteropTest {
    @get:Rule val compose = createAstravarComposeRule()

    @Test fun restoredPortraitsFormsGearAndSharedPoolsAgreeWithTheAuthorizedDm(): Unit = runBlocking {
        val provider = System.getenv("ASTRAVAR_MINIFLARE_PATH")
        if (System.getenv("GENESIS_GATE") == "true") require(!provider.isNullOrBlank()) {
            "The release suite requires its pinned isolated provider runtime."
        }
        assumeTrue("Run the provider-backed native test with ASTRAVAR_MINIFLARE_PATH.", !provider.isNullOrBlank())
        val driver = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "scripts/verify-ascension-forms-recovery.cjs") }.first(File::isFile)
        val worker = ProcessBuilder("node", driver.absolutePath, requireNotNull(provider), "--serve")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val input = worker.inputStream.bufferedReader()
        val output = worker.outputStream.bufferedWriter()
        val cache = CloudSnapshotCache(ApplicationProvider.getApplicationContext<Context>(), null)
        try {
            fun read(): JsonObject {
                val reply = Json.parseToJsonElement(requireNotNull(input.readLine()) { "The isolated provider stopped." }).jsonObject
                require("fixtureError" !in reply) { "The restored provider rejected a test request." }
                return reply
            }
            val pairing = read()
            val vault = OwnerSyncCredential(pairing.cloudText("device"), pairing.cloudText("continuity"), pairing.cloudText("origin"),
                pairing.getValue("expiresAt").jsonPrimitive.long, pairing.cloudText("bearer"))
            val actors = pairing.getValue("fixture").jsonObject.getValue("actors").jsonArray.map { it.jsonObject }
            assertEquals(2, actors.size)
            val monitor = Any()
            fun call(message: JsonObject): JsonObject = synchronized(monitor) {
                output.write(message.toString()); output.newLine(); output.flush(); read()
            }
            fun get(route: String, binary: Boolean): JsonObject = call(buildJsonObject {
                put("call", if (binary) "media" else "request"); put("route", route)
                put("credentialId", vault.credentialId); put("token", vault.bearer)
            })
            fun dm(name: String, id: String): JsonObject = call(buildJsonObject {
                put("call", "dm"); put("name", name); putJsonObject("args") { put("id", id) }
            })
            val client = CloudCampaignClient(cache, { CloudSession(vault, null) },
                OwnerConnectionTransport { origin, route, credential, _, body ->
                    assertEquals(vault.origin, origin); assertSame(vault, credential)
                    assertNull("Inspecting a restored campaign issued a gameplay command.", body)
                    val r = get(route, false)
                    OwnerSyncResponse(r.getValue("status").jsonPrimitive.int, r.getValue("json").jsonObject)
                }, mediaTransport = { s, id, _, upload, bytes ->
                    assertSame(vault, s.credential); assertNull(upload); assertNull(bytes)
                    val r = get("/v3/continuities/${vault.campaignId}/media/primary/$id/thumbnail", true)
                    val status = r.getValue("status").jsonPrimitive.int
                    if (status != 200) throw OwnerSyncFailure("MEDIA_HTTP_$status")
                    Base64.getDecoder().decode(r.cloudText("bytesBase64"))
                })
            val current = client.refresh(openExisting = true)
            assertTrue("The restored provider must have a cutover epoch.", current.version.epoch > 1)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                activity.get().setContent { AstravarTheme { CloudWorkspace(client, current) } }
                fun awaitText(label: String) = compose.waitUntil(20_000) {
                    compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
                }
                fun chooseSection(title: String) {
                    compose.onNodeWithText(title).performScrollTo().performClick()
                }
                fun awaitImage(label: String, target: String, digest: String, insideDialog: Boolean = false) {
                    val image = if (insideDialog) hasContentDescription(label) and hasAnyAncestor(isDialog()) else hasContentDescription(label)
                    compose.waitUntil(20_000) { compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() &&
                        runBlocking { client.cachedImage(target) }?.let(::cloudDigest) == digest }
                }
                val seenDigests = mutableSetOf<String>()
                for (actor in actors) {
                    val id = actor.cloudText("id"); val title = actor.cloudText("title")
                    val hp = actor.getValue("currentHp").jsonPrimitive.int
                    val cap = actor.getValue("maxHp").jsonPrimitive.int
                    val active = actor.cloudText("activeFormId")
                    val forms = actor.getValue("forms").jsonArray.map { it.jsonObject }
                    val sheet = dm("get_cloud_actor_sheet", id).getValue("sheet").jsonObject
                    assertEquals(active, sheet.cloudText("formId"))
                    assertEquals(hp, sheet.getValue("currentHp").jsonPrimitive.int)
                    assertEquals(cap, sheet.getValue("maxHp").jsonPrimitive.int)
                    val gear = actor.getValue("item").jsonObject
                    val pool = actor.getValue("pool").jsonObject
                    val poolId = pool.cloudText("id"); val itemId = gear.cloudText("id")
                    fun verified(idToRead: String): JsonObject {
                        val row = dm("get_cloud_state", idToRead).getValue("records").jsonArray.single().jsonObject
                        assertEquals(runBlocking { client.record(idToRead) }, row)
                        return row
                    }
                    verified(itemId)
                    val poolRow = verified(poolId)
                    val balance = poolRow.getValue("data").jsonObject
                    assertEquals(pool.getValue("current"), balance.getValue("current"))
                    val admin = hasContentDescription("Character administration")
                    compose.waitUntil(20_000) { compose.onAllNodes(admin).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNode(admin).performClick()
                    compose.onNode(hasText(title) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
                    awaitText("$hp / $cap")
                    awaitImage(title, active, forms.single { it.cloudText("id") == active }.cloudText("thumbnailSha256"))
                    chooseSection("Equipment"); awaitText(gear.cloudText("title"))
                    chooseSection("Resources"); awaitText(poolRow.cloudText("title"))
                    val amount = "${balance.getValue("current")}/${balance.getValue("maximum")} remaining"
                    assertTrue(compose.onAllNodesWithContentDescription(amount).fetchSemanticsNodes().isNotEmpty())
                    chooseSection("Forms")
                    for (form in forms) {
                        val formId = form.cloudText("id"); val formTitle = form.cloudText("title")
                        val hash = form.cloudText("thumbnailSha256")
                        assertTrue("Each restored form must have its own source image.", seenDigests.add(hash))
                        awaitText(formTitle)
                        compose.onNodeWithText(formTitle).performScrollTo().performClick()
                        awaitText("Preview form")
                        awaitImage(formTitle, formId, hash, insideDialog = true)
                        compose.onNodeWithText("Close").performClick()
                    }
                    val unchanged = dm("get_cloud_actor_sheet", id)
                    assertEquals(sheet, unchanged.getValue("sheet"))
                    assertEquals(current.version.revision, unchanged.getValue("revision").jsonPrimitive.long)
                }
                assertTrue(seenDigests.size >= 5)
            } finally { activity.pause().stop().destroy() }
        } finally {
            output.close()
            if (!worker.waitFor(12, TimeUnit.SECONDS)) worker.destroyForcibly()
            input.close(); cache.close()
        }
    }
}
