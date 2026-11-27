package com.yazsras.astravar.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.yazsras.astravar.data.*
import com.yazsras.astravar.rules.reforge.AttackRules
import com.yazsras.astravar.rules.reforge.CharacterCalculator
import com.yazsras.astravar.rules.reforge.Stat
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

/** All ledger editors receive the proven original record and its reviewed revision. */
@Composable internal fun LegacyCharacterSources(repository: CampaignLedgerRepository, local: ReforgeRepository,
    saved: ReforgeSavedCharacter, ability: String?, close: () -> Unit) {
    val actor = saved.character
    val projection = remember(actor) { CharacterCalculator.calculate(actor) }
    var source by remember(actor.id,saved.revision) { mutableStateOf<ReforgeLegacySnapshot?>(null) }
    var original by remember(actor.id,saved.revision) { mutableStateOf<ReforgeLegacySnapshot?>(null) }
    var message by remember(actor.id,saved.revision) { mutableStateOf("Reading source identity…") }
    var refresh by remember(actor.id) { mutableIntStateOf(0) }
    var references by rememberSaveable(actor.id) { mutableStateOf(false) }
    var statusDraft by rememberSaveable(actor.id) { mutableStateOf<String?>(null) }
    var abilityDraft by rememberSaveable(actor.id) { mutableStateOf<String?>(null) }
    var stat by rememberSaveable(actor.id,ability) { mutableStateOf(ability) }
    LaunchedEffect(actor.id,saved.revision,refresh) {
        try {
            val identity=local.inspectCampaignIdentity(repository,saved)
            source=identity?.profile?.id?.let { repository.readCharacterSnapshot(it) }
            original=saved.origin.sourceSnapshotJson?.takeIf { raw ->
                saved.origin.sourceProfileId==actor.id && reforgeHash(raw)==saved.origin.sourceFingerprint
            }?.let { reforgeJson.decodeFromString<ReforgeLegacySnapshot>(it) }?.takeIf { it.profile.id==actor.id }
            message=if(source==null) "No separate source record is linked to this actor." else ""
        } catch(e:CancellationException){throw e}
        catch(_:Exception){source=null;original=null;message="The source identity needs review before its record can be opened."}
    }
    val linked=source
    if(references){
        RebuildToolSheet("Equipment and source references",{references=false}) {
            actor.items.forEach { item ->
                actor.itemDefinitions.firstOrNull{it.id==item.definitionId}?.let { def->
                    Text(def.name)
                    def.effects.forEach { effect-> Text("${effect.name} · ${effect.source.name}") }
                }
            }
            val documentIds=linked?.referenceMetadata.orEmpty().map { it.id }.toSet()
            if(documentIds.isNotEmpty()) CampaignReferenceLibraryV21(repository,documentIds)
            else Text("No linked historical documents.")
        }
        return
    }
    PlayerSheet("Character source evidence",close) {
        Text(actor.name)
        Text("Playable revision ${saved.revision} · ${saved.origin.label}")
        Text("Level ${actor.level} · AC ${projection.armorClass.value ?: "Unknown"}")
        if(actor.attacks.isNotEmpty())PlayerDisclosure("Attack sources") {
            actor.attacks.forEach { attack ->
                val result=remember(actor,attack.id){ runCatching { AttackRules.preview(actor,attack.id) } }
                Text(attack.name+" · "+(result.getOrNull()?.attackBonus?.value?.let(::reforgeSigned)
                    ?: result.exceptionOrNull()?.message ?: "Unknown"))
                result.getOrNull()?.attackBonus?.explanation?.forEach { line -> Text(line.label+" · "+(line.value ?: "Unknown")) }
                attack.recordedSource?.let { record -> Text(record.sheetSource+" · revision "+record.sheetRevision) }
            }
        }
        if(actor.spells.isNotEmpty() || actor.spellcastingClassLevel!=null) {
            Text("Spell attack ${projection.spellAttack.value?.let(::reforgeSigned) ?: "Unknown"} · Spell save DC ${projection.spellSaveDc.value ?: "Unknown"}")
        }
        stat?.let { key->projection.abilities[Stat.valueOf(key)]?.let { a->
            Text("$key · base ${a.permanent ?: "Unknown"} · effective ${a.effective.value ?: "Unknown"}")
            a.effective.explanation.forEach { Text("${it.label} · ${it.value ?: "Unknown"}") }
        } }
        saved.origin.warnings.forEach { Text(it) }
        if (message.isNotEmpty()) Text(message)
        if(linked!=null) {
            PlayerDisclosure("Original campaign record",initiallyOpen=true) {
                val profile=linked.profile
                Text("Linked profile · ${profile.id}")
                Text("Source profile revision ${profile.revision}")
                if(profile.source.isNotBlank() && !profile.source.startsWith("Reviewed name-only playable identity\n"))Text(profile.source)
                original?.let { historical->
                    Text("Reviewed snapshot · profile revision ${historical.profile.revision}")
                    stat?.let { key-> historical.abilities.firstOrNull{it.key==key}?.let { at->
                        Text("Reviewed $key · ${at.score ?: "Unknown"} · revision ${at.revision}")
                    } }
                }
                PlayerTextButton({statusDraft=reforgeJson.encodeToString(profile)}){Text("Edit source status")}
                val keys=if(ability!=null) listOf(ability) else Stat.entries.map{it.name}
                keys.forEach { key->
                    val recorded=linked.abilities.firstOrNull { it.key==key }
                    Text("Source $key · ${recorded?.score ?: "Unknown"}")
                    recorded?.let {
                        Text("Ability revision ${it.revision}")
                        if(it.source.isNotBlank())Text(it.source)
                    }
                    PlayerTextButton({stat=key;abilityDraft=recorded?.let { reforgeJson.encodeToString(it) } ?: "null"}){Text("Edit source $key")}
                }
            }
        }
        if(actor.items.isNotEmpty() || !linked?.referenceMetadata.isNullOrEmpty()) PlayerTextButton({references=true}){Text("Equipment and source references")}
    }
    if(linked!=null && (statusDraft!=null || abilityDraft!=null)) {
        val profiles by repository.records.characterProfiles.collectAsState(initial=listOf(linked.profile))
        val abilities by repository.records.characterAbilities.collectAsState(initial=linked.abilities)
        val latest=profiles.firstOrNull{it.id==linked.profile.id}
        statusDraft?.let { snapshot -> CharacterProfileDialog(reforgeJson.decodeFromString(snapshot),latest,
            repository.records,linked.profile.id,{statusDraft=null},{statusDraft=null;refresh++}) }
        abilityDraft?.let { snapshot ->stat?.let { key->CharacterAbilityDialog(key,
            if(snapshot=="null")null else reforgeJson.decodeFromString(snapshot),abilities.firstOrNull{it.profileId==linked.profile.id && it.key==key},
            latest!=null,linked.profile.id,repository.records,{abilityDraft=null},{abilityDraft=null;refresh++},showArtwork=false) } }
    }
}
