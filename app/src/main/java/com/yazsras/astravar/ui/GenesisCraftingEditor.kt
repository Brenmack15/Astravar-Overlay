package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

private data class GenesisCraftContainerChoice(val id: String?, val label: String)

private fun genesisCraftContainerLabel(character: ReforgeCharacter, instance: ItemInstance): String {
    val name = character.itemDefinitions.firstOrNull { it.id == instance.definitionId }?.name ?: "Unnamed inventory item"
    val ambiguous = character.items.count { item -> character.itemDefinitions.firstOrNull { it.id == item.definitionId }?.name == name } > 1
    return if (ambiguous) "$name · ${instance.id}" else name
}

/** A resolved craft has one frozen review, one command and one output identity across both views. */
@Composable internal fun GenesisCraftingEditor(environment: GenesisConnectedEnvironment, project: InventionProject,
    onDismiss: () -> Unit, onSaved: (GenesisCraftingOutcome) -> Unit, onUseSourceOnly: (() -> Unit)? = null) {
    val requirementsState by remember(environment.campaign, project.id) {
        environment.campaign.records.requirementsFor(project.id).map { true to it }
    }.collectAsState(initial = false to emptyList<MaterialRequirement>())
    val itemState by remember(environment.campaign) { environment.campaign.items.map { true to it } }
        .collectAsState(initial = false to emptyList<LedgerItem>())
    val actorsState by remember(environment.authority) { environment.authority.actorAssociations.map { true to it } }
        .collectAsState(initial = false to emptyList<GenesisActorAuthority>())
    val playableState by remember(environment.local) { environment.local.observeAll().map { true to it } }
        .collectAsState(initial = false to emptyList<ReforgeSavedCharacter>())
    val requirements = requirementsState.second
    val items = itemState.second
    val actors = actorsState.second
    val campaigns by environment.authority.campaigns.collectAsState(initial = emptyList())
    val playable = playableState.second.filterNot { it.character.archived }
    val ownerProfiles = playable.map { CharacterProfile(it.character.name, id = it.character.id, level = it.character.level) }
    val loaded = requirementsState.first && itemState.first && actorsState.first && playableState.first
    var step by rememberSaveable(project.id) { mutableIntStateOf(0) }
    var title by rememberSaveable(project.id) { mutableStateOf("") }
    var quantity by rememberSaveable(project.id) { mutableStateOf("1") }
    val descriptionDraft = rememberReforgeDraft("", String.serializer())
    var description by descriptionDraft
    val sourceDraft = rememberReforgeDraft(project.source, String.serializer())
    var source by sourceDraft
    val selectionsDraft = rememberReforgeDraft(emptyMap<String, String>(), MapSerializer(String.serializer(), String.serializer()))
    var selections by selectionsDraft
    var ownerId by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var containerId by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var state by rememberSaveable(project.id) { mutableStateOf("Carried") }
    var ownerQuery by rememberSaveable(project.id) { mutableStateOf("") }
    var membershipId by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var commandId by rememberSaveable(project.id) { mutableStateOf(UUID.randomUUID().toString()) }
    // One atomic bundle value also protects a stale activity snapshot after the durable hint clears.
    var pendingJson by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    // Approval and the mechanical snapshot never restore independently after recreation.
    var reviewed by remember { mutableStateOf<GenesisCraftingReview?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var recoveryLoaded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<GenesisPendingCraft?>(null) }
    var savedOutcome by remember { mutableStateOf<GenesisCraftingOutcome?>(null) }
    var recoveryChecked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draftsValid = selectionsDraft.canSave && descriptionDraft.canSave && sourceDraft.canSave
    val selectedOwner = playable.firstOrNull { it.character.id == ownerId }
    val ownerConnected = ownerId != null && actors.any { it.actorId == ownerId && it.playable }
    val selectedMaterialIds = requirements.filter { it.id in selections }.mapNotNull { it.itemId }.toSet()
    val containers = selectedOwner?.character?.items.orEmpty().filter {
        it.quantity > 0 && it.state in setOf(ItemState.CARRIED, ItemState.STORED) && it.id !in selectedMaterialIds
    }
    val dirty = savedOutcome == null && (title.isNotBlank() || quantity != "1" || description.isNotBlank() ||
        selections.isNotEmpty() || ownerId != null || containerId != null || source != project.source)
    suspend fun recoverPending() {
        recoveryChecked = false
        val restored = pendingJson?.let(::decodeGenesisPendingCraft)
        val recovery = GenesisCraftSubmission.recover(environment, project.id, restored)
        pending = recovery.pending
        pendingJson = recovery.pending?.let(::encodeGenesisPendingCraft)
        savedOutcome = recovery.outcome
        recoveryChecked = true; recoveryLoaded = true
    }
    LaunchedEffect(environment.authority, environment.preferences, project.id) {
        busy = true
        try { recoverPending() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "The previous craft outcome could not be verified. Preserve this draft and retry the lookup." }
        finally { busy = false }
    }
    LaunchedEffect(step, reviewed) { if (step == 2 && reviewed == null) step = 1 }
    fun edit(section: Int) {
        if (!busy && recoveryLoaded && pending == null) { step = section; reviewed = null; acknowledged = false; error = ""; commandId = UUID.randomUUID().toString() }
    }
    fun prepareReview() {
        if (busy || !loaded || !draftsValid || !recoveryLoaded || pending != null) return
        error = ""; acknowledged = false
        try {
            require(title.isNotBlank()) { "Enter the crafted item's name." }
            val count = quantity.toIntOrNull() ?: error("Enter a whole output quantity.")
            require(count > 0) { "Output quantity must be positive." }
            require(source.isNotBlank()) { "Record the confirmed scene, ruling or source for this craft." }
            val recipient = requireNotNull(ownerId) { "Choose the output owner explicitly." }
            require(ownerConnected && selectedOwner != null) { "Connect and review this actor before using its inventory." }
            require(selections.isNotEmpty() && selections.keys.all { id -> requirements.any { it.id == id } }) { "Select current linked requirements and confirm the actual amounts consumed." }
            val uses = requirements.filter { it.id in selections }.map { requirement ->
                val item = items.firstOrNull { it.id == requirement.itemId } ?: error("Link ${requirement.label} to its actual inventory item first.")
                val amount = selections.getValue(requirement.id).toIntOrNull() ?: error("Enter the actual quantity for ${requirement.label}.")
                require(amount > 0) { "Actual consumed quantities must be positive." }
                MaterialConsumption(item.id, item.revision, amount)
            }.groupBy { it.itemId }.map { (_, rows) ->
                val total = rows.sumOf { it.quantity.toLong() }
                require(total <= Int.MAX_VALUE) { "Consumed quantity is too large." }
                rows.first().copy(quantity = total.toInt())
            }
            val output = LedgerDraft(title.trim(), quantity = count, description = description,
                itemState = state, evidenceStatus = "Confirmed", source = source)
            val selectedContainer = containerId
            val resolvedSource = source
            busy = true
            scope.launch {
                try {
                    reviewed = environment.authority.crafting.review(project.id, uses, output, recipient, selectedContainer, resolvedSource)
                    commandId = UUID.randomUUID().toString(); step = 2
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "The craft could not be reviewed. No materials were consumed." }
                finally { busy = false }
            }
        } catch (e: Exception) { error = e.message ?: "Check the selected materials and destination." }
    }
    fun finishSaved() {
        val token = pending ?: return
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try {
                val outcome = GenesisCraftSubmission.acknowledge(environment, token)
                // Keep the bundle token until this surface is disposed; an old snapshot must recover.
                onSaved(outcome)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "The saved craft could not be verified. Retry the lookup before continuing." }
            finally { busy = false }
        }
    }
    if (!recoveryLoaded || pending != null || pendingJson != null) {
        val outcome = savedOutcome
        PlayerSheet(if (outcome != null) "Craft saved" else "Check previous craft", onDismiss = {
            if (outcome != null) finishSaved() else onDismiss()
        }, dirty = outcome == null && dirty, dismissEnabled = !busy, actions = {
            when {
                outcome != null -> PlayerButton(enabled = !busy, onClick = ::finishSaved) { Text("Return to invention") }
                recoveryChecked && pending != null -> PlayerButton(enabled = !busy, onClick = {
                    val token = pending ?: return@PlayerButton
                    busy = true; error = ""
                    scope.launch {
                        try {
                            val committed = GenesisCraftSubmission.reviewAgain(environment, token)
                            if (committed != null) savedOutcome = committed
                            else {
                                pending = null; pendingJson = null; reviewed = null; acknowledged = false
                                commandId = UUID.randomUUID().toString(); step = 1
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { recoveryChecked = false; error = e.message ?: "The pending craft could not be resolved." }
                        finally { busy = false }
                    }
                }) { Text("Review draft again") }
                else -> PlayerButton(enabled = !busy, onClick = {
                    busy = true; error = ""
                    scope.launch {
                        try { recoverPending() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "The previous craft outcome could not be verified." }
                        finally { busy = false }
                    }
                }) { Text("Check saved outcome") }
            }
        }) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("genesis-craft-error"))
            if (outcome != null) {
                val recordedOwner = outcome.actors.singleOrNull { saved -> saved.character.items.any { it.id == outcome.outputId } }?.character
                val recordedItem = recordedOwner?.items?.singleOrNull { it.id == outcome.outputId }
                val recordedContainer = recordedItem?.containerId?.let { id -> recordedOwner?.items?.singleOrNull { it.id == id } }
                val containerName = recordedContainer?.let { container -> recordedOwner?.itemDefinitions?.singleOrNull { it.id == container.definitionId }?.name }
                Text("${outcome.sourceOutput.quantity} × ${outcome.sourceOutput.title}", style = MaterialTheme.typography.titleLarge)
                Text("The materials were deducted and the crafted item was added to ${outcome.sourceOutput.owner}'s inventory. Reopening this result does not consume materials again.", color = RebuildPalette.Muted)
                GenesisReviewValue("Recorded owner", outcome.sourceOutput.owner)
                GenesisReviewValue("Recorded location", when (recordedItem?.state) {
                    ItemState.CARRIED -> "Carried"
                    ItemState.STORED -> "Stored"
                    else -> "Unknown"
                })
                GenesisReviewValue("Recorded container", if (recordedItem?.containerId == null) "No container" else containerName ?: "Unavailable in the recorded result")
                Text("This shows where the item was saved. Later edits or transfers remain intact.", color = RebuildPalette.Muted)
            } else if (recoveryChecked) {
                Text("No completed craft was found for this attempt.", style = MaterialTheme.typography.titleMedium)
                Text("Materials were not consumed by this command. Review the current project, quantities and output again before confirming.", color = RebuildPalette.Muted)
            } else Text("Checking whether the previous craft was saved before you can record another. Nothing is submitted again automatically.", color = RebuildPalette.Muted)
        }
        return
    }
    val frozen = reviewed
    PlayerSheet(if (frozen == null) "Record connected craft" else "Review connected craft", onDismiss,
        dirty = dirty, dismissEnabled = !busy, scrollResetKey = Triple(step, frozen?.fingerprint, error), actions = {
            if (step > 0) PlayerTextButton(enabled = !busy, onClick = { edit(step - 1) }) { Text("Back") }
            when (step) {
                0 -> PlayerOutlinedButton(enabled = loaded && draftsValid && selections.isNotEmpty() && !busy, onClick = { edit(1) }) { Text("Choose output") }
                1 -> PlayerButton(enabled = loaded && draftsValid && !busy && title.isNotBlank() && ownerConnected && source.isNotBlank(), onClick = ::prepareReview) { Text("Review connected craft") }
                2 -> PlayerButton(enabled = frozen != null && acknowledged && !busy, modifier = Modifier.testTag("genesis-confirm-craft"), onClick = {
                    val review = frozen ?: return@PlayerButton
                    if (!busy) {
                        busy = true; error = ""
                        val token = GenesisPendingCraft(project.id, review.fingerprint, commandId)
                        pendingJson = encodeGenesisPendingCraft(token); pending = token; recoveryChecked = false
                        scope.launch {
                            try {
                                savedOutcome = GenesisCraftSubmission.submit(environment, review, token)
                                recoveryChecked = true
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                error = e.message ?: "The craft outcome needs to be checked before continuing."
                                try { recoverPending() }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (lookup: Exception) { error = lookup.message ?: "The craft outcome could not be verified. Preserve the pending command." }
                            }
                            finally { busy = false }
                        }
                    }
                }) { Text("Confirm resolved craft") }
            }
        }) {
        Text(frozen?.project?.title ?: project.title, style = MaterialTheme.typography.titleLarge)
        Text("Record an already resolved craft. Reviewing makes no changes and does not resolve checks or advance campaign time.", color = RebuildPalette.Muted)
        PlayerTabs(listOf("Materials", "Output", "Review"), listOf("Materials", "Output", "Review")[step], { section ->
            if (!busy) { if (section == "Review") prepareReview() else edit(if (section == "Materials") 0 else 1) }
        })
        ReforgeDraftRecovery(selectionsDraft, descriptionDraft, sourceDraft)
        if (!loaded || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("genesis-craft-error"))
        when (step) {
            0 -> {
                Text("Select only the materials actually consumed. Proposed recipe quantities remain editable.", color = RebuildPalette.Muted)
                if (loaded && requirements.isEmpty()) Text("Add linked material requirements from the invention before recording this craft.")
                requirements.forEach { requirement ->
                    val item = items.firstOrNull { it.id == requirement.itemId }
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(requirement.label, style = MaterialTheme.typography.titleMedium)
                        Text("${requirement.quantity?.toString() ?: "Unknown"} proposed · ${item?.quantity?.toString() ?: "Unknown"} available", color = RebuildPalette.Muted)
                        Toggle("Consume ${requirement.label}", requirement.id in selections, enabled = !busy && loaded) { checked ->
                            selections = if (checked) selections + (requirement.id to requirement.quantity?.toString().orEmpty()) else selections - requirement.id
                        }
                        if (requirement.id in selections) Field("Actual quantity: ${requirement.label}", selections[requirement.id].orEmpty(),
                            { selections = selections + (requirement.id to it) }, enabled = !busy)
                        if (item == null) Text("This requirement needs an inventory link before it can be consumed.", color = RebuildPalette.Muted)
                    }
                }
                if (onUseSourceOnly != null) PlayerDisclosure("Historical source-only workflow") {
                    Text("Use the original ledger workflow when the output should remain a source record. Connected inventory materials still require this connected review.", color = RebuildPalette.Muted)
                    PlayerTextButton(enabled = !busy && !dirty, onClick = onUseSourceOnly) { Text("Record a source-only craft") }
                    if (dirty) Text("Cancel this draft before switching workflows.", color = RebuildPalette.Muted)
                }
            }
            1 -> {
                Field("Crafted item name", title, { title = it }, enabled = !busy)
                Field("Output quantity", quantity, { quantity = it }, enabled = !busy)
                Field("Output description", description, { description = it }, single = false, enabled = !busy)
                Field("Confirmed scene or source", source, { source = it }, single = false, enabled = !busy)
                Text("Output owner", style = MaterialTheme.typography.titleMedium)
                Text(selectedOwner?.character?.name ?: "Choose an owner", color = RebuildPalette.GoldBright)
                PlayerSearch(ownerQuery, { if (!busy) ownerQuery = it }, "Find an output owner")
                val candidates = playable.filter { ownerQuery.isBlank() || it.character.name.contains(ownerQuery.trim(), true) }
                candidates.take(40).forEach { saved ->
                    val association = actors.firstOrNull { it.actorId == saved.character.id }
                    val ownerContext = genesisOwnerLabel(ownerProfiles.single { it.id == saved.character.id }, ownerProfiles, actors, campaigns)
                    if (association?.playable == true) PlayerEntry(saved.character.name, "$ownerContext · Connected inventory", "Emblem",
                        { if (!busy) { ownerId = saved.character.id; containerId = null } }, trailing = if (ownerId == saved.character.id) "Selected" else null)
                    else PlayerEntry("Review connection for ${saved.character.name}", ownerContext, "Emblem", { if (!busy) membershipId = saved.character.id })
                }
                if (loaded && playable.isEmpty()) Text("Create and review a playable character before assigning a connected craft output.", color = RebuildPalette.Muted)
                if (ownerId != null && !ownerConnected) Text("This owner is unavailable or not connected. Review the relationship or choose another owner.", color = MaterialTheme.colorScheme.error)
                if (ownerConnected && selectedOwner != null) {
                    val options = listOf(GenesisCraftContainerChoice(null, "No container")) + containers.map { instance ->
                        GenesisCraftContainerChoice(instance.id, genesisCraftContainerLabel(selectedOwner.character, instance))
                    }
                    val selected = options.firstOrNull { it.id == containerId } ?: GenesisCraftContainerChoice(containerId, "Container unavailable")
                    Choices("Output container (optional)", options, selected, display = { it.label }, enabled = !busy) { choice ->
                        containerId = choice.id; if (choice.id != null) state = "Stored"
                    }
                    Choices("Output location", listOf("Carried", "Stored"), state, enabled = !busy && containerId == null) { state = it }
                    Text("The output has no configured mechanical effects. Equip, attune or review effects separately after it is saved.", color = RebuildPalette.Muted)
                }
            }
            2 -> if (frozen != null) {
                val owner = frozen.actors.single { it.authority.actorId == frozen.outputOwnerActorId }
                GenesisReviewSection("Materials", { edit(0) }) {
                    frozen.materials.forEach { material ->
                        val materialOwner = material.authority?.ownerActorId?.let { id -> frozen.actors.firstOrNull { it.authority.actorId == id }?.profile?.name } ?: material.item.owner
                        val ambiguous = frozen.materials.count { it.item.title == material.item.title && it.item.owner == material.item.owner } > 1
                        GenesisReviewValue(material.item.title + if (ambiguous) " · ${material.item.id}" else "",
                            "$materialOwner · ${material.use.quantity} consumed · ${material.item.quantity} → ${requireNotNull(material.item.quantity) - material.use.quantity}")
                    }
                }
                GenesisReviewSection("Output", { edit(1) }) {
                    GenesisReviewValue("Item", "${frozen.output.quantity} × ${frozen.output.title}")
                    GenesisReviewValue("Owner", owner.profile.name)
                    GenesisReviewValue("Location", frozen.output.itemState)
                    val container = owner.playable.character.items.firstOrNull { it.id == frozen.outputContainerItemId }
                    GenesisReviewValue("Container", container?.let { instance -> genesisCraftContainerLabel(owner.playable.character, instance) }.orEmpty(), "No container")
                    GenesisReviewValue("Source", frozen.source)
                }
                Text("Confirmation deducts these exact materials and adds the crafted item to the selected owner's inventory. If the project or inventory changes first, review it again.", color = RebuildPalette.Muted)
                Toggle("I confirm this craft is resolved and these quantities are correct", acknowledged, enabled = !busy) { acknowledged = it }
            }
        }
    }
    membershipId?.let { id -> GenesisActorMembershipEditor(environment, id, { membershipId = null }) }
}
