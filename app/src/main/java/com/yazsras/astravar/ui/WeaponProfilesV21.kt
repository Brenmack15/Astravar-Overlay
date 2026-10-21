package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.*
import kotlinx.serialization.encodeToString

/** Explicit future-roll configuration. Old plans keep the complete profile snapshot. */
@Composable fun ElementDamageProfilesEditor(s: Campaign, a: Actions, onDirtyChange: (Boolean) -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf<DamageType?>(null) }
    if (selected == null) {
        SideEffect { onDirtyChange(false) }
        Text("Each type can inherit its existing formula or use a separately sourced, confirmed formula. Editing a locked element does not unlock it.", color = Muted)
        DamageType.entries.forEach { type ->
            val explicit = s.profile.elementDamageProfiles[type]
            val formula = if (type == DamageType.FORCE) s.profile.base else s.profile.selectorFor(type)
            val locked = type != DamageType.FORCE && type !in Engine.elements(s.profile)
            PlayerEntry(type.name.lowercase().replaceFirstChar(Char::titlecase),
                "$formula · ${if (explicit?.approved == true) "confirmed v${explicit.version}" else "inherited default"}${if (locked) " · locked" else ""}",
                type.name, { selected = type })
        }
        Text("Force configures the primary Force component. Neutral Force adds no extra selector or enhanced damage.", style = MaterialTheme.typography.bodySmall, color = Muted)
    } else key(selected) {
        val type = selected!!
        var baselineJson by rememberSaveable { mutableStateOf(BackupCodec.json.encodeToString(s.profile)) }
        val baseline = remember(baselineJson) { BackupCodec.json.decodeFromString<Profile>(baselineJson) }
        val original = baseline.elementDamageProfiles[type]
        val inherited = if (type == DamageType.FORCE) baseline.copy(elementDamageProfiles = baseline.elementDamageProfiles - type).base else baseline.selector
        var count by rememberSaveable { mutableStateOf((original?.dice ?: inherited).count.toString()) }
        var sides by rememberSaveable { mutableStateOf((original?.dice ?: inherited).sides.toString()) }
        var flat by rememberSaveable { mutableStateOf((original?.dice ?: inherited).flat.toString()) }
        var source by rememberSaveable { mutableStateOf(original?.sourceReference.orEmpty()) }
        var approved by rememberSaveable { mutableStateOf(original?.approved ?: false) }
        val stale = s.profile != baseline
        val dirty = count != (original?.dice ?: inherited).count.toString() || sides != (original?.dice ?: inherited).sides.toString() ||
            flat != (original?.dice ?: inherited).flat.toString() || source != original?.sourceReference.orEmpty() || approved != (original?.approved ?: false)
        var discard by rememberSaveable { mutableStateOf(false) }
        SideEffect { onDirtyChange(dirty) }
        if (discard) ArcaneDialog(onDismissRequest = { discard = false }, title = { Text("Discard unsaved formula?") },
            text = { Text("Your saved formula remains unchanged.") },
            confirmButton = { PlayerTextButton(onClick = { selected = null }) { Text("Discard draft") } },
            dismissButton = { PlayerTextButton(onClick = { discard = false }) { Text("Keep editing") } })
        PlayerTextButton(onClick = { if (dirty) discard = true else selected = null }, enabled = !a.busy) { Text("‹ All damage types") }
        Text(type.name.lowercase().replaceFirstChar(Char::titlecase), style = MaterialTheme.typography.titleLarge, color = Brass)
        Text("Inherited default: $inherited", color = Muted)
        if (type in listOf(DamageType.RADIANT, DamageType.PSYCHIC, DamageType.NECROTIC)) {
            Text("Unlock requires Artificer level 8 and a confirmed Advanced Elements upgrade. ${if (type in Engine.elements(s.profile)) "Currently unlocked." else "Currently locked; this editor does not bypass that gate."}", color = Frost)
        }
        if (type == DamageType.FORCE) Text("Primary Force only. No proposed enhanced Neutral component is enabled.", color = Muted)
        Field("Dice count", count, { count = it }, enabled = !a.busy)
        Field("Die size (4, 6, 8, 10, 12 or 20)", sides, { sides = it }, enabled = !a.busy)
        Field("Accepted flat bonus", flat, { flat = it }, enabled = !a.busy)
        Field("Accepted ruling / source reference", source, { source = it }, single = false, enabled = !a.busy)
        Toggle("Confirmed for future rolls", approved, !a.busy) { approved = it }
        Text("Unconfirmed formulas are saved for review and do not affect attacks. Explosions retain their accepted no-flat-bonus and save rules.", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (stale) Text("Weapon profile changed. Your draft is retained; return to the list and reopen before saving.", color = MaterialTheme.colorScheme.error)
        PlayerButton(onClick = {
            a.run("Damage formula saved; previous rolls preserved") {
                val dice = Dice(count.intValue("Dice count", 1, 10), sides.intValue("Die size", 4, 20), flat.intValue("Flat bonus", -100, 100))
                dice.validate()
                require(source.isNotBlank()) { "Record the ruling or source reference before saving." }
                val formula = DamageFormulaProfile(version = (original?.version ?: 0) + 1, dice = dice, sourceReference = source.trim(), approved = approved)
                a.repo.mutate(label = "${type.name} formula v${formula.version} · ${source.trim()}") { current ->
                    check(current.profile == baseline) { "Weapon profile changed. Reopen this editor." }
                    current.copy(profile = current.profile.copy(version = current.profile.version + 1,
                        elementDamageProfiles = current.profile.elementDamageProfiles + (type to formula)))
                }
                selected = null
            }
        }, enabled = !a.busy && !stale && source.isNotBlank()) { Text("Save formula") }
        if (original != null) PlayerOutlinedButton(onClick = {
            a.run("Inherited damage formula restored for future rolls") {
                a.repo.mutate(label = "${type.name}: use inherited formula") { current ->
                    check(current.profile == baseline) { "Weapon profile changed. Reopen this editor." }
                    current.copy(profile = current.profile.copy(version = current.profile.version + 1,
                        elementDamageProfiles = current.profile.elementDamageProfiles - type))
                }; selected = null
            }
        }, enabled = !a.busy && !stale) { Text("Use inherited default") }
        if (a.message.isNotBlank()) Text(a.message, color = Frost)
    }
}

@Composable fun MeleeProfileEditor(s: Campaign, a: Actions, onDirtyChange: (Boolean) -> Unit = {}) {
    var baselineJson by rememberSaveable { mutableStateOf(BackupCodec.json.encodeToString(s.profile)) }
    val baseline = remember(baselineJson) { BackupCodec.json.decodeFromString<Profile>(baselineJson) }
    val original = baseline.melee
    var guardianAttack by rememberSaveable { mutableStateOf(original?.guardianAttackBonus?.toString().orEmpty()) }
    var attack by rememberSaveable { mutableStateOf(original?.attackBonus?.toString().orEmpty()) }
    var count by rememberSaveable { mutableStateOf(original?.force?.count?.toString().orEmpty()) }
    var sides by rememberSaveable { mutableStateOf(original?.force?.sides?.toString().orEmpty()) }
    var flat by rememberSaveable { mutableStateOf(original?.force?.flat?.toString().orEmpty()) }
    var strength by rememberSaveable { mutableStateOf(original?.strengthModifier?.toString().orEmpty()) }
    var dexterity by rememberSaveable { mutableStateOf(original?.dexterityModifier?.toString().orEmpty()) }
    var intelligence by rememberSaveable { mutableStateOf(original?.intelligenceModifier?.toString().orEmpty()) }
    var source by rememberSaveable { mutableStateOf(original?.sourceReference.orEmpty()) }
    var approved by rememberSaveable { mutableStateOf(original?.approved ?: false) }
    var chargedDamageType by rememberSaveable { mutableStateOf(original?.chargedDamageType) }
    val dirty = guardianAttack != original?.guardianAttackBonus?.toString().orEmpty() || attack != original?.attackBonus?.toString().orEmpty() || count != original?.force?.count?.toString().orEmpty() ||
        sides != original?.force?.sides?.toString().orEmpty() || flat != original?.force?.flat?.toString().orEmpty() ||
        strength != original?.strengthModifier?.toString().orEmpty() || dexterity != original?.dexterityModifier?.toString().orEmpty() ||
        intelligence != original?.intelligenceModifier?.toString().orEmpty() || source != original?.sourceReference.orEmpty() ||
        approved != (original?.approved ?: false) || chargedDamageType != original?.chargedDamageType
    SideEffect { onDirtyChange(dirty) }
    val stale = s.profile != baseline
    Text("Gauntlet attacks use an explicitly sourced melee profile. Character changes do not silently overwrite the accepted weapon profile.", color = Muted)
    Engine.meleeLockReason(s.profile)?.let { Text(it, color = Frost) }
    Engine.guardianLockReason(s.profile)?.let { Text(it, color = Frost) }
    Field("Ordinary Guardian attack bonus (blank = unresolved)", guardianAttack, { guardianAttack = it }, enabled = !a.busy)
    Field("Accepted enhanced melee attack bonus", attack, { attack = it }, enabled = !a.busy)
    Field("Force dice count", count, { count = it }, enabled = !a.busy)
    Field("Force die size", sides, { sides = it }, enabled = !a.busy)
    Field("Accepted Force flat bonus", flat, { flat = it }, enabled = !a.busy)
    Field("Current STR modifier", strength, { strength = it }, enabled = !a.busy)
    Field("Current DEX modifier", dexterity, { dexterity = it }, enabled = !a.busy)
    Field("Current INT modifier", intelligence, { intelligence = it }, enabled = !a.busy)
    Field("Accepted ruling / source reference", source, { source = it }, single = false, enabled = !a.busy)
    Choices("Charged bonus damage type · requires accepted ruling", listOf<DamageType?>(null) + DamageType.entries, chargedDamageType,
        display = { it?.name?.lowercase()?.replaceFirstChar(Char::titlecase) ?: "Unresolved · charging remains locked" }, enabled = !a.busy) { chargedDamageType = it }
    Toggle("Confirmed melee configuration", approved, !a.busy) { approved = it }
    Text("Ordinary Guardian gauntlets use 1d8 + configured INT Thunder and remain available during nexus overload once their attack bonus is confirmed. Enhanced attacks remain locked during recovery. Charged Gauntlet spends a Bonus Action to prepare STR + DEX + INT damage on the next melee strike. Its damage type requires an accepted ruling. Spell-Imbued Gauntlet uses a chosen stored payload or available configured spell slot. Dice double on a critical; flat bonuses do not.", style = MaterialTheme.typography.bodySmall, color = Muted)
    if (stale) Text("Weapon profile changed. Close and reopen before saving your retained draft.", color = MaterialTheme.colorScheme.error)
    PlayerButton(onClick = {
        a.run("Melee profile saved; historical rolls unchanged") {
            val force = Dice(count.intValue("Force count", 1, 10), sides.intValue("Force die size", 4, 20), flat.intValue("Force flat", -100, 100))
            force.validate()
            require(source.isNotBlank()) { "Record an accepted ruling or source reference." }
            val melee = MeleeProfile(attackBonus = attack.intValue("Melee attack bonus", -10, 30), force = force,
                strengthModifier = strength.intValue("STR modifier", -5, 10), dexterityModifier = dexterity.intValue("DEX modifier", -5, 10),
                intelligenceModifier = intelligence.intValue("INT modifier", -5, 10), sourceReference = source.trim(),
                version = (original?.version ?: 0) + 1, approved = approved, chargedDamageType = chargedDamageType,
                guardianAttackBonus = guardianAttack.takeIf { it.isNotBlank() }?.intValue("Guardian attack bonus", -10, 30))
            val after = a.repo.mutate(label = "Melee profile v${melee.version} · ${source.trim()}") { current ->
                check(current.profile == baseline) { "Weapon profile changed. Reopen this editor." }
                current.copy(profile = current.profile.copy(version = current.profile.version + 1, melee = melee))
            }
            baselineJson = BackupCodec.json.encodeToString(after.profile)
        }
    }, enabled = !a.busy && !stale && source.isNotBlank()) { Text("Save melee profile") }
    if (a.message.isNotBlank()) Text(a.message, color = Frost)
}
