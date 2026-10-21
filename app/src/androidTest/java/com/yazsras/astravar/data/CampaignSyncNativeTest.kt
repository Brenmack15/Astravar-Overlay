package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android SQLite/Room/domain acceptance using an isolated fictional database.
 * This is not hosted MCP, network, standing-grant or physical phone acceptance.
 */
@RunWith(AndroidJUnit4::class)
class CampaignSyncNativeTest {
    private lateinit var context: Context
    private lateinit var db: CampaignLedgerDb
    private lateinit var ledger: CampaignLedgerRepository
    private lateinit var domain: CampaignSyncDomain
    private val databaseName = "native-campaign-v22-fixture.db"
    private val campaign = "local-campaign"
    private val source = "Confirmed isolated fictional native acceptance"
    private val player = "native-v22-player"
    private fun fields(vararg pairs: Pair<String,JsonElement>) = JsonObject(mapOf(*pairs))

    @Before fun fixtureDatabaseOnly() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertEquals("Physical devices are forbidden for destructive fixtures", "1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals("com.yazsras.astravar.v2uidebug", context.packageName)
        context.deleteDatabase(databaseName)
        reopen()
        ledger.records.saveProfile(CharacterProfile(name="Fictional native player",id=player,currentHp=12,maximumHp=20,temporaryHp=2),source=source)
        ledger.party.saveSheet(player,PartySheetData(resources=listOf(SheetResource("Level 2",2,3),SheetResource("Unknown charge"))),source=source)
        ledger.party.saveWallet(PartyWallet("native-v22-wallet",player,"Fictional wallet",gold=10),source=source)
        Unit
    }
    private fun reopen() {
        db = Room.databaseBuilder(context,CampaignLedgerDb::class.java,databaseName).build()
        ledger = CampaignLedgerRepository(db)
        domain = CampaignSyncDomain(db)
    }
    @After fun deleteOnlyFixtureDatabase() { if (::db.isInitialized) db.close(); if (::context.isInitialized) context.deleteDatabase(databaseName) }
    private suspend fun apply(id: String,vararg operations: CampaignSyncOperation) = domain.apply(campaign,id,source,operations.toList())
    private suspend fun rejects(action: suspend () -> Unit) { var failed = false; try { action() } catch (_: Exception) { failed = true }; assertTrue("Invalid operation must fail",failed) }

    @Test fun nativeCommittedHpSlotAndCheckpointSurviveDatabaseReopen() = runBlocking {
        val hp = CampaignSyncOperation("adjust_hp",player,"character-profile",1,fields("delta" to JsonPrimitive(-3)))
        val first = apply("native-hp",hp)
        assertEquals(first,apply("native-hp",hp))
        apply("native-slot",CampaignSyncOperation("adjust_resource",player,"party-sheet",1,fields("resourceName" to JsonPrimitive("Level 2"),"delta" to JsonPrimitive(-1))))
        val checkpoint = CampaignCheckpoint("native-v22-checkpoint","Fictional native session",scene="Workshop",unresolvedChoice="Choose next project",unknowns=listOf("Spell charges"))
        apply("native-save",CampaignSyncOperation("save_checkpoint",checkpoint.id,"checkpoint",0,campaignJson.encodeToJsonElement(checkpoint).jsonObject))
        db.close(); reopen()
        val actual = requireNotNull(db.recordsDao().characterProfile(player))
        assertEquals(9,actual.currentHp); assertEquals(20,actual.maximumHp); assertEquals(2,actual.temporaryHp); assertEquals(2L,actual.revision)
        assertEquals(first,db.recordsDao().receipt(CampaignSyncDomain.receiptId("native-hp")))
        val sheet = requireNotNull(db.partyDao().sheet(player)).sheet
        assertEquals(1,sheet.resources.first().current); assertNull(sheet.resources.last().current)
        val saved = domain.snapshot(campaign).single { it.kind == "checkpoint" }
        assertEquals(checkpoint.id,saved.recordId); assertEquals(1L,saved.revision)
        assertEquals("Choose next project",saved.fields["unresolvedChoice"]?.jsonPrimitive?.content)
        assertEquals(source,saved.fields["source"]?.jsonPrimitive?.content)
        rejects { apply("native-stale",hp) }
        assertEquals(9,db.recordsDao().characterProfile(player)?.currentHp)
    }

    @Test fun nativeAtomicPurchaseRollbackAndRetryPreserveSinglePhysicalIdentity() = runBlocking {
        val debit = CampaignSyncOperation("adjust_currency","native-v22-wallet","wallet",1,fields("goldDelta" to JsonPrimitive(-4)))
        val invalid = LedgerDraft("",evidenceStatus="Confirmed",quantity=1,itemState="Carried")
        rejects { apply("native-bad-buy",debit,CampaignSyncOperation("acquire_item","native-v22-coil","item",0,campaignJson.encodeToJsonElement(invalid).jsonObject)) }
        assertEquals(10L,db.partyDao().wallet("native-v22-wallet")?.gold)
        assertNull(db.recordsDao().receipt(CampaignSyncDomain.receiptId("native-bad-buy")))
        assertTrue(db.ledgerDao().allItems().isEmpty())
        val draft = LedgerDraft("Fictional copper coil",evidenceStatus="Confirmed",quantity=1,itemState="Carried")
        val acquire = CampaignSyncOperation("acquire_item","native-v22-coil","item",0,campaignJson.encodeToJsonElement(draft).jsonObject)
        val receipt = apply("native-buy",debit,acquire)
        assertEquals(receipt,apply("native-buy",debit,acquire))
        val bag = ledger.create(LedgerDraft("Fictional native container",quantity=1))
        apply("native-move",CampaignSyncOperation("move_item","native-v22-coil","item",1,fields("expectedPlacementRevision" to JsonNull,"containerItemId" to JsonPrimitive(bag.id),"state" to JsonPrimitive("Stored"))))
        db.close(); reopen()
        assertEquals(6L,db.partyDao().wallet("native-v22-wallet")?.gold)
        assertEquals(bag.id,db.partyDao().placement("native-v22-coil")?.containerItemId)
        assertEquals(2,db.ledgerDao().allItems().size)
        assertEquals(1,db.ledgerDao().getItem("native-v22-coil")?.quantity)
        assertEquals(receipt,db.recordsDao().receipt(CampaignSyncDomain.receiptId("native-buy")))
    }

    @Test fun nativeCompanionAndStageChangesKeepIndependentResourcesAndLaterWork() = runBlocking {
        ledger.records.saveCompanionState(CompanionState("Fictional native owl",companionId="native-v22-owl"),source=source)
        ledger.records.saveCompanionResource(CompanionResource("Charges","native-v22-owl",2,3,id="native-v22-owl-charge"),source=source)
        apply("native-owl",CampaignSyncOperation("adjust_resource","native-v22-owl-charge","companion-resource",1,fields("delta" to JsonPrimitive(-1))))
        ledger.records.saveProject(InventionProject("Fictional native tool",status="Construction",id="native-v22-tool"),source=source)
        val stage = ProjectStage("native-v22-tool","First stage",id="native-v22-stage",status="In progress")
        ledger.records.saveStage(stage,source=source)
        ledger.records.saveStage(ProjectStage("native-v22-tool","Later stage",id="native-v22-later"),source=source)
        apply("native-stage",CampaignSyncOperation("set_record",stage.id,"stage",1,campaignJson.encodeToJsonElement(stage.copy(status="Completed")).jsonObject))
        db.close(); reopen()
        assertEquals(1,db.recordsDao().resource("native-v22-owl-charge")?.current)
        assertEquals(12,db.recordsDao().characterProfile(player)?.currentHp)
        assertEquals("Completed",db.recordsDao().stage(stage.id)?.status)
        assertEquals("Planned",db.recordsDao().stage("native-v22-later")?.status)
        assertEquals("Construction",db.recordsDao().project("native-v22-tool")?.status)
        assertTrue(db.ledgerDao().allItems().isEmpty())
    }
}
