package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PartyRecordsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: CampaignLedgerDb
    private lateinit var ledger: CampaignLedgerRepository
    private lateinit var party: PartyRecordsRepository
    @Before fun open() { runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        ledger = CampaignLedgerRepository(db)
        party = ledger.party
        ledger.records.saveProfile(CharacterProfile(id = "synthetic-a", name = "Synthetic player", currentHp = 3, maximumHp = 20, level = 2), source = "Synthetic fixture")
        ledger.records.saveProfile(CharacterProfile(id = "synthetic-b", name = "Synthetic companion"), source = "Synthetic fixture")
    } }
    @After fun close() = db.close()
    private suspend fun rejects(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Invalid commands must fail without partial state", failed)
    }
    private suspend fun item(name: String = "Synthetic lens", quantity: Int? = 1) = ledger.create(LedgerDraft(name, quantity = quantity))
    private suspend fun move(item: LedgerItem, holder: String = "synthetic-a", placement: Long? = null, state: String = "Carried", commandId: String = "move-${item.id}") =
        party.moveItem(ItemMoveCommand(item.id, item.revision, placement, holder, state = state, source = "Confirmed synthetic handoff", commandId = commandId))

    @Test fun typedSheetPreservesUnknownsAndNeverChangesAcceptedHpAbilitiesOrResources() = runBlocking {
        ledger.records.saveAbility(CharacterAbility("INT", "synthetic-a", 15), source = "Confirmed synthetic ability")
        val data = PartySheetData(creatureType = "Construct", role = "Synthetic test", movement = listOf(SheetMovement("Fly", 30)),
            savingThrows = listOf(SheetModifier("INT", 4, true)), skills = listOf(SheetModifier("Perception")),
            capabilities = listOf(SheetCapability("Synthetic capability", "Descriptive only", evidenceStatus = "Proposed")),
            attacks = listOf(SheetAttack("Synthetic strike", damageFormula = "1d4", evidenceStatus = "Confirmed", source = "Fictional rules")),
            resources = listOf(SheetResource("Unknown charge")), unresolvedConflicts = listOf("Synthetic HP source disagreement"))
        party.saveSheet("synthetic-a", data, source = "Synthetic source")
        assertEquals(data, party.sheets.first().single().sheet)
        assertNull(party.sheets.first().single().sheet.resources.single().current)
        assertNull(party.sheets.first().single().sheet.skills.single().modifier)
        assertEquals("Proposed", party.sheets.first().single().sheet.capabilities.single().evidenceStatus)
        assertEquals(3, db.recordsDao().characterProfile("synthetic-a")?.currentHp)
        assertEquals(15, db.recordsDao().characterAbility("synthetic-a", "INT")?.score)
        assertTrue(db.recordsDao().allCompanionResources().isEmpty())
    }

    @Test fun sheetVersionsReturnOriginalReceiptAfterLaterEditsAndRejectStaleOrCollidingCommands() = runBlocking {
        val data = PartySheetData(role = "Initial")
        val original = party.saveSheet("synthetic-a", data, source = "Synthetic source", commandId = "sheet-create")
        party.saveSheet("synthetic-a", data.copy(role = "Later correction"), 1, "Later synthetic source", "sheet-later")
        assertEquals(original, party.saveSheet("synthetic-a", data, source = "Synthetic source", commandId = "sheet-create"))
        rejects { party.saveSheet("synthetic-a", data.copy(role = "Stale"), 1, "Stale synthetic source") }
        rejects { party.saveSheet("synthetic-a", data.copy(role = "Collision"), source = "Synthetic source", commandId = "sheet-create") }
        rejects { party.saveSheet("missing-profile", data, source = "Synthetic source") }
        assertEquals(2L, party.sheets.first().single().revision)
        assertEquals("Later correction", party.sheets.first().single().sheet.role)
    }

    @Test fun transfersRetainOneItemQuantityAndOriginalReceiptsAcrossLaterTransfers() = runBlocking {
        val original = item(quantity = 4)
        val first = move(original, commandId = "first-transfer")
        val current = requireNotNull(db.ledgerDao().getItem(original.id))
        val second = move(current, "synthetic-b", 1, "Equipped", "second-transfer")
        assertEquals(first, move(original, commandId = "first-transfer"))
        assertEquals(1, ledger.items.first().size)
        assertEquals(4, ledger.items.first().single().quantity)
        assertEquals("synthetic-b", party.placements.first().single().holderProfileId)
        assertEquals("Equipped", ledger.items.first().single().itemState)
        assertEquals("Actor:synthetic-b", ledger.items.first().single().location)
        assertEquals(3L, second.resultingRevision)
        assertEquals(2, db.recordsDao().allAudit().count { it.recordType == "item-placement" })
    }

    @Test fun staleOwnershipAndCrossDomainCommandsDoNotOverwriteCurrentRecords() = runBlocking {
        val original = item()
        move(original, commandId = "global-transfer")
        rejects { move(original, "synthetic-b", null, commandId = "stale-transfer") }
        val current = requireNotNull(db.ledgerDao().getItem(original.id))
        rejects { move(current, "synthetic-b", null, commandId = "stale-placement") }
        rejects { party.saveSheet("synthetic-a", PartySheetData(), source = "Synthetic source", commandId = "global-transfer") }
        assertEquals("synthetic-a", party.placements.first().single().holderProfileId)
        assertNull(ledger.records.receipt("stale-transfer"))
    }

    @Test fun transferFailureRollsBackPlacementItemHistoryAndReceiptThenRetryWorks() = runBlocking {
        val original = item()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_transfer_failure BEFORE UPDATE ON ledger_items BEGIN SELECT RAISE(ABORT, 'Synthetic failure'); END")
        rejects { move(original, commandId = "rollback-transfer") }
        assertTrue(party.placements.first().isEmpty())
        assertEquals(original, db.ledgerDao().getItem(original.id))
        assertNull(ledger.records.receipt("rollback-transfer"))
        assertTrue(db.recordsDao().allAudit().none { it.commandId == "rollback-transfer" })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_transfer_failure")
        move(original, commandId = "rollback-transfer")
        assertEquals("synthetic-a", party.placements.first().single().holderProfileId)
    }

    @Test fun containersRejectCyclesConflictingHoldersAndImplicitTransfers() = runBlocking {
        val box = item("Synthetic box")
        val lens = item()
        move(box, commandId = "box-owner")
        party.moveItem(ItemMoveCommand(lens.id, 1, holderProfileId = "synthetic-a", containerItemId = box.id, state = "Stored", source = "Synthetic storage", commandId = "store-lens"))
        val currentBox = requireNotNull(db.ledgerDao().getItem(box.id))
        rejects { party.moveItem(ItemMoveCommand(box.id, currentBox.revision, 1, "synthetic-a", lens.id, state = "Stored", source = "Synthetic cycle")) }
        rejects { move(currentBox, "synthetic-b", 1, commandId = "silent-container-transfer") }
        val currentLens = requireNotNull(db.ledgerDao().getItem(lens.id))
        rejects { party.moveItem(ItemMoveCommand(lens.id, currentLens.revision, 1, "synthetic-b", box.id, state = "Stored", source = "Conflicting synthetic holder")) }
        assertEquals(2, ledger.items.first().size)
        assertTrue(party.placements.first().all { it.holderProfileId == "synthetic-a" })
    }

    @Test fun installationUsesSameItemAndLegacyEditsCannotBypassOwnership() = runBlocking {
        val part = item()
        val project = InventionProject("Synthetic invention")
        ledger.records.saveProject(project, source = "Synthetic project")
        party.moveItem(ItemMoveCommand(part.id, 1, installedProjectId = project.id, state = "Installed", source = "Confirmed synthetic installation", commandId = "install-part"))
        val installed = requireNotNull(db.ledgerDao().getItem(part.id))
        rejects { ledger.update(part.id, installed.revision, LedgerDraft(installed.title, owner = "Other", quantity = 1), "Bypass attempt") }
        rejects { party.changeQuantity(QuantityCommand(part.id, installed.revision, -1, "Attempt to spend installed component")) }
        assertEquals(1, ledger.items.first().size)
        assertEquals(project.id, party.placements.first().single().installedProjectId)
    }

    @Test fun unknownNegativeAndDuplicateQuantityChangesCannotDoubleSpend() = runBlocking {
        val unknown = item("Uncounted synthetic pieces", null)
        rejects { party.changeQuantity(QuantityCommand(unknown.id, 1, -1, "Unknown synthetic expense")) }
        val known = item(quantity = 2)
        move(known)
        val command = QuantityCommand(known.id, 2, -2, "Confirmed synthetic expense", "expense-once")
        val receipt = party.changeQuantity(command)
        assertEquals(receipt, party.changeQuantity(command))
        val consumed = requireNotNull(db.ledgerDao().getItem(known.id))
        assertEquals(0, consumed.quantity)
        assertEquals("Consumed", consumed.itemState)
        assertEquals("Consumed", party.placements.first().single().state)
        rejects { party.changeQuantity(command.copy(delta = -1)) }
        rejects { party.changeQuantity(QuantityCommand(known.id, 3, -1, "Second synthetic expense")) }
        assertNull(db.ledgerDao().getItem(unknown.id)?.quantity)
    }

    @Test fun explicitSourcedBaselineMakesUnknownAssignedStockEditableWithoutAssumingZero() = runBlocking {
        val unknown = item("Synthetic unknown stock", null)
        move(unknown, commandId = "assign-unknown")
        assertNull(db.ledgerDao().getItem(unknown.id)?.quantity)
        val command = QuantityConfirmationCommand(unknown.id, 2, 4, "Confirmed synthetic count", "confirm-baseline")
        val receipt = party.confirmQuantity(command)
        assertEquals(receipt, party.confirmQuantity(command))
        assertEquals(4, db.ledgerDao().getItem(unknown.id)?.quantity)
        party.changeQuantity(QuantityCommand(unknown.id, 3, -1, "Confirmed synthetic expense", "expense-after-count"))
        assertEquals(3, db.ledgerDao().getItem(unknown.id)?.quantity)
        assertEquals("synthetic-a", party.placements.first().single().holderProfileId)
        rejects { party.confirmQuantity(command.copy(quantity = 7)) }
    }

    @Test fun walletUnknownDenominationsStayUnknownAndWholeAdjustmentRollsBackOnInsufficientFunds() = runBlocking {
        party.saveWallet(PartyWallet("synthetic-wallet", "synthetic-a", "Synthetic wallet", gold = 5), source = "Confirmed synthetic opening")
        val command = CurrencyCommand("synthetic-wallet", 1, goldDelta = -2, source = "Confirmed synthetic purchase", commandId = "currency-once")
        val receipt = party.adjustCurrency(command)
        assertEquals(receipt, party.adjustCurrency(command))
        assertEquals(3L, party.wallets.first().single().gold)
        assertNull(party.wallets.first().single().copper)
        rejects { party.adjustCurrency(CurrencyCommand("synthetic-wallet", 2, copperDelta = 1, goldDelta = -1, source = "Unknown baseline")) }
        rejects { party.adjustCurrency(CurrencyCommand("synthetic-wallet", 2, goldDelta = -4, source = "Insufficient synthetic funds")) }
        rejects { party.adjustCurrency(CurrencyCommand("synthetic-wallet", 1, goldDelta = 1, source = "Stale synthetic balance")) }
        assertEquals(3L, party.wallets.first().single().gold)
        assertEquals(2L, party.wallets.first().single().revision)
    }

    @Test fun currencyOverflowAndNegativeOpeningBalancesLeaveNoReceipt() = runBlocking {
        rejects { party.saveWallet(PartyWallet("invalid-wallet", name = "Invalid", gold = -1), source = "Invalid fixture") }
        party.saveWallet(PartyWallet("synthetic-wallet", name = "Synthetic wallet", gold = 2), source = "Synthetic baseline")
        rejects { party.adjustCurrency(CurrencyCommand("synthetic-wallet", 1, goldDelta = Long.MAX_VALUE, source = "Overflow fixture", commandId = "overflow")) }
        assertNull(ledger.records.receipt("overflow"))
        assertEquals(2L, party.wallets.first().single().gold)
    }

    @Test fun checkpointPreservesUnresolvedDecisionsAndNeverRefillsState() = runBlocking {
        val checkpoint = CampaignCheckpoint("synthetic-checkpoint", "Synthetic scene", lastConfirmedAction = "Door approached", unresolvedChoice = "Roll unresolved", unknowns = listOf("Current initiative unknown"))
        val first = party.saveCheckpoint(checkpoint, source = "Confirmed synthetic checkpoint", commandId = "checkpoint-create")
        party.saveCheckpoint(checkpoint.copy(objective = "Later sourced objective"), 1, "Later source", "checkpoint-edit")
        assertEquals(first, party.saveCheckpoint(checkpoint, source = "Confirmed synthetic checkpoint", commandId = "checkpoint-create"))
        assertEquals("Roll unresolved", party.checkpoints.first().single().unresolvedChoice)
        assertEquals(3, db.recordsDao().characterProfile("synthetic-a")?.currentHp)
        rejects { party.saveCheckpoint(checkpoint, 1, "Stale checkpoint") }
    }

    @Test fun exactExternalSourceIdsPreventDuplicateRecordMappings() = runBlocking {
        val testItem = item()
        val source = ExternalRecordReference("synthetic-reference", "notion", "synthetic-external-page-id", "item", testItem.id, "https://example.invalid/private-source")
        party.saveExternalReference(source, source = "Synthetic source only")
        rejects { party.saveExternalReference(source.copy(id = "duplicate-reference"), source = "Duplicate synthetic mapping") }
        rejects { party.saveExternalReference(source.copy(id = "missing-target", externalId = "different-source", recordId = "missing"), source = "Missing target") }
        assertEquals("synthetic-external-page-id", db.partyDao().allReferences().single().externalId)
        assertEquals(1, ledger.items.first().size)
    }

    @Test fun v3BackupRoundTripsEveryTypedRowOriginalIdsUnknownsAndReceipts() = runBlocking {
        party.saveSheet("synthetic-a", PartySheetData(role = "Synthetic sheet", resources = listOf(SheetResource("Unknown slot"))), source = "Synthetic sheet source", commandId = "backup-sheet")
        val testItem = item(quantity = 3)
        move(testItem, commandId = "backup-placement")
        party.saveWallet(PartyWallet("synthetic-wallet", name = "Synthetic purse", gold = 2), source = "Synthetic opening")
        party.saveCheckpoint(CampaignCheckpoint("synthetic-checkpoint", "Synthetic checkpoint", unknowns = listOf("No inferred roll")), source = "Synthetic checkpoint source")
        party.saveExternalReference(ExternalRecordReference("synthetic-ref", "notion", "synthetic-source-id", "item", testItem.id), source = "Synthetic reference")
        val backup = ledger.export()
        val targetDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val target = CampaignLedgerRepository(targetDb)
            val preview = target.previewImport(backup)
            assertTrue(preview.conflicts.toString(), preview.valid)
            assertTrue(target.items.first().isEmpty())
            val receipt = target.restore(preview, "restore-v3")
            assertEquals(receipt, target.restore(preview, "restore-v3"))
            assertEquals(db.partyDao().allSheets(), targetDb.partyDao().allSheets())
            assertEquals(db.partyDao().allPlacements(), targetDb.partyDao().allPlacements())
            assertEquals(db.partyDao().allWallets(), targetDb.partyDao().allWallets())
            assertEquals(db.partyDao().allCheckpoints(), targetDb.partyDao().allCheckpoints())
            assertEquals(db.partyDao().allReferences(), targetDb.partyDao().allReferences())
            assertEquals(ledger.records.receipt("backup-placement"), target.records.receipt("backup-placement"))
        } finally { targetDb.close() }
    }

    @Test fun malformedOwnershipAndDuplicateSourceMappingCannotPassMigrationPreview() = runBlocking {
        val testItem = item()
        val current = campaignJson.decodeFromString<LedgerExport>(ledger.export())
        val placement = ItemPlacement(testItem.id, "synthetic-a", state = "Equipped")
        val badMirror = current.copy(records = current.records.copy(placements = listOf(placement)))
        assertFalse(party.migrationPreview(campaignJson.encodeToString(badMirror)).valid)
        val references = listOf(ExternalRecordReference("a", "notion", "same-external-id", "item", testItem.id), ExternalRecordReference("b", "notion", "same-external-id", "item", testItem.id))
        assertFalse(party.migrationPreview(campaignJson.encodeToString(current.copy(records = current.records.copy(externalReferences = references)))).valid)
        val disguisedLegacy = current.copy(schema = 2, records = current.records.copy(partySheets = listOf(PartySheetRecord("synthetic-a", campaignJson.encodeToString(PartySheetData())))))
        assertFalse(party.migrationPreview(campaignJson.encodeToString(disguisedLegacy)).valid)
        assertTrue(party.placements.first().isEmpty())
    }

    @Test fun reverseTransferIsCompensatingAndRetainsAllHistory() = runBlocking {
        val testItem = item()
        move(testItem, commandId = "structured-origin")
        val current = requireNotNull(db.ledgerDao().getItem(testItem.id))
        move(current, "synthetic-b", 1, "Equipped", "transfer-to-reverse")
        val result = party.reverseTransfer("transfer-to-reverse", 3, 2, "Confirmed synthetic reversal", "reverse-once")
        assertEquals(4L, result.resultingRevision)
        assertEquals("synthetic-a", party.placements.first().single().holderProfileId)
        assertEquals(3, db.recordsDao().allAudit().count { it.recordType == "item-placement" })
        rejects { party.reverseTransfer("structured-origin", 4, 3, "Cannot infer legacy owner") }
    }

    @Test fun zeroStockCannotHoldItemsOrImportAnAvailablePlacement() = runBlocking {
        val empty = item("Synthetic zero container", 0)
        val contents = item("Synthetic contents")
        rejects { party.moveItem(ItemMoveCommand(contents.id, 1, holderProfileId = "synthetic-a", containerItemId = empty.id,
            state = "Stored", source = "Invalid empty container", commandId = "zero-container")) }
        assertNull(ledger.records.receipt("zero-container"))
        val snapshot = campaignJson.decodeFromString<LedgerExport>(ledger.export())
        val altered = empty.copy(itemState = "Equipped", location = "Actor:synthetic-a", owner = "Synthetic player")
        val bad = snapshot.copy(items = snapshot.items.map { if (it.id == empty.id) altered else it }, events = emptyList(),
            records = snapshot.records.copy(placements = listOf(ItemPlacement(empty.id, "synthetic-a", state = "Equipped")), audit = emptyList(), receipts = emptyList()))
        assertFalse(party.migrationPreview(campaignJson.encodeToString(bad)).valid)
    }

    @Test fun nonemptyContainersCannotBeConsumedArchivedOrUsedUpInCrafting() = runBlocking {
        val box = item("Synthetic nonempty box")
        val contents = item("Synthetic stored contents")
        party.moveItem(ItemMoveCommand(contents.id, 1, holderProfileId = "synthetic-a", containerItemId = box.id,
            state = "Stored", source = "Confirmed synthetic storage", commandId = "synthetic-store"))
        val eventsBefore = db.ledgerDao().allEvents()
        val receiptsBefore = db.recordsDao().allReceipts()
        rejects { party.changeQuantity(QuantityCommand(box.id, 1, -1, "Invalid synthetic consumption", "consume-box")) }
        rejects { party.confirmQuantity(QuantityConfirmationCommand(box.id, 1, 0, "Invalid synthetic zero", "zero-box")) }
        rejects { ledger.update(box.id, 1, LedgerDraft(box.title, quantity = 1, archived = true), "Invalid synthetic archive", "archive-box") }
        val project = InventionProject("Synthetic box craft")
        ledger.records.saveProject(project, source = "Synthetic design")
        rejects { ledger.records.confirmCraft(CraftCommand(project.id, 1, listOf(MaterialConsumption(box.id, 1, 1)),
            LedgerDraft("Should not exist", quantity = 1), "Invalid synthetic construction", "craft-box")) }
        assertEquals(box, db.ledgerDao().getItem(box.id))
        assertEquals(eventsBefore, db.ledgerDao().allEvents())
        assertTrue(db.recordsDao().allReceipts().containsAll(receiptsBefore))
        assertNull(ledger.records.receipt("consume-box"))
        assertNull(ledger.records.receipt("zero-box"))
        assertNull(ledger.records.receipt("archive-box"))
        assertNull(ledger.records.receipt("craft-box"))
        assertEquals(2, ledger.items.first().size)
    }

    @Test fun installedMaterialsCannotBeConsumedByCraftAndLaterChangesPreventAutomaticReversal() = runBlocking {
        val installed = item("Synthetic installed material")
        val project = InventionProject("Synthetic attached project")
        ledger.records.saveProject(project, source = "Synthetic design")
        party.moveItem(ItemMoveCommand(installed.id, 1, installedProjectId = project.id, state = "Installed", source = "Synthetic installation"))
        rejects { ledger.records.confirmCraft(CraftCommand(project.id, 1, listOf(MaterialConsumption(installed.id, 2, 1)),
            LedgerDraft("Should not exist", quantity = 1), "Invalid synthetic construction", "spend-installed")) }
        assertNull(ledger.records.receipt("spend-installed"))
        assertEquals("Installed", db.ledgerDao().getItem(installed.id)?.itemState)
        val transferable = item("Synthetic transfer history")
        move(transferable, commandId = "origin-transfer")
        move(requireNotNull(db.ledgerDao().getItem(transferable.id)), "synthetic-b", 1, commandId = "older-transfer")
        move(requireNotNull(db.ledgerDao().getItem(transferable.id)), "synthetic-a", 2, commandId = "later-transfer")
        rejects { party.reverseTransfer("older-transfer", 4, 3, "Cannot undo later changes", "unsafe-reversal") }
        rejects { party.reverseTransfer("older-transfer", 3, 2, "Stale original revisions", "stale-reversal") }
        assertNull(ledger.records.receipt("unsafe-reversal"))
        assertEquals(4L, db.ledgerDao().getItem(transferable.id)?.revision)
    }

    @Test fun atomicItemCurrencyExchangeRetriesOriginalOutcomeAndRejectsChangedPayload() = runBlocking {
        val stock = item("Synthetic exchange stock", 2)
        party.saveWallet(PartyWallet("exchange-wallet", name = "Synthetic wallet", gold = 5), source = "Synthetic opening")
        val command = PartyExchangeCommand(quantities = listOf(QuantityCommand(stock.id, 1, -1, "Ignored child source")),
            currency = listOf(CurrencyCommand("exchange-wallet", 1, goldDelta = -2, source = "Ignored child source")),
            source = "Confirmed synthetic exchange", commandId = "exchange-once")
        val receipt = party.commitExchange(command)
        assertEquals(receipt, party.commitExchange(command))
        assertEquals(1, db.ledgerDao().getItem(stock.id)?.quantity)
        assertEquals(3L, party.wallets.first().single().gold)
        assertEquals(2, campaignJson.decodeFromString<PartyExchangeOutcome>(receipt.outcomeJson).childReceipts.size)
        rejects { party.commitExchange(command.copy(currency = listOf(command.currency.single().copy(goldDelta = -3)))) }
        assertEquals(1, db.ledgerDao().getItem(stock.id)?.quantity)
        val preview = party.migrationPreview(ledger.export())
        assertTrue(preview.conflicts.toString(), preview.valid)
    }

    @Test fun atomicExchangeFailureRollsBackItemCurrencyChildAndParentReceipts() = runBlocking {
        val stock = item("Synthetic rollback stock", 2)
        party.saveWallet(PartyWallet("exchange-wallet", name = "Synthetic wallet", gold = 1), source = "Synthetic opening")
        val beforeEvents = db.ledgerDao().allEvents()
        val beforeReceipts = db.recordsDao().allReceipts()
        val command = PartyExchangeCommand(quantities = listOf(QuantityCommand(stock.id, 1, -1, "Synthetic input")),
            currency = listOf(CurrencyCommand("exchange-wallet", 1, goldDelta = -2, source = "Synthetic input")),
            source = "Confirmed synthetic insufficient exchange", commandId = "failed-exchange")
        rejects { party.commitExchange(command) }
        assertEquals(2, db.ledgerDao().getItem(stock.id)?.quantity)
        assertEquals(1L, party.wallets.first().single().gold)
        assertEquals(beforeEvents, db.ledgerDao().allEvents())
        assertEquals(beforeReceipts, db.recordsDao().allReceipts())
        assertNull(ledger.records.receipt("failed-exchange"))
    }

    @Test fun newItemPurchaseCreatesExactlyOneIdAndInsufficientFundsLeaveNoItem() = runBlocking {
        party.saveWallet(PartyWallet("purchase-wallet", name = "Synthetic purse", gold = 5), source = "Synthetic opening")
        val command = PartyExchangeCommand(creations = listOf(LedgerDraft("Synthetic purchased item", quantity = 1)),
            currency = listOf(CurrencyCommand("purchase-wallet", 1, goldDelta = -2, source = "Synthetic child")),
            source = "Confirmed synthetic purchase", commandId = "synthetic-purchase", creationHolderProfileIds = listOf("synthetic-a"))
        val receipt = party.commitExchange(command)
        assertEquals(receipt, party.commitExchange(command))
        assertEquals(1, ledger.items.first().size)
        val purchaseOutcome = campaignJson.decodeFromString<PartyExchangeOutcome>(receipt.outcomeJson)
        assertEquals(ledger.items.first().single().id, purchaseOutcome.childReceipts.first { it.kind == "CREATE" }.recordId)
        assertEquals("synthetic-a", party.placements.first().single().holderProfileId)
        assertEquals(2L, ledger.items.first().single().revision)
        assertEquals(3L, party.wallets.first().single().gold)
        val failed = command.copy(commandId = "insufficient-new-purchase", creations = listOf(LedgerDraft("Must roll back", quantity = 1)),
            currency = listOf(CurrencyCommand("purchase-wallet", 2, goldDelta = -4, source = "Synthetic child")))
        rejects { party.commitExchange(failed) }
        assertEquals(1, ledger.items.first().size)
        assertEquals(3L, party.wallets.first().single().gold)
        assertNull(ledger.records.receipt("insufficient-new-purchase"))
        val preview = party.migrationPreview(ledger.export())
        assertTrue(preview.conflicts.toString(), preview.valid)
    }

    @Test fun exhaustedStoredResourcesDetachFromContainerForQuantityBaselineAndCrafting() = runBlocking {
        for (path in listOf("quantity", "baseline", "craft")) {
            val box = item("Synthetic $path container")
            val resource = item("Synthetic $path resource")
            party.moveItem(ItemMoveCommand(resource.id, 1, holderProfileId = "synthetic-a", containerItemId = box.id,
                state = "Stored", source = "Synthetic storage", commandId = "$path-store"))
            when (path) {
                "quantity" -> party.changeQuantity(QuantityCommand(resource.id, 2, -1, "Synthetic resolved use", "deplete-quantity"))
                "baseline" -> party.confirmQuantity(QuantityConfirmationCommand(resource.id, 2, 0, "Synthetic confirmed empty count", "deplete-baseline"))
                else -> {
                    val project = InventionProject("Synthetic material depletion")
                    ledger.records.saveProject(project, source = "Synthetic design")
                    ledger.records.confirmCraft(CraftCommand(project.id, 1, listOf(MaterialConsumption(resource.id, 2, 1)),
                        LedgerDraft("Synthetic crafted result", quantity = 1), "Synthetic resolved craft", "deplete-craft"))
                }
            }
            val placement = requireNotNull(db.partyDao().placement(resource.id))
            assertEquals("Consumed", placement.state)
            assertNull(placement.containerItemId)
            assertEquals("Actor:synthetic-a", db.ledgerDao().getItem(resource.id)?.location)
            move(box, "synthetic-b", commandId = "move-empty-$path-container")
        }
        val preview = party.migrationPreview(ledger.export())
        assertTrue(preview.conflicts.toString(), preview.valid)
    }

    @Test fun v3RestoreFailureRollsBackExistingParentTablesAndCanRetry() = runBlocking {
        party.saveSheet("synthetic-a", PartySheetData(role = "Synthetic"), source = "Synthetic source")
        item()
        val backup = ledger.export()
        val targetDb = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val target = CampaignLedgerRepository(targetDb)
            val preview = target.previewImport(backup)
            assertTrue(preview.conflicts.toString(), preview.valid)
            targetDb.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_party_restore_failure BEFORE INSERT ON party_sheets BEGIN SELECT RAISE(ABORT, 'Synthetic failure'); END")
            rejects { target.restore(preview, "failed-v3-restore") }
            assertTrue(target.items.first().isEmpty())
            assertTrue(target.records.characterProfiles.first().isEmpty())
            assertNull(target.records.receipt("failed-v3-restore"))
            targetDb.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_party_restore_failure")
            target.restore(preview, "failed-v3-restore")
            assertEquals(1, target.party.sheets.first().size)
        } finally { targetDb.close() }
    }
}
