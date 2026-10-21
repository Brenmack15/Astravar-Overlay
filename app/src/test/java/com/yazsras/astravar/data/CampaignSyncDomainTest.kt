package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CampaignSyncDomainTest {
    private lateinit var db: CampaignLedgerDb
    private lateinit var domain: CampaignSyncDomain
    private lateinit var ledger: CampaignLedgerRepository
    private val campaign = "local-campaign"
    private val source = "Confirmed isolated fictional event"
    @Before fun open() { runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context,CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        domain = CampaignSyncDomain(db); ledger = CampaignLedgerRepository(db)
        ledger.records.saveProfile(CharacterProfile(name="Fictional player",id="synthetic-player",currentHp=12,maximumHp=20,temporaryHp=2),source=source)
        ledger.party.saveSheet("synthetic-player",PartySheetData(resources=listOf(SheetResource("Level 2",2,3),SheetResource("Unknown charge"))),source=source)
        ledger.party.saveWallet(PartyWallet("synthetic-wallet","synthetic-player","Fictional wallet",gold=10),source=source)
    } }
    @After fun close() = db.close()
    private fun fields(vararg pairs: Pair<String,JsonElement>) = JsonObject(mapOf(*pairs))
    private fun number(value: Int) = JsonPrimitive(value)
    private suspend fun rejects(block:suspend ()->Unit) { var failed=false; try {block()} catch (_:Exception) {failed=true}; assertTrue(failed) }
    private suspend fun apply(id:String,vararg ops:CampaignSyncOperation) = domain.apply(campaign,id,source,ops.toList())
    @Test fun narrowGameplayTransactionsExcludeReadOnlyArchiveAndHistoryWithoutChangingThem() = runBlocking {
        val body="Large read-only original archive 🦉\n".repeat(8000)
        val reference=CampaignReferenceRecord("narrow-read-reference",campaign,"original-reference-source","Fixture archive","Reference",body,"{}",
            "https://example.invalid/original","original-source-revision",campaignHash(body),"original-package-hash")
        db.archiveDao().insert(reference)
        val audit=CampaignAudit("large-read-only-audit","old-command","character-profile","synthetic-player","UPDATE",source,1,2,100,beforeJson=body,afterJson=body)
        db.recordsDao().insert(audit)
        assertTrue(domain.snapshot(campaign,includeReadOnly=false).none{it.kind=="audit"||it.recordId.startsWith("archive:")})
        val receipt=apply("narrow-hp",CampaignSyncOperation("adjust_hp","synthetic-player","character-profile",1,fields("delta" to number(-1))))
        val outputs=campaignJson.decodeFromString<List<CampaignSyncRecord>>(receipt.outcomeJson)
        assertEquals(setOf("character-profile"),outputs.map{it.kind}.toSet())
        assertEquals(reference,db.archiveDao().allReferences().single())
        assertEquals(audit,db.recordsDao().allAudit().single{it.id==audit.id})
        assertEquals(11,db.recordsDao().characterProfile("synthetic-player")?.currentHp)
    }
    @Test fun largeAuditProjectionReconstructsExactEvidenceAndRequiresAllReadScopes() {
        val full=CampaignSyncRecord("large-audit", "audit", 1,buildJsonObject {
            put("id","large-audit");put("commandId","original-command");put("recordType","item");put("recordId","original-item")
            put("kind","UPDATE");put("source","Original source identity");put("priorRevision",2);put("resultingRevision",3);put("createdAt",100)
            put("beforeJson", "Unicode 🦉 and escaped \"evidence\"\n".repeat(4000));put("afterJson","Confirmed text ".repeat(5000))
        })
        val projected=CampaignSyncAuditProjection.records(full)
        assertTrue(projected.size>2);assertEquals(full.recordId,projected.first().recordId)
        assertEquals("original-command",projected.first().fields.getValue("commandId").jsonPrimitive.content)
        assertEquals(2,projected.first().fields.getValue("priorRevision").jsonPrimitive.int)
        val text=projected.drop(1).sortedBy{it.fields.getValue("index").jsonPrimitive.int}.joinToString(""){it.fields.getValue("text").jsonPrimitive.content}
        assertEquals(OwnerSyncProtocol.canonical(full.fields),text)
        assertEquals(campaignHash(text),projected.first().fields.getValue("contentSha256").jsonPrimitive.content)
        assertTrue(projected.all{OwnerSyncProtocol.canonical(campaignJson.encodeToJsonElement(it)).toByteArray(Charsets.UTF_8).size<40_000})
        assertTrue(CampaignConnectionProjection.project(projected,setOf("continuity")).isEmpty())
        assertEquals(projected,CampaignConnectionProjection.project(projected,CampaignConnectionProtocol.categories))
    }
    @Test fun legacyAcquisitionHistoryKeepsOriginalLedgerCommandAndScopedItemIdentity() = runBlocking {
        val item=ledger.create(LedgerDraft("Fictional historical lens",quantity=1,source="Resolved historical acquisition"),"legacy-acquisition")
        val snapshot=domain.snapshot(campaign)
        val evidence=snapshot.filter {it.kind=="audit" && it.fields["commandId"]?.jsonPrimitive?.content=="legacy-acquisition"}
        assertEquals(2,evidence.size)
        val record=evidence.single {it.recordId=="ledger:${campaignHash("legacy-acquisition").take(48)}"}
        val original=requireNotNull(db.ledgerDao().event("legacy-acquisition"))
        assertEquals(campaignJson.encodeToJsonElement(original).jsonObject,record.fields)
        assertEquals(item.id,record.fields.getValue("itemId").jsonPrimitive.content)
        assertEquals("Resolved historical acquisition",record.fields.getValue("source").jsonPrimitive.content)
        assertEquals(1L,record.revision);assertEquals("CREATE",record.fields.getValue("kind").jsonPrimitive.content)
        val modern=evidence.single {it.recordId=="legacy-acquisition"}
        val originalAudit=db.recordsDao().allAudit().single {it.id=="legacy-acquisition"}
        assertEquals(campaignJson.encodeToJsonElement(originalAudit).jsonObject,modern.fields)
        assertEquals(item.id,modern.fields.getValue("recordId").jsonPrimitive.content)
        assertNotEquals(record.recordId,modern.recordId)
        assertEquals(snapshot.size,snapshot.map {it.kind to it.recordId}.distinct().size)
        assertFalse(domain.snapshot("synthetic-other-campaign").any {it.recordId in setOf(record.recordId,modern.recordId)})
    }
    @Test fun healthKeepsTemporaryMaximumAndUnknownAndRetriesExactlyOnce() = runBlocking {
        val op=CampaignSyncOperation("adjust_hp","synthetic-player","character-profile",1,fields("delta" to number(-3)))
        val first=apply("hp-test",op); assertEquals(first,apply("hp-test",op))
        val value=requireNotNull(db.recordsDao().characterProfile("synthetic-player"))
        assertEquals(9,value.currentHp); assertEquals(20,value.maximumHp); assertEquals(2,value.temporaryHp); assertEquals(2L,value.revision)
        rejects { apply("hp-test",op.copy(fields=fields("delta" to number(-4)))) }
        rejects { apply("hp-stale",op) }
        val result=campaignJson.decodeFromString<List<CampaignSyncRecord>>(first.outcomeJson)
        assertEquals(9,result.single().fields["currentHp"]?.jsonPrimitive?.int)
    }
    @Test fun slotExpenditureUsesNamedOwnCounterAndUnknownCannotBeSpent() = runBlocking {
        apply("slot",CampaignSyncOperation("adjust_resource","synthetic-player","party-sheet",1,fields("resourceName" to JsonPrimitive("Level 2"),"delta" to number(-1))))
        val sheet=requireNotNull(db.partyDao().sheet("synthetic-player"))
        assertEquals(1,sheet.sheet.resources.first().current); assertNull(sheet.sheet.resources.last().current)
        rejects { apply("unknown",CampaignSyncOperation("adjust_resource","synthetic-player","party-sheet",2,fields("resourceName" to JsonPrimitive("Unknown charge"),"delta" to number(-1)))) }
        assertEquals(2L,db.partyDao().sheet("synthetic-player")?.revision)
    }
    @Test fun atomicPurchaseRetainsPhysicalIdentityAndRetryCannotDoubleSpend() = runBlocking {
        val draft=LedgerDraft("Fictional copper coil",evidenceStatus="Confirmed",quantity=1,itemState="Carried")
        val ops=arrayOf(CampaignSyncOperation("adjust_currency","synthetic-wallet","wallet",1,fields("goldDelta" to number(-4))),
            CampaignSyncOperation("acquire_item","synthetic-coil","item",0,campaignJson.encodeToJsonElement(draft).jsonObject))
        val receipt=apply("purchase",*ops); assertEquals(receipt,apply("purchase",*ops))
        assertEquals(6L,db.partyDao().wallet("synthetic-wallet")?.gold)
        assertEquals("synthetic-coil",db.ledgerDao().allItems().single().id)
        assertEquals(2,campaignJson.decodeFromString<List<CampaignSyncRecord>>(receipt.outcomeJson).size)
    }
    @Test fun failedAtomicPurchaseRollsBackWalletItemAndAllReceipts() = runBlocking {
        val bad=LedgerDraft("",evidenceStatus="Confirmed",quantity=1,itemState="Carried")
        rejects { apply("bad-purchase",CampaignSyncOperation("adjust_currency","synthetic-wallet","wallet",1,fields("goldDelta" to number(-4))),
            CampaignSyncOperation("acquire_item","synthetic-coil","item",0,campaignJson.encodeToJsonElement(bad).jsonObject)) }
        assertEquals(10L,db.partyDao().wallet("synthetic-wallet")?.gold); assertTrue(db.ledgerDao().allItems().isEmpty())
        assertNull(db.recordsDao().receipt(CampaignSyncDomain.receiptId("bad-purchase")))
    }
    @Test fun storageMovePreservesOmittedHolderOwnerAndExplicitNullRemainsAnExplicitClear() = runBlocking {
        val bag=ledger.create(LedgerDraft("Fictional bag",quantity=1))
        val unplaced=ledger.create(LedgerDraft("Fictional owned material",owner="Known fixture owner",quantity=1))
        apply("unplaced-storage",CampaignSyncOperation("move_item",unplaced.id,"item",1,fields("expectedPlacementRevision" to JsonNull,"containerItemId" to JsonPrimitive(bag.id),"state" to JsonPrimitive("Stored"))))
        assertEquals("Known fixture owner",db.ledgerDao().getItem(unplaced.id)?.owner)
        assertEquals(bag.id,db.partyDao().placement(unplaced.id)?.containerItemId)
        val placed=ledger.create(LedgerDraft("Fictional held material",quantity=1))
        ledger.party.moveItem(ItemMoveCommand(placed.id,1,null,holderProfileId="synthetic-player",source=source))
        apply("placed-storage",CampaignSyncOperation("move_item",placed.id,"item",2,fields("expectedPlacementRevision" to number(1),"containerItemId" to JsonPrimitive(bag.id),"state" to JsonPrimitive("Stored"))))
        assertEquals("synthetic-player",db.partyDao().placement(placed.id)?.holderProfileId)
        assertEquals("Fictional player",db.ledgerDao().getItem(placed.id)?.owner)
        apply("explicit-clear",CampaignSyncOperation("move_item",placed.id,"item",3,fields("expectedPlacementRevision" to number(2),"holderProfileId" to JsonNull)))
        assertNull(db.partyDao().placement(placed.id)?.holderProfileId)
        assertEquals(bag.id,db.partyDao().placement(placed.id)?.containerItemId)
        assertEquals("Stored",db.partyDao().placement(placed.id)?.state)
        assertEquals("Unknown",db.ledgerDao().getItem(placed.id)?.owner)
        assertFalse(campaignJson.encodeToString(ItemMoveCommand("fixture",1,source=source)).contains("preserveOwner"))
    }
    @Test fun transferMovesSameItemIntoContainerAndReturnsBothCommittedRevisions() = runBlocking {
        val item=ledger.create(LedgerDraft("Fictional lens",quantity=1)); val bag=ledger.create(LedgerDraft("Fictional container",quantity=1))
        val receipt=apply("move",CampaignSyncOperation("move_item",item.id,"item",1,fields("expectedPlacementRevision" to JsonNull,
            "containerItemId" to JsonPrimitive(bag.id),"state" to JsonPrimitive("Stored"))))
        val values=campaignJson.decodeFromString<List<CampaignSyncRecord>>(receipt.outcomeJson)
        assertEquals(setOf("item","item-placement"),values.map {it.kind}.toSet())
        assertEquals(bag.id,db.partyDao().placement(item.id)?.containerItemId)
        assertEquals(2,db.ledgerDao().allItems().size); assertEquals(1,db.ledgerDao().getItem(item.id)?.quantity)
        assertEquals(2L,db.ledgerDao().getItem(item.id)?.revision)
    }
    @Test fun companionAdjustmentCannotFallBackToPlayerHp() = runBlocking {
        ledger.records.saveCompanionState(CompanionState("Fictional owl",companionId="synthetic-owl"),source=source)
        ledger.records.saveCompanionResource(CompanionResource("Charges","synthetic-owl",2,3,id="synthetic-owl-charge"),source=source)
        apply("owl",CampaignSyncOperation("adjust_resource","synthetic-owl-charge","companion-resource",1,fields("delta" to number(-1))))
        assertEquals(1,db.recordsDao().resource("synthetic-owl-charge")?.current)
        assertEquals(12,db.recordsDao().characterProfile("synthetic-player")?.currentHp)
    }
    @Test fun completingOneStageDoesNotCompleteProjectLaterStageOrConsumeMaterials() = runBlocking {
        ledger.records.saveProject(InventionProject("Fictional tool",status="Construction",id="synthetic-tool"),source=source)
        val stage=ProjectStage("synthetic-tool","First stage",id="synthetic-stage",status="In progress")
        ledger.records.saveStage(stage,source=source)
        ledger.records.saveStage(ProjectStage("synthetic-tool","Later stage",id="synthetic-later"),source=source)
        apply("stage",CampaignSyncOperation("set_record",stage.id,"stage",1,campaignJson.encodeToJsonElement(stage.copy(status="Completed")).jsonObject))
        assertEquals("Completed",db.recordsDao().stage(stage.id)?.status)
        assertEquals("Planned",db.recordsDao().stage("synthetic-later")?.status)
        assertEquals("Construction",db.recordsDao().project("synthetic-tool")?.status)
    }
    @Test fun minimalCheckpointUsesExplicitOperationIdentityAndSelectedCampaignWithoutInference() = runBlocking {
        val selected="synthetic-selected-campaign"
        ledger.records.saveProfile(CharacterProfile(id="selected-owner",name="Fictional selected owner"),source=source)
        db.partyDao().put(PartySheetRecord("selected-owner",campaignJson.encodeToString(PartySheetData()),campaignId=selected))
        domain.apply(selected,"minimal-checkpoint",source,listOf(CampaignSyncOperation("save_checkpoint","selected-checkpoint","checkpoint",0,fields("title" to JsonPrimitive("Resolved fictional session")))))
        val saved=requireNotNull(db.partyDao().checkpoint("selected-checkpoint"))
        assertEquals(selected,saved.campaignId);assertEquals("selected-checkpoint",saved.id);assertEquals("",saved.scene);assertTrue(saved.unknowns.isEmpty())
        rejects { domain.apply(selected,"wrong-campaign",source,listOf(CampaignSyncOperation("save_checkpoint","wrong-checkpoint","checkpoint",0,fields("title" to JsonPrimitive("Wrong campaign"),"campaignId" to JsonPrimitive("local-campaign"))))) }
        assertNull(db.partyDao().checkpoint("wrong-checkpoint"))
    }
    @Test fun checkpointPreservesSourceUnresolvedChoiceAndIndependentReadback() = runBlocking {
        val checkpoint=CampaignCheckpoint("synthetic-checkpoint","Fictional session",scene="Workshop",unresolvedChoice="Choose next project",unknowns=listOf("Spell charges"))
        apply("save",CampaignSyncOperation("save_checkpoint",checkpoint.id,"checkpoint",0,campaignJson.encodeToJsonElement(checkpoint).jsonObject))
        val saved=domain.snapshot(campaign).single {it.kind=="checkpoint"}
        assertEquals(checkpoint.id,saved.recordId); assertEquals(1L,saved.revision)
        assertEquals(source,saved.fields["source"]?.jsonPrimitive?.content)
        assertEquals("Choose next project",saved.fields["unresolvedChoice"]?.jsonPrimitive?.content)
    }
    @Test fun descriptiveUpdatesCannotBypassDedicatedHpOrResourceScopes() = runBlocking {
        val profile=requireNotNull(db.recordsDao().characterProfile("synthetic-player"))
        rejects { apply("bypass",CampaignSyncOperation("set_record",profile.id,"character-profile",1,campaignJson.encodeToJsonElement(profile.copy(currentHp=20)).jsonObject)) }
        val sheet=requireNotNull(db.partyDao().sheet(profile.id))
        rejects { apply("bypass-slot",CampaignSyncOperation("set_record",profile.id,"party-sheet",1,campaignJson.encodeToJsonElement(sheet.copy(contentJson=campaignJson.encodeToString(sheet.sheet.copy(resources=emptyList())))).jsonObject)) }
        assertEquals(12,db.recordsDao().characterProfile(profile.id)?.currentHp)
    }
    @Test fun campaignScopePreventsLegacyInventoryOrHiddenCrossCampaignMutation() = runBlocking {
        val item=ledger.create(LedgerDraft("Fictional private item",quantity=1))
        ledger.records.saveProfile(CharacterProfile(name="Other campaign",id="synthetic-other"),source=source)
        val row=PartySheetRecord("synthetic-other",campaignJson.encodeToString(PartySheetData()),campaignId="synthetic-campaign")
        db.partyDao().put(row)
        assertFalse(domain.snapshot("synthetic-campaign").any {it.kind=="item"})
        rejects { domain.apply("synthetic-campaign","scope",source,listOf(CampaignSyncOperation("consume_item",item.id,"item",1,fields("quantity" to number(1))))) }
        assertEquals(1,db.ledgerDao().getItem(item.id)?.quantity)
    }
    @Test fun campaignTransactionBackupPreservesAllChildAuditsReceiptsAndRejectsForgedAggregateReadback() = runBlocking {
        apply("backup-hp-first",CampaignSyncOperation("adjust_hp","synthetic-player","character-profile",1,fields("delta" to number(-3))))
        apply("backup-hp-later",CampaignSyncOperation("adjust_hp","synthetic-player","character-profile",2,fields("delta" to number(-2))))
        val exported=ledger.export()
        val target=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val repo=CampaignLedgerRepository(target); val preview=repo.previewImport(exported)
            assertTrue(preview.summary+preview.conflicts,preview.valid)
            repo.restore(preview,"fictional-sync-backup-restore")
            assertEquals(7,target.recordsDao().characterProfile("synthetic-player")?.currentHp)
            assertEquals(db.recordsDao().receipt(CampaignSyncDomain.receiptId("backup-hp-first")),target.recordsDao().receipt(CampaignSyncDomain.receiptId("backup-hp-first")))
        } finally {target.close()}
        val data=campaignDecodeBackup(exported)
        val aggregate=data.records.audit.first {it.recordType=="campaign-transaction"}
        val outcomes=campaignJson.decodeFromString<List<CampaignSyncRecord>>(requireNotNull(aggregate.afterJson))
        val forgedJson=campaignJson.encodeToString(outcomes.map {it.copy(fields=JsonObject(it.fields+("currentHp" to number(19))))})
        val forged=data.copy(records=data.records.copy(audit=data.records.audit.map {if(it.id==aggregate.id) it.copy(afterJson=forgedJson) else it},
            receipts=data.records.receipts.map {if(it.commandId==aggregate.commandId) it.copy(outcomeJson=forgedJson) else it}))
        val empty=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try { assertFalse(CampaignLedgerRepository(empty).previewImport(campaignJson.encodeToString(forged)).valid) } finally {empty.close()}
    }

    @Test fun archiveReferenceChunksBoundEscapedBytesAndReassembleExactUnicodeSourceIdentities() {
        val body=("Fictional \"quoted\" lore \\ path\n星図 🧭\u0001 ".replace("\\u0001","\u0001")).repeat(2000)
        val properties="{\"fictional\":\""+"x".repeat(50000)+"\"}"
        val reference=CampaignReferenceRecord("original-reference-id","local-campaign","original-notion-id","Fictional archive","Research",body,properties,
            "https://example.invalid/notion/source","original-source-revision","original-content-hash","original-package-hash")
        val chunks=CampaignSyncReferenceProjection.records(reference)
        val metadata=chunks.single {it.recordId.endsWith(":meta")}.fields
        assertEquals(reference.id,metadata["referenceId"]?.jsonPrimitive?.content)
        assertEquals(reference.externalId,metadata["externalId"]?.jsonPrimitive?.content)
        for((part,original) in listOf("body" to body,"properties" to properties)) {
            val values=chunks.filter {it.fields["part"]?.jsonPrimitive?.content==part}.sortedBy {it.fields.getValue("index").jsonPrimitive.int}
            assertEquals(original,values.joinToString("") {it.fields.getValue("text").jsonPrimitive.content})
            assertTrue(values.size>1)
            values.forEach {chunk ->
                val text=chunk.fields.getValue("text").jsonPrimitive.content
                assertTrue(JsonPrimitive(text).toString().toByteArray(Charsets.UTF_8).size<=CampaignSyncReferenceProjection.MAX_ESCAPED_TEXT_BYTES+2)
                assertTrue(campaignJson.encodeToString(chunk).toByteArray(Charsets.UTF_8).size<24*1024)
                assertEquals(campaignHash(text),chunk.fields["contentHash"]?.jsonPrimitive?.content)
                assertEquals(reference.sourceRevision,chunk.fields["sourceRevision"]?.jsonPrimitive?.content)
                assertTrue(chunk.recordId.length<200)
            }
        }
    }

    @Test fun importedCampaignReadsOnlyExplicitMappedRecordsAndAtomicallyScopesNewInventionsAndItems() = runBlocking {
        val selected="fictional-imported-campaign"
        ledger.records.saveProfile(CharacterProfile("Fictional imported actor",id="fictional-imported-actor"),source=source)
        db.partyDao().put(PartySheetRecord("fictional-imported-actor",campaignJson.encodeToString(PartySheetData()),campaignId=selected))
        val mapped=ledger.create(LedgerDraft("Fictional mapped material",quantity=2))
        val unrelated=ledger.create(LedgerDraft("Fictional unrelated material",quantity=5))
        ledger.party.saveExternalReference(ExternalRecordReference("fictional-map","notion","fictional-original-page","item",mapped.id,campaignId=selected),source=source)
        val scoped=domain.snapshot(selected)
        assertEquals(listOf(mapped.id),scoped.filter {it.kind=="item"}.map {it.recordId})
        assertFalse(domain.snapshot("local-campaign").any {it.kind=="item" && it.recordId==mapped.id})
        val project=InventionProject("Fictional new research",id="fictional-new-project",status="Research")
        val stage=ProjectStage(project.id,"Fictional first stage",id="fictional-new-stage")
        val acquired=LedgerDraft("Fictional acquired crystal",quantity=1,itemState="Carried",evidenceStatus="Confirmed")
        domain.apply(selected,"fictional-new-records",source,listOf(
            CampaignSyncOperation("set_record",project.id,"project",0,campaignJson.encodeToJsonElement(project).jsonObject),
            CampaignSyncOperation("set_record",stage.id,"stage",0,campaignJson.encodeToJsonElement(stage).jsonObject),
            CampaignSyncOperation("acquire_item","fictional-new-crystal","item",0,campaignJson.encodeToJsonElement(acquired).jsonObject)))
        val after=domain.snapshot(selected)
        assertTrue(after.any {it.kind=="project" && it.recordId==project.id})
        assertTrue(after.any {it.kind=="stage" && it.recordId==stage.id})
        assertTrue(after.any {it.kind=="item" && it.recordId=="fictional-new-crystal"})
        assertFalse(after.any {it.recordId==unrelated.id})
        assertFalse(after.any {it.fields["namespace"]?.jsonPrimitive?.content=="campaign-sync-membership"})
        assertFalse(domain.snapshot("local-campaign").any {it.recordId==project.id || it.recordId=="fictional-new-crystal"})
    }

    @Test fun movementAndReferenceCannotClaimRecordsFromAnotherCampaign() = runBlocking {
        ledger.records.saveProfile(CharacterProfile("Fictional private other",id="fictional-other-profile"),source=source)
        db.partyDao().put(PartySheetRecord("fictional-other-profile",campaignJson.encodeToString(PartySheetData()),campaignId="fictional-other-campaign"))
        val item=ledger.create(LedgerDraft("Fictional local lens",quantity=1))
        rejects { apply("cross-move",CampaignSyncOperation("move_item",item.id,"item",1,fields("holderProfileId" to JsonPrimitive("fictional-other-profile"),"expectedPlacementRevision" to JsonNull))) }
        val reference=ExternalRecordReference("cross-reference","notion","fictional-source","character-profile","fictional-other-profile")
        rejects {apply("cross-reference",CampaignSyncOperation("set_record",reference.id,"reference",0,campaignJson.encodeToJsonElement(reference).jsonObject))}
        assertEquals(1L,db.ledgerDao().getItem(item.id)?.revision)
    }

    @Test fun changedArchiveSourceGetsANewImmutableVersionedIdentity() {
        val original=CampaignReferenceRecord("original-reference-id","local-campaign","original-notion-id","Fictional archive","Research","First source","{}",
            "https://example.invalid/source","source-v1","original-content-hash","package1")
        val first=CampaignSyncReferenceProjection.records(original)
        val next=CampaignSyncReferenceProjection.records(original.copy(body="Later verified source",sourceRevision="source-v2"))
        assertEquals(first,CampaignSyncReferenceProjection.records(original))
        assertTrue(first.map {it.recordId}.toSet().intersect(next.map {it.recordId}.toSet()).isEmpty())
        assertTrue(next.all {it.fields["referenceId"]?.jsonPrimitive?.content==original.id})
    }

    @Test fun atomicPurchaseThenContainerMoveBackupRestoresWalletNullCoinsPhysicalIdentityAndEveryReceipt() = runBlocking {
        val draft=LedgerDraft("Fictional purchased coil",quantity=1,itemState="Carried",evidenceStatus="Confirmed")
        val purchase=apply("wallet-backup-purchase",
            CampaignSyncOperation("adjust_currency","synthetic-wallet","wallet",1,fields("goldDelta" to number(-4))),
            CampaignSyncOperation("acquire_item","synthetic-backup-coil","item",0,campaignJson.encodeToJsonElement(draft).jsonObject))
        val purchased=campaignJson.decodeFromString<List<CampaignSyncRecord>>(purchase.outcomeJson)
        val wallet=purchased.single {it.kind=="wallet"}
        assertEquals(JsonNull,wallet.fields["electrum"]);assertEquals(JsonNull,wallet.fields["platinum"])
        val bag=ledger.create(LedgerDraft("Fictional backup container",quantity=1))
        val movement=apply("wallet-backup-move",CampaignSyncOperation("move_item","synthetic-backup-coil","item",1,
            fields("expectedPlacementRevision" to JsonNull,"containerItemId" to JsonPrimitive(bag.id),"state" to JsonPrimitive("Stored"))))
        val backup=ledger.export()
        val target=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val repository=CampaignLedgerRepository(target);val preview=repository.previewImport(backup)
            assertTrue(preview.summary+preview.conflicts,preview.valid)
            repository.restore(preview,"synthetic-purchase-backup-restore")
            val restored=requireNotNull(target.partyDao().wallet("synthetic-wallet"))
            assertEquals(6L,restored.gold);assertNull(restored.electrum);assertNull(restored.platinum)
            assertEquals(bag.id,target.partyDao().placement("synthetic-backup-coil")?.containerItemId)
            assertEquals(2L,target.ledgerDao().getItem("synthetic-backup-coil")?.revision)
            assertEquals(2,target.ledgerDao().allItems().size)
            assertEquals(purchase,target.recordsDao().receipt(purchase.commandId));assertEquals(movement,target.recordsDao().receipt(movement.commandId))
            db.recordsDao().allReceipts().forEach {assertEquals(it,target.recordsDao().receipt(it.commandId))}
            assertEquals(db.ledgerDao().allEvents(),target.ledgerDao().allEvents())
            assertEquals(db.recordsDao().allAudit(),target.recordsDao().allAudit().filterNot {it.recordType=="backup"})
        } finally {target.close()}
    }

}
