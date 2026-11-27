package com.yazsras.astravar.data

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yazsras.astravar.MainActivity
import com.yazsras.astravar.ui.AstravarTheme
import com.yazsras.astravar.ui.CloudWorkspace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.regex.Pattern

private const val archivedName = "Preservation archive specimen"
private const val activeName = "Preservation active specimen"
private const val archivedId = "qa-preservation-archive"
private val appContext: Context get() = ApplicationProvider.getApplicationContext()
private val guest: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
private fun checkGuest(code: Long) {
    assertEquals(37, Build.VERSION.SDK_INT)
    assertEquals("1",guest.executeShellCommand("getprop ro.kernel.qemu").trim())
    assertEquals("com.yazsras.astravar.v2uidebug",appContext.packageName)
    assertEquals(code,appContext.packageManager.getPackageInfo(appContext.packageName,0).longVersionCode)
}
private fun proofDir() = File(appContext.getExternalFilesDir(null),"qa-preservation").apply { mkdirs() }
private fun archiveWitness(payload: String): JsonObject {
    val json = Json.parseToJsonElement(payload).jsonObject
    val row = json.getValue("items").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content==archivedId }.jsonObject
    val events = json.getValue("events").jsonArray.filter { it.jsonObject.getValue("itemId").jsonPrimitive.content==archivedId }
    assertTrue(row.getValue("archived").jsonPrimitive.boolean)
    assertEquals(JsonNull,row.getValue("quantity"))
    assertEquals(2L,row.getValue("revision").jsonPrimitive.long)
    assertEquals(setOf("qa-pres-create","qa-pres-archive"),events.map { it.jsonObject.getValue("commandId").jsonPrimitive.content }.toSet())
    val receipts = json.getValue("records").jsonObject.getValue("receipts").jsonArray.filter { 
        (it.jsonObject["commandId"] as? JsonPrimitive)?.content in setOf("qa-pres-create","qa-pres-archive") }
    assertEquals(2,receipts.size)
    return buildJsonObject { put("item",row);put("history",JsonArray(events));put("receipts",JsonArray(receipts)) }
}

/** Executes against the original code-58 implementation before adb install -r. */
@RunWith(AndroidJUnit4::class)
class PreservationLegacySeed {
    @Test fun createArchivedSource() = runBlocking {
        checkGuest(58)
        val db=CampaignLedgerDb.open(appContext)
        try {
            val repository=CampaignLedgerRepository(db)
            assertNull(db.ledgerDao().getItem(archivedId))
            val initial=LedgerDraft(title=archivedName,owner="Unknown",itemState="Stored",quantity=null,
                description="Observed archival survey label",source="Synthetic notebook",evidenceStatus="Confirmed")
            repository.create(initial,"qa-pres-create",archivedId)
            repository.update(archivedId,1L,initial.copy(archived=true),"Synthetic administrative archive","qa-pres-archive")
            repository.create(LedgerDraft(title=activeName,quantity=3,source="Synthetic positive control"),"qa-pres-positive","qa-pres-active")
            val witness=archiveWitness(repository.export())
            File(appContext.filesDir,"qa-preservation-code58.json").writeText(witness.toString())
        } finally { db.close() }
    }
}

@RunWith(AndroidJUnit4::class)
class InstalledPreservationGate {
    @get:Rule val compose = createEmptyComposeRule()
    private fun await(m: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(25_000) { compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(m).onLast()
    }
    private fun click(m: SemanticsMatcher) {
        val node=await(m and hasClickAction() and isEnabled())
        if (node.fetchSemanticsNode().parent!=null) runCatching { node.performScrollTo() }
        node.performSemanticsAction(SemanticsActions.OnClick)
    }
    private fun tap(label: String) = click(hasText(label))
    private fun proof(name: String) { assertTrue(guest.takeScreenshot(File(proofDir(),name+".png"))) }

    @Test fun upgradedArchiveIsAbsentFromFindButStillInOwnerBackupAndHistory() {
        checkGuest(59)
        val source=Json.parseToJsonElement(File(appContext.filesDir,"qa-preservation-code58.json").readText()).jsonObject
        val db=CampaignLedgerDb.open(appContext)
        try { runBlocking {
            val repo=CampaignLedgerRepository(db)
            assertEquals(source,archiveWitness(repo.export()))
            val active = repo.items.first().map { it.id }
            assertTrue("qa-pres-active" in active)
            assertFalse(archivedId in active)
        } } finally { db.close() }
        ActivityScenario.launch(MainActivity::class.java).use {
            tap("Find")
            val query = await(hasSetTextAction())
            query.performTextReplacement("Preservation")
            await(hasText(activeName) and hasClickAction()).assertExists()
            compose.onAllNodesWithText(archivedName).assertCountEquals(0)
            await(hasText("1 matches")).assertExists()
            proof("upgraded-find")
            tap("Close")
            click(hasTestTag("navigation-Inventory"))
            await(hasTestTag("genesis-inventory-item-qa-pres-active")).assertExists()
            compose.onAllNodesWithTag("genesis-inventory-item-"+archivedId).assertCountEquals(0)
            tap("Campaign items")
            await(hasText("Equipment, treasures & discoveries")).assertExists()
            await(hasText(activeName) and hasClickAction()).assertExists()
            compose.onAllNodesWithText(archivedName).assertCountEquals(0)
            proof("upgraded-active-inventory")
            tap("Close");tap("Tools");tap("Records, backups & connection");tap("Owner connection");tap("Backups and recovery");tap("Campaign backup")
            tap("Export campaign backup")
            val filename=guest.wait(Until.findObject(By.clazz("android.widget.EditText")),10_000)
            assertNotNull("The system CreateDocument activity must accept a filename",filename)
            filename!!.text="ascension-preservation-witness.json"
            val save=guest.wait(Until.findObject(By.text(Pattern.compile("save",Pattern.CASE_INSENSITIVE))),10_000)
            assertNotNull("The system document provider must offer Save",save)
            save!!.click()
            await(hasText("Campaign backup saved and independently verified.")).assertExists()
            proof("upgraded-owner-backup")
            val path=guest.executeShellCommand("find /sdcard/Download /sdcard/Documents -name ascension-preservation-witness.json").lineSequence().firstOrNull { it.startsWith("/sdcard/") }
            assertNotNull("The real Downloads provider must retain the named synthetic export",path)
            val bytes=guest.executeShellCommand("cat "+path)
            assertEquals(source,archiveWitness(bytes))
            File(proofDir(),"archive-summary.json").writeText(buildJsonObject{
                put("oldCode",58);put("newCode",59);put("archivedId",archivedId);put("revision",2)
                put("receiptCount",2);put("quantity",JsonNull);put("independentExport",true);put("activeSearchMatches",1)
            }.toString())
        }
    }

    private fun exchange(input:JsonObject):JsonObject = Socket().use { sock ->
        sock.connect(InetSocketAddress("127.0.0.1",38271),7000);sock.soTimeout=45_000
        sock.getOutputStream().bufferedWriter().also { it.write(input.toString());it.newLine();it.flush() }
        Json.parseToJsonElement(requireNotNull(sock.getInputStream().bufferedReader().readLine())).jsonObject
            .also { require("fixtureError" !in it) { "The restored test provider refused a read." } }
    }

    @Test fun restoredActorAndFormPortraitsAndUnknownPoolSurviveInstalledReadback() = runBlocking {
        checkGuest(59)
        val pairing=exchange(buildJsonObject{put("call","pair")})
        val actorId="frame-unit";val formId="rig-form"
        val actors=pairing.getValue("fixture").jsonObject.getValue("actors").jsonArray.map{it.jsonObject}
        val frame=actors.single{it.cloudText("id")==actorId}
        val active=frame.getValue("forms").jsonArray.map{it.jsonObject}.single{it.cloudText("id")==formId}
        val vault=OwnerSyncCredential(pairing.cloudText("device"),pairing.cloudText("continuity"),pairing.cloudText("origin"),pairing.getValue("expiresAt").jsonPrimitive.long,pairing.cloudText("bearer"))
        fun read(call:String,route:String)=exchange(buildJsonObject{
            put("call",call);put("route",route);put("credentialId",vault.credentialId);put("token",vault.bearer)
        })
        fun dm(name:String,id:String)=exchange(buildJsonObject{
            put("call","dm");put("name",name);putJsonObject("args"){put("id",id)}
        })
        val cache=CloudSnapshotCache(appContext,"qa-preservation-restored-provider.db")
        val client=CloudCampaignClient(cache,{CloudSession(vault,null)},OwnerConnectionTransport { origin,route,credential,_,body->
            assertEquals(vault.origin,origin);assertEquals(vault,credential);assertNull(body)
            val res=read("request",route);OwnerSyncResponse(res.getValue("status").jsonPrimitive.int,res.getValue("json").jsonObject)
        },mediaTransport={_,id,_,upload,bytes->
            assertNull(upload);assertNull(bytes)
            val res=read("media","/v3/continuities/"+vault.campaignId+"/media/primary/"+id+"/thumbnail")
            val status=res.getValue("status").jsonPrimitive.int
            if(status!=200)throw OwnerSyncFailure("MEDIA_HTTP_"+status)
            Base64.decode(res.cloudText("bytesBase64"),Base64.DEFAULT)
        })
        try {
            val snapshot=client.refresh(true)
            assertEquals(2L,snapshot.version.epoch)
            val baseline=dm("get_cloud_actor_sheet",actorId)
            val sheet=baseline.getValue("sheet").jsonObject
            assertEquals(formId,sheet.cloudText("formId"))
            assertEquals(55,sheet.getValue("currentHp").jsonPrimitive.int);assertEquals(7,sheet.getValue("temporaryHp").jsonPrimitive.int)
            assertTrue(sheet.getValue("conditions").toString().contains("marked"))
            val reviewed=listOf(actorId,formId,"unmeasured-charge","shell-rig","ward","blink").associateWith { id->
                val row=dm("get_cloud_state",id).getValue("records").jsonArray.single().jsonObject
                assertEquals(row,client.record(id));row
            }
            assertEquals(JsonNull,reviewed.getValue("unmeasured-charge").getValue("data").jsonObject.getValue("current"))
            assertTrue(reviewed.getValue("ward").getValue("data").jsonObject.getValue("attuned").jsonPrimitive.boolean)
            assertNotEquals(active.cloudText("thumbnailSha256"),frame.cloudText("thumbnailSha256"))
            fun checkArt(target:String,expected:String) {
                await(hasTestTag("primary-image-"+target)).assertExists()
                compose.waitUntil(20_000){runBlocking{client.cachedImage(target)}?.let(::cloudDigest)==expected}
                val bytes=runBlocking { requireNotNull(client.cachedImage(target)) }
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                try { assertTrue(bitmap.width>0&&bitmap.height>0) } finally {bitmap.recycle()}
            }
            ActivityScenario.launch(MainActivity::class.java).use { activity->
                activity.onActivity { it.setContent { AstravarTheme { CloudWorkspace(client,snapshot) } } }
                await(hasText("Cloud · revision "+snapshot.version.revision))
                click(hasContentDescription("Character administration"))
                click(hasText(frame.cloudText("title")) and hasAnyAncestor(isDialog()))
                await(hasContentDescription("Hit points, 55 of 60, 7 temporary hit points")).assertExists()
                checkArt(formId,active.cloudText("thumbnailSha256"));proof("restored-form-header")
                tap("Equipment");await(hasText("Fixture shell-rig")).assertExists()
                tap("Resources");await(hasText("Unmeasured charge")).assertExists()
                await(hasContentDescription("?/6 remaining")).assertExists()
                proof("restored-resource")
                tap("Find");await(hasSetTextAction()).performTextReplacement(frame.cloudText("title"))
                click(hasText(frame.cloudText("title")) and hasText("Actor"))
                checkArt(actorId,frame.cloudText("thumbnailSha256"));proof("restored-ordinary-portrait")
            }
            assertEquals(baseline,dm("get_cloud_actor_sheet",actorId))
            reviewed.forEach { (id,row)->assertEquals(row,dm("get_cloud_state",id).getValue("records").jsonArray.single().jsonObject) }
            File(proofDir(),"restore-summary.json").writeText(buildJsonObject{
                put("installedCode",59);put("androidApi",37);put("authorityEpoch",snapshot.version.epoch);put("revision",snapshot.version.revision)
                put("ordinaryHp",55);put("temporaryHp",7);put("unknownCharge",JsonNull);put("ordinaryImage",frame.getValue("thumbnailSha256"))
                put("activeFormImage",active.getValue("thumbnailSha256"));put("preservedRecords",reviewed.size)
            }.toString())
        } finally {cache.close()}
    }
}
