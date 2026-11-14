package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.R
import com.yazsras.astravar.rules.*
import com.yazsras.astravar.rules.reforge.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Armor configuration belongs to this character. Opening a view never adopts or changes rules. */
@Composable internal fun GenesisArmorFeatures(
    character: ReforgeCharacter,
    onSave: (ReforgeCharacter, String) -> Unit
) {
    val armor = character.armor ?: return
    val profile = armor.campaign.profile
    val runtime = remember(character) { runCatching { GenesisArmorRules.runtime(character) }.getOrNull() }
    var editor by rememberSaveable(character.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("genesis-armor-features"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerArtBanner("ASTRAVAR", "Armor features", "Accepted engineering, damage and upgrades", R.drawable.astravar_armor_front)
        Text("Ability scores and proficiency come from your character sheet. Armor rules below apply only to armor actions.", color = RebuildPalette.Muted)
        runtime?.let {
            Text("Armor attack ${reforgeSigned(it.profile.attack)} · Internal save DC ${it.profile.saveDc}", color = RebuildPalette.Ice)
            Text("${it.profile.base} Force · ${it.profile.selector} per active element", style = MaterialTheme.typography.bodyMedium)
        }
        PlayerEntry("Armor damage", "Shared defaults and sourced elemental formulas", "Weapon", { editor = "damage" })
        PlayerEntry("Gauntlet mechanics", profile.melee?.let { "${if (it.approved) "Confirmed" else "Needs review"} · ${it.force} Force" }
            ?: "Configure accepted melee and Guardian values", "Weapon", { editor = "melee" })
        PlayerEntry("Engineering upgrades", "Calibration, internal DC and confirmed capabilities", "Core", { editor = "upgrades" })
        PlayerDisclosure("Armor rules & sources") {
            Text(armor.source, color = RebuildPalette.Muted)
            armor.configurationSources.forEach { (section, source) -> Text("$section · $source", color = RebuildPalette.Muted) }
            profile.melee?.let { Text("Gauntlets · ${it.sourceReference}", color = RebuildPalette.Muted) }
            profile.elementDamageProfiles.forEach { (type, value) ->
                Text("${type.displayName()} · ${value.sourceReference}${if (value.approved) "" else " · unconfirmed"}", color = RebuildPalette.Muted)
            }
            Text("Past attacks and loaded spells keep their original rules. Changes here affect future actions.", color = RebuildPalette.Muted)
            Text("Discharge retains the configured inherited 3× primary Force dice, 60-foot range, 15-foot radius and ten-minute in-world recovery. The conflicting later armor reference has not been substituted.", color = RebuildPalette.GoldBright)
        }
    }
    editor?.let { mode -> key(character.id, mode) {
        GenesisArmorConfigurationEditor(character, mode, { editor = null }, onSave)
    } }
}

private fun DamageType.displayName() = name.lowercase().replaceFirstChar(Char::titlecase)
private val configurableArmorUpgrades = listOf(Upgrade.ADVANCED_ELEMENTS, Upgrade.DUAL_RESONANCE, Upgrade.DUAL_STORAGE)
private fun Upgrade.displayName() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

/** Kept separate from UI so invalid drafts and preservation can be tested directly. */
internal fun genesisArmorFeatureFields(profile: Profile, section: String, damageType: DamageType? = null): Map<String, String> = when (section) {
    "damage" -> if (damageType == null) mapOf("base" to profile.baseDamageDiceCount.toString(),
        "selector" to profile.elementalSelectorDiceCount.toString(), "source" to "")
    else {
        val explicit = profile.elementDamageProfiles[damageType]
        val inherited = if (damageType == DamageType.FORCE) profile.base else profile.selectorFor(damageType)
        val dice = explicit?.dice ?: inherited
        mapOf("count" to dice.count.toString(), "sides" to dice.sides.toString(), "flat" to dice.flat.toString(),
            "source" to explicit?.sourceReference.orEmpty(), "approved" to (explicit?.approved == true).toString(), "inherit" to "false")
    }
    "melee" -> profile.melee.let { melee -> mapOf(
        "attack" to melee?.attackBonus?.toString().orEmpty(), "guardian" to melee?.guardianAttackBonus?.toString().orEmpty(),
        "count" to melee?.force?.count?.toString().orEmpty(), "sides" to melee?.force?.sides?.toString().orEmpty(),
        "flat" to melee?.force?.flat?.toString().orEmpty(), "source" to melee?.sourceReference.orEmpty(),
        "approved" to (melee?.approved == true).toString(), "chargedType" to melee?.chargedDamageType?.name.orEmpty()) }
    "upgrades" -> mapOf("calibration" to profile.calibration.toString(), "enabled" to profile.calibrationEnabled.toString(),
        "progression" to profile.proposedCalibrationProgression.toString(), "dc" to profile.saveDc.toString(),
        "capacity" to profile.capacityOverride?.toString().orEmpty(), "elements" to profile.elementCapacityOverride?.toString().orEmpty(),
        "release" to profile.releaseOverride?.toString().orEmpty(), "limit" to profile.spellShotsPerTurn.toString(), "source" to "") +
        configurableArmorUpgrades.associate { it.name to (it in profile.confirmed).toString() }
    else -> error("Unknown armor configuration section")
}

internal fun genesisArmorEditedProfile(character: ReforgeCharacter, section: String, fields: Map<String, String>,
    damageType: DamageType? = null): Profile {
    val profile = requireNotNull(character.armor) { "Connect this armor to the character first." }.campaign.profile
    fun field(name: String) = fields[name].orEmpty()
    fun number(name: String, label: String, min: Int, max: Int) = field(name).intValue(label, min, max)
    fun optional(name: String, label: String, min: Int, max: Int) = optionalInt(field(name), label, min, max)
    val source = field("source").trim()
    require(source.isNotEmpty() && source.length <= 1000) { "Record the accepted ruling or source (up to 1,000 characters)." }
    val next = when (section) {
        "damage" -> if (damageType == null) profile.copy(
            baseDamageDiceCount = number("base", "Primary dice", 1, 10),
            elementalSelectorDiceCount = number("selector", "Selector dice", 1, 10))
        else if (field("inherit").toBoolean()) profile.copy(elementDamageProfiles = profile.elementDamageProfiles - damageType)
        else {
            val formula = DamageFormulaProfile((profile.elementDamageProfiles[damageType]?.version ?: 0) + 1,
                Dice(number("count", "Dice count", 1, 10), number("sides", "Die size", 4, 20), number("flat", "Flat bonus", -100, 100)),
                source, field("approved").toBoolean())
            formula.validate()
            profile.copy(elementDamageProfiles = profile.elementDamageProfiles + (damageType to formula))
        }
        "melee" -> {
            val projection = CharacterCalculator.calculate(character)
            fun ability(stat: Stat) = requireNotNull(projection.abilities[stat]?.modifier) { "Set ${stat.name} on the character sheet before configuring the gauntlets." }
            val melee = MeleeProfile(number("attack", "Enhanced attack bonus", -10, 30),
                Dice(number("count", "Force dice", 1, 10), number("sides", "Force die size", 4, 20), number("flat", "Force flat bonus", -100, 100)),
                ability(Stat.STR), ability(Stat.DEX), ability(Stat.INT), source,
                (profile.melee?.version ?: 0) + 1, field("approved").toBoolean(),
                field("chargedType").takeIf(String::isNotBlank)?.let(DamageType::valueOf),
                optional("guardian", "Guardian attack bonus", -10, 30))
            melee.validate()
            profile.copy(melee = melee)
        }
        "upgrades" -> profile.copy(calibration = number("calibration", "Calibration", 0, 10),
            calibrationEnabled = field("enabled").toBoolean(), proposedCalibrationProgression = field("progression").toBoolean(),
            saveDc = number("dc", "Internal save DC", 1, 40),
            confirmed = (profile.confirmed - configurableArmorUpgrades.toSet()) + configurableArmorUpgrades.filter { field(it.name).toBoolean() },
            capacityOverride = optional("capacity", "Storage capacity", 1, 12),
            elementCapacityOverride = optional("elements", "Element capacity", 1, 9),
            releaseOverride = optional("release", "Release limit", 1, 12), spellShotsPerTurn = number("limit", "Spell firings per turn", 1, 20))
        else -> error("Unknown armor configuration section")
    }
    return next.copy(version = profile.version + 1).also(Profile::validate)
}

/** A frozen draft cannot overwrite a character changed in another screen or while the app was paused. */
@Composable internal fun GenesisArmorConfigurationEditor(
    latestCharacter: ReforgeCharacter,
    section: String,
    onDismiss: () -> Unit,
    onSave: (ReforgeCharacter, String) -> Unit
) {
    val character = requireNotNull(rememberReforgeSnapshot(latestCharacter))
    val armor = character.armor ?: return
    val profile = armor.campaign.profile
    var selectedType by rememberSaveable { mutableStateOf<String?>(null) }
    val damageType = selectedType?.let(DamageType::valueOf)
    val sourceKey = if (section == "damage") "damage:${damageType?.name ?: "default"}" else section
    val originalFields = remember(profile, section, damageType) {
        val editingProfile = if (section == "melee" && armor.meleeUsesCharacterAccuracy)
            runCatching { GenesisArmorRules.runtime(character).profile }.getOrDefault(profile) else profile
        val initial = genesisArmorFeatureFields(editingProfile, section, damageType)
        initial + mapOf("source" to (armor.configurationSources[sourceKey] ?: initial["source"].orEmpty())) +
            when (section) {
                "melee" -> mapOf("liveAccuracy" to armor.meleeUsesCharacterAccuracy.toString())
                "upgrades" -> mapOf("onboarded" to armor.campaign.onboarded.toString(), "attuned" to armor.campaign.attuned.toString())
                else -> emptyMap()
            }
    }
    var draftJson by rememberSaveable(section, selectedType) { mutableStateOf(Json.encodeToString(originalFields)) }
    val fields = remember(draftJson) { Json.decodeFromString<Map<String, String>>(draftJson) }
    fun field(name: String) = fields[name].orEmpty()
    fun update(name: String, value: String) { draftJson = Json.encodeToString(fields + (name to value)) }
    var error by rememberSaveable { mutableStateOf("") }
    var pendingProfileJson by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingProfile = remember(pendingProfileJson) { pendingProfileJson?.let { BackupCodec.json.decodeFromString<Profile>(it) } }
    val stale = latestCharacter != character
    val feedback = LocalGenesisEditorFeedback.current
    val saving = feedback.busy
    LaunchedEffect(latestCharacter.armor?.campaign?.profile, pendingProfile) {
        if (pendingProfile != null && latestCharacter.armor?.campaign?.profile == pendingProfile) onDismiss()
    }
    val preview = remember(character, fields, damageType) { runCatching { genesisArmorEditedProfile(character, section, fields, damageType) } }
    val title = when (section) { "damage" -> "Armor damage"; "melee" -> "Gauntlet mechanics"; else -> "Engineering upgrades" }
    val projection = remember(character) { CharacterCalculator.calculate(character) }
    val reviewNames = listOf(if (section == "damage") "Formula" else "Configuration", "Review")
    GenesisEditorSheet(title, reviewNames, creating = true, context = "${character.name} · Armor profile ${profile.version}",
        onDismiss = onDismiss, dirty = fields != originalFields,
        saveLabel = "Save armor rules", saveEnabled = !stale && preview.isSuccess && fields != originalFields,
        error = if (stale) "The character changed while this draft was open. Close and reopen to review the latest values." else error,
        onSave = {
            try {
                check(!stale) { "The character changed. Reopen the editor." }
                val next = preview.getOrThrow()
                val label = "$title v${next.version}"
                pendingProfileJson = BackupCodec.json.encodeToString(next)
                val nextCampaign = armor.campaign.copy(profile = next).let {
                    if (section != "upgrades") it else it.copy(onboarded = field("onboarded").toBoolean(), attuned = field("attuned").toBoolean())
                }
                val nextArmor = armor.copy(campaign = nextCampaign,
                    configurationSources = armor.configurationSources + (sourceKey to field("source").trim())).let {
                    if (section != "melee") it else it.copy(
                        meleeUsesCharacterAccuracy = field("liveAccuracy").toBoolean(),
                        meleeAccuracyIntBaseline = projection.abilities[Stat.INT]?.modifier,
                        meleeAccuracyProficiencyBaseline = projection.proficiencyBonus)
                }
                onSave(character.copy(armor = nextArmor), label)
            } catch (failure: Exception) { error = failure.message ?: "Check the armor configuration." }
        }) { step, select ->
        if (step == 0) {
            when (section) {
                "damage" -> {
                    Choices("Damage configuration", listOf("Shared defaults") + DamageType.entries.map { it.displayName() },
                        damageType?.displayName() ?: "Shared defaults", enabled = !saving && fields == originalFields) { chosen ->
                        selectedType = DamageType.entries.firstOrNull { it.displayName() == chosen }?.name
                        error = ""
                    }
                    if (fields != originalFields) Text("Save or close this draft before choosing another formula.", color = RebuildPalette.Muted)
                    if (damageType == null) {
                        Text("Defaults apply where an individual approved formula is not configured.", color = RebuildPalette.Muted)
                        Field("Primary Force dice count (d12)", field("base"), { update("base", it) }, enabled = !saving)
                        Field("Elemental selector dice count (d8)", field("selector"), { update("selector", it) }, enabled = !saving)
                        if (profile.elementDamageProfiles.values.any { it.approved }) Text("Approved individual formulas keep their own dice and flat bonuses.", color = RebuildPalette.Ice)
                    } else {
                        val original = profile.elementDamageProfiles[damageType]
                        if (original != null) Toggle("Use shared default", field("inherit").toBoolean(), !saving) { update("inherit", it.toString()) }
                        if (!field("inherit").toBoolean()) {
                            Field("Dice count", field("count"), { update("count", it) }, enabled = !saving)
                            Field("Die size (4, 6, 8, 10, 12 or 20)", field("sides"), { update("sides", it) }, enabled = !saving)
                            Field("Accepted flat bonus", field("flat"), { update("flat", it) }, enabled = !saving)
                            Toggle("Confirmed formula for future attacks", field("approved").toBoolean(), !saving) { update("approved", it.toString()) }
                        }
                        Text(if (damageType == DamageType.FORCE) "This replaces the primary Force formula. Neutral Force adds no selector dice. Discharge never gains its flat bonus."
                            else "A formula does not unlock an element. Unconfirmed formulas are retained for review and use the shared default.", color = RebuildPalette.Muted)
                    }
                }
                "melee" -> {
                    Text("Accepted attack and Force values remain explicit. Thunder and Charged Gauntlet ability modifiers follow the character sheet.", color = RebuildPalette.Muted)
                    Text("STR ${reforgeSigned(projection.abilities[Stat.STR]?.modifier)} · DEX ${reforgeSigned(projection.abilities[Stat.DEX]?.modifier)} · INT ${reforgeSigned(projection.abilities[Stat.INT]?.modifier)}", color = RebuildPalette.Ice)
                    Field("Accepted enhanced attack bonus", field("attack"), { update("attack", it) }, enabled = !saving)
                    Field("Guardian attack bonus (blank = unresolved)", field("guardian"), { update("guardian", it) }, enabled = !saving)
                    Toggle("Follow future INT and proficiency changes", field("liveAccuracy").toBoolean(), !saving) { update("liveAccuracy", it.toString()) }
                    Text(if (field("liveAccuracy").toBoolean()) "The entered attack bonuses are today's totals. Later INT or proficiency changes adjust them by the difference."
                        else "Attack bonuses stay at their accepted fixed totals until you edit them.", color = RebuildPalette.Muted)
                    Field("Force dice count", field("count"), { update("count", it) }, enabled = !saving)
                    Field("Force die size (4, 6, 8, 10, 12 or 20)", field("sides"), { update("sides", it) }, enabled = !saving)
                    Field("Accepted Force flat bonus", field("flat"), { update("flat", it) }, enabled = !saving)
                    Choices("Charged bonus damage type", listOf("Unresolved") + DamageType.entries.map { it.displayName() },
                        field("chargedType").takeIf(String::isNotBlank)?.let { DamageType.valueOf(it).displayName() } ?: "Unresolved", enabled = !saving) { chosen ->
                        update("chargedType", DamageType.entries.firstOrNull { it.displayName() == chosen }?.name.orEmpty())
                    }
                    Toggle("Confirmed melee configuration", field("approved").toBoolean(), !saving) { update("approved", it.toString()) }
                    Text("Missing charged damage type keeps charging unavailable. Guardian remains independent of nexus overload when its attack bonus is confirmed.", color = RebuildPalette.Muted)
                }
                else -> {
                    Text("Artificer level, INT and proficiency are read from your character. Internal DC is an independent accepted armor rule.", color = RebuildPalette.Muted)
                    Toggle("Established armor configuration reviewed", field("onboarded").toBoolean(), !saving) { update("onboarded", it.toString()) }
                    Toggle("Attuned to Astravar", field("attuned").toBoolean(), !saving) { update("attuned", it.toString()) }
                    Field("Internal save DC", field("dc"), { update("dc", it) }, enabled = !saving)
                    Toggle("Calibration enabled", field("enabled").toBoolean(), !saving) { update("enabled", it.toString()) }
                    Field("Calibration bonus", field("calibration"), { update("calibration", it) }, enabled = !saving)
                    Toggle("Accepted +2 at level 10 / +3 at level 15 progression", field("progression").toBoolean(), !saving) { update("progression", it.toString()) }
                    Text("Confirmed installed upgrades", style = MaterialTheme.typography.titleMedium)
                    configurableArmorUpgrades.forEach { upgrade ->
                        Toggle(upgrade.displayName(), field(upgrade.name).toBoolean(), !saving) { update(upgrade.name, it.toString()) }
                    }
                    Text("Confirmation does not waive level requirements. Unchanged historical upgrade records remain preserved.", color = RebuildPalette.Muted)
                    PlayerDisclosure("Explicit capacity & firing rules") {
                        Field("Storage capacity override (blank = milestone rules)", field("capacity"), { update("capacity", it) }, enabled = !saving)
                        Field("Element capacity override (blank = milestone rules)", field("elements"), { update("elements", it) }, enabled = !saving)
                        Field("Release limit override (blank = milestone rules)", field("release"), { update("release", it) }, enabled = !saving)
                        Field("Spell-powered firings per turn", field("limit"), { update("limit", it) }, enabled = !saving)
                    }
                }
            }
            Field("Accepted ruling / source reference", field("source"), { update("source", it) }, single = false, enabled = !saving)
        } else {
            GenesisReviewSection(title, { select(0) }) {
                preview.getOrNull()?.let { next ->
                    when (section) {
                        "damage" -> if (damageType == null) {
                            GenesisReviewValue("Primary default", "${next.baseDamageDiceCount}d12 + INT + calibration")
                            GenesisReviewValue("Selector default", "${next.elementalSelectorDiceCount}d8")
                        } else {
                            val explicit = next.elementDamageProfiles[damageType]
                            GenesisReviewValue(damageType.displayName(), explicit?.let { "${it.dice} · ${if (it.approved) "confirmed" else "unconfirmed; inherited default applies"}" } ?: "Use shared default")
                        }
                        "melee" -> next.melee?.let {
                            GenesisReviewValue("Enhanced gauntlet", "${reforgeSigned(it.attackBonus)} attack · ${it.force} Force + ${it.thunder} Thunder")
                            GenesisReviewValue("Guardian", "${reforgeSigned(it.guardianAttackBonus)} attack · ${it.thunder} Thunder")
                            GenesisReviewValue("Accuracy", if (field("liveAccuracy").toBoolean()) "Follow future INT and proficiency changes" else "Keep accepted fixed totals")
                            GenesisReviewValue("Charged bonus type", it.chargedDamageType?.displayName().orEmpty(), "Unresolved")
                            GenesisReviewValue("Approval", if (it.approved) "Confirmed" else "Unconfirmed; enhanced attacks remain unavailable")
                        }
                        else -> {
                            GenesisReviewValue("Armor readiness", "${if (field("onboarded").toBoolean()) "Configuration reviewed" else "Configuration needs review"} · ${if (field("attuned").toBoolean()) "Attuned" else "Not attuned"}")
                            GenesisReviewValue("Internal save DC", next.saveDc.toString())
                            GenesisReviewValue("Calibration", if (!next.calibrationEnabled) "Off" else if (next.proposedCalibrationProgression) "+2 at level 10 / +3 at level 15" else reforgeSigned(next.calibration))
                            GenesisReviewValue("Confirmed upgrades", configurableArmorUpgrades.filter { it in next.confirmed }.joinToString { it.displayName() }, "None")
                            GenesisReviewValue("Spell firings per turn", next.spellShotsPerTurn.toString())
                            GenesisReviewValue("Explicit overrides", listOfNotNull(next.capacityOverride?.let { "Storage $it" }, next.elementCapacityOverride?.let { "Elements $it" }, next.releaseOverride?.let { "Release $it" }).joinToString(" · "), "Milestone rules")
                        }
                    }
                    GenesisReviewValue("Source", field("source"))
                } ?: Text(preview.exceptionOrNull()?.message ?: "Check the entered values.", color = MaterialTheme.colorScheme.error)
            }
            Text("This changes future armor actions only. Character abilities, slots, HP, stored spells and past rolls stay as recorded.", color = RebuildPalette.Muted)
        }
    }
}
