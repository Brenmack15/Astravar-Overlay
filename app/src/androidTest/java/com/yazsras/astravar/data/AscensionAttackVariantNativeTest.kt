package com.yazsras.astravar.data

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yazsras.astravar.ui.AstravarTheme
import com.yazsras.astravar.ui.CloudActorWorkspace
import com.yazsras.astravar.ui.CloudCommandForm
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Two separately installed processes exercise the connected actor's actual Actions controls. */
@RunWith(AndroidJUnit4::class)
class AscensionAttackVariantNativeTest : AscensionCloudGuest() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actor = "native-holder"
    private val ranged = "synthetic-ranged-probe"
    private val melee = "synthetic-contact-probe"
    private val completed = CopyOnWriteArrayList<JsonObject>()
    private val failure = AtomicReference<Throwable?>(null)

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(30000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val scrolling = compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction()))
        return if (scrolling.fetchSemanticsNodes().isEmpty()) compose.onAllNodes(matcher).onFirst()
            else scrolling.onFirst().performScrollTo()
    }

    private fun press(text: String) = node(hasText(text) and hasClickAction()).performClick()
    private fun damage(formula: String) = node(hasText(formula) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))

    private suspend fun connectActions(client: CloudCampaignClient) {
        val initial = client.refresh(openExisting = true)
        val operation = client.capabilities().getValue("operations").jsonArray.map { it.jsonObject }
            .single { it.cloudText("name") == "select_damage_variant" }
        compose.setContent {
            var snapshot by remember { mutableStateOf(initial) }
            var draft by remember { mutableStateOf<JsonObject?>(null) }
            var busy by remember { mutableStateOf(false) }
            val coroutine = rememberCoroutineScope()
            AstravarTheme {
                CloudActorWorkspace(client, snapshot, true, actor, {}, { name, args ->
                    check(name == "select_damage_variant")
                    draft = args
                }, {}, { _, _ -> error("Selection must not roll or submit another operation") })
                draft?.let { args ->
                    CloudCommandForm(operation, busy, { draft = null }, { reviewed, id ->
                        busy = true
                        coroutine.launch {
                            try {
                                val outcome = client.submit("select_damage_variant", reviewed, snapshot, id)
                                snapshot = requireNotNull(client.workspace())
                                draft = null
                                completed.add(outcome)
                            } catch (ex: Exception) { failure.set(ex) }
                            finally { busy = false }
                        }
                    }, null, args)
                }
            }
        }
        press("Actions")
    }

    private fun select(formula: String, type: String, attackId: String): JsonObject {
        val prior = completed.size
        damage(formula).performSemanticsAction(SemanticsActions.OnLongClick)
        press(type.replaceFirstChar(Char::uppercase))
        assertEquals("A variant preview must not commit", prior, completed.size)
        press("Submit")
        compose.waitUntil(30000) { failure.get() != null || completed.size > prior }
        failure.get()?.let { throw it }
        assertEquals(prior + 1, completed.size)
        val command = completed[prior]
        assertEquals("COMMITTED", command.cloudText("status"))
        val out = command.getValue("receipt").jsonObject.getValue("outcome").jsonObject
        assertEquals(attackId, out.cloudText("attackId"))
        assertEquals("element", out.cloudText("componentId"))
        assertEquals(type, out.cloudText("type"))
        return command
    }

    private suspend fun stored(client: CloudCampaignClient, id: String, type: String) {
        val row = requireNotNull(client.record(id))
        assertEquals(id, row.cloudText("id"))
        val selection = row.getValue("data").jsonObject.getValue("selections").jsonArray.single().jsonObject
        assertEquals(actor, selection.cloudText("actorId"))
        assertEquals(JsonNull, selection.getValue("formId"))
        assertEquals("element", selection.cloudText("componentId"))
        assertEquals(type, selection.cloudText("type"))
    }

    @Test fun chooseSeparateVariantsThroughTheInstalledActor(): Unit = runBlocking {
        val credential = requireNotNull(OwnerSyncCredentialVault(context).credential(origin, scope))
        val reads = listOf("character", "combat", "resources", "inventory", "economy", "world", "chronicle", "rolls", "spells").map { "$it.read" }
        owner("$base/grants/device:" + credential.credentialId, buildJsonObject {
            put("scopes", JsonArray((reads + "combat.write").map(::JsonPrimitive)))
            putJsonArray("auto") { add("combat.write") }; putJsonArray("actors") { add(actor) }
            put("expectedGrantRevision", 1); put("expiresAt", System.currentTimeMillis() + 86400000); put("revoked", false)
        })
        val client = CloudCampaignClient.open(context)
        connectActions(client)
        damage("1d4 fire").assertExists(); damage("1d8 fire").assertExists()
        select("1d4 fire", "acid", ranged)
        damage("1d4 acid").assertExists(); damage("1d8 fire").assertExists()
        select("1d8 fire", "cold", melee)
        stored(client, ranged, "acid"); stored(client, melee, "cold")
        select("1d4 acid", "fire", ranged)
        press("Resources"); press("Actions")
        damage("1d4 fire").assertExists(); damage("1d8 cold").assertExists()
        stored(client, ranged, "fire"); stored(client, melee, "cold")
        val receipts = completed.map { check ->
            assertEquals(check, client.verify(check.cloudText("commandId"))); check
        }
        evidence("variant-before", buildJsonObject {
            put("api", Build.VERSION.SDK_INT); put("revision", requireNotNull(client.workspace()).version.revision)
            put("receipts", JsonArray(receipts)); put("rangedId", ranged); put("meleeId", melee)
        })
    }

    @Test fun reopenStoredVariantsAfterPackageReplacement(): Unit = runBlocking {
        val previous = File(requireNotNull(context.getExternalFilesDir(null)), "ascension-cloud/variant-before.json")
        val proof = Json.parseToJsonElement(previous.readText()).jsonObject
        val client = CloudCampaignClient.open(context)
        val reopened = requireNotNull(client.workspace())
        assertEquals(proof.getValue("revision").jsonPrimitive.long, reopened.version.revision)
        stored(client, ranged, "fire"); stored(client, melee, "cold")
        connectActions(client)
        damage("1d4 fire").assertExists(); damage("1d8 cold").assertExists()
        for (original in proof.getValue("receipts").jsonArray.map { it.jsonObject })
            assertEquals(original, client.verify(original.cloudText("commandId")))
        assertTrue(completed.isEmpty())
        assertEquals(reopened.version, requireNotNull(client.workspace()).version)
        evidence("variant-after", buildJsonObject {
            put("api", Build.VERSION.SDK_INT); put("revision", reopened.version.revision)
            put("selected", buildJsonObject { put(ranged, "fire"); put(melee, "cold") })
            put("oldReceiptsReverified", proof.getValue("receipts").jsonArray.size)
        })
    }
}
