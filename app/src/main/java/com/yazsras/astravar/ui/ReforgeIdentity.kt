package com.yazsras.astravar.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.reforge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable internal fun ReforgeIdentitySection(character: ReforgeCharacter, onEdit: () -> Unit,
    onNotes: () -> Unit, onAdvance: () -> Unit) {
    PlayerCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReforgePortrait(character.portraitRef)
            Column(Modifier.weight(1f)) {
                Text(listOf(character.species, character.className, character.subclass).filter(String::isNotBlank).joinToString(" · ")
                    .ifBlank { "Identity & background" }, style = MaterialTheme.typography.titleMedium)
                if (character.background.isNotBlank()) Text(character.background, color = RebuildPalette.Muted)
                PlayerTextButton(onClick = onEdit) { Text("Edit identity & features") }
            }
        }
        if (character.languages.isNotEmpty()) Text("Languages · ${character.languages.joinToString()}", color = RebuildPalette.Muted)
        if (character.toolProficiencies.isNotEmpty()) Text("Tools · ${character.toolProficiencies.joinToString()}", color = RebuildPalette.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerOutlinedButton(onClick = onNotes, modifier = Modifier.weight(1f)) { Text("Notes") }
            if (character.level < 20) PlayerOutlinedButton(onClick = onAdvance, modifier = Modifier.weight(1f)) { Text("Level up") }
        }
    }
    if (character.features.isNotEmpty()) PlayerDisclosure("Features & feats", initiallyOpen = true) {
        character.features.forEach { feature -> PlayerDisclosure(feature.name) {
            Text("${feature.kind.name.lowercase()} · ${feature.source.acceptance.name.lowercase().replace('_', ' ')}", color = RebuildPalette.Ice)
            if (feature.actionType.isNotBlank()) Text(feature.actionType, color = RebuildPalette.Muted)
            ReforgeRichText(feature.description)
            feature.resourceId?.let { id -> character.resources.firstOrNull { it.id == id }?.let { Text("${it.name} · ${it.current ?: "?"}/${it.maximum ?: "?"}") } }
        } }
    }
    if (character.notes.isNotBlank()) PlayerDisclosure("Character notes") { ReforgeRichText(character.notes) }
}

@Composable private fun ReforgePortrait(reference: String?) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, reference) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(reference ?: return@runCatching null)
                if (uri.scheme != "content") return@runCatching null
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 8_000_000) { "Portrait is too large." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) Image(bitmap!!, contentDescription = "Character portrait", modifier = Modifier.size(72.dp), contentScale = ContentScale.Crop)
    else ArcaneIcon("Emblem", Modifier.size(56.dp), RebuildPalette.Ice)
}

@Composable internal fun ReforgeIdentityEditor(character: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit) {
    var species by rememberSaveable { mutableStateOf(character.species) }
    var className by rememberSaveable { mutableStateOf(character.className) }
    var subclass by rememberSaveable { mutableStateOf(character.subclass) }
    var background by rememberSaveable { mutableStateOf(character.background) }
    var languages by rememberSaveable { mutableStateOf(character.languages.joinToString(", ")) }
    var tools by rememberSaveable { mutableStateOf(character.toolProficiencies.joinToString(", ")) }
    var portrait by rememberSaveable { mutableStateOf(character.portraitRef) }
    var features by remember { mutableStateOf(character.features) }
    var editingFeature by remember { mutableStateOf<CharacterFeature?>(null) }
    var featureName by rememberSaveable { mutableStateOf("") }
    var featureText by rememberSaveable { mutableStateOf("") }
    var featureKind by rememberSaveable { mutableStateOf(FeatureKind.FEAT.name) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var featureOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var error by remember { mutableStateOf("") }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); portrait = uri.toString() }
            .onFailure { error = "The portrait could not be retained. Choose another document." }
    }
    fun split(value: String) = value.split(',').map(String::trim).filter(String::isNotBlank).toSet()
    val changed = species != character.species || className != character.className || subclass != character.subclass ||
        background != character.background || split(languages) != character.languages || split(tools) != character.toolProficiencies ||
        portrait != character.portraitRef || features != character.features
    PlayerSheet("Identity & features", onDismiss, dirty = changed || featureOpen, actions = {
        PlayerButton(onClick = { onSave(character.copy(species = species.trim(), className = className.trim(), subclass = subclass.trim(),
            background = background.trim(), languages = split(languages), toolProficiencies = split(tools), portraitRef = portrait, features = features)) }, enabled = !featureOpen) { Text("Save identity") }
    }) {
        Field("Species", species, { species = it.take(200) })
        Field("Class", className, { className = it.take(200) })
        Field("Subclass", subclass, { subclass = it.take(200) })
        Field("Background", background, { background = it.take(2000) }, single = false)
        Field("Languages (comma separated)", languages, { languages = it.take(2000) })
        Field("Tool proficiencies (comma separated)", tools, { tools = it.take(2000) })
        PlayerOutlinedButton(onClick = { image.launch(arrayOf("image/*")) }) { Text("Choose portrait") }
        if (portrait != null) PlayerTextButton(onClick = { portrait = null }) { Text("Remove portrait") }
        Text("Class labels and feature descriptions do not activate mechanical bonuses. Add reviewed effects to equipment or configure training separately.", color = RebuildPalette.Muted)
        features.forEach { feature -> PlayerEntry(feature.name, feature.kind.name.lowercase(), "Emblem", {
            editingFeature = feature; featureName = feature.name; featureText = feature.description; featureKind = feature.kind.name
            accepted = feature.source.acceptance in setOf(Acceptance.OFFICIAL, Acceptance.ACCEPTED_HOMEBREW); featureOpen = true
        }) }
        if (!featureOpen) PlayerOutlinedButton(onClick = {
            editingFeature = null; featureName = ""; featureText = ""; featureKind = FeatureKind.FEAT.name; accepted = false; featureOpen = true
        }) { Text("Add feature or feat") }
        if (featureOpen) PlayerCard {
            Field("Feature name", featureName, { featureName = it.take(200) })
            Choices("Feature kind", FeatureKind.entries.map { it.name }, featureKind) { featureKind = it }
            Field("Feature description", featureText, { featureText = it.take(16000) }, single = false)
            Toggle("Accepted at this table", accepted) { accepted = it }
            PlayerOutlinedButton(onClick = {
                if (featureName.isBlank()) error = "Enter a feature name." else {
                    val id = editingFeature?.id ?: UUID.randomUUID().toString()
                    val feature = (editingFeature ?: CharacterFeature(id, featureName, RuleSource("local:$id", "Local feature record"))).copy(
                        name = featureName.trim(), description = featureText, kind = FeatureKind.valueOf(featureKind),
                        source = editingFeature?.source?.takeIf { accepted && it.acceptance == Acceptance.OFFICIAL }
                            ?: RuleSource("local:$id", "Player feature review", if (accepted) Acceptance.ACCEPTED_HOMEBREW else Acceptance.UNVERIFIED))
                    features = features.filterNot { it.id == id } + feature; featureOpen = false
                }
            }) { Text("Keep feature") }
            if (editingFeature != null) PlayerTextButton(onClick = { features = features.filterNot { it.id == editingFeature?.id }; featureOpen = false }) { Text("Remove from this sheet") }
            PlayerTextButton(onClick = { featureOpen = false }) { Text("Cancel feature edit") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun ReforgeAdvancementDialog(character: ReforgeCharacter, onDismiss: () -> Unit, onSave: (ReforgeCharacter) -> Unit) {
    var hitDieGain by rememberSaveable { mutableStateOf("") }
    var stat by rememberSaveable { mutableStateOf(Stat.INT.name) }
    var increase by rememberSaveable { mutableStateOf("0") }
    var preview by remember { mutableStateOf<ReforgeCharacter?>(null) }
    var error by remember { mutableStateOf("") }
    PlayerSheet("Advance to level ${character.level + 1}", onDismiss, dirty = hitDieGain.isNotBlank() || increase != "0", actions = {
        if (preview == null) PlayerButton(onClick = {
            try {
                preview = AdvancementRules.advance(character, character.level + 1, optionalInt(hitDieGain, "Hit Die gain", 1, 1000),
                    mapOf(Stat.valueOf(stat) to (optionalInt(increase, "Accepted ability increase", 0, 10) ?: 0)))
            } catch (e: Exception) { error = e.message ?: "Check advancement choices." }
        }) { Text("Preview advancement") }
        else PlayerButton(onClick = { preview?.let(onSave) }) { Text("Confirm level ${character.level + 1}") }
    }) {
        Text("Record the advancement awarded by your DM. New class features, spells and feats require your accepted choices; no rulebook content is granted automatically.", color = RebuildPalette.Muted)
        Field("Hit Die roll or fixed gain (before Constitution)", hitDieGain, { hitDieGain = it; preview = null })
        Text("Blank keeps the new maximum HP unknown. Baseline Constitution is applied by the calculation engine; existing missing HP is preserved.", color = RebuildPalette.Muted, style = MaterialTheme.typography.bodySmall)
        Choices("Ability increased (if awarded)", Stat.entries.map { it.name }, stat) { stat = it; preview = null }
        Field("Accepted permanent increase", increase, { increase = it; preview = null })
        preview?.let { next ->
            val before = CharacterCalculator.calculate(character); val after = CharacterCalculator.calculate(next)
            Text("Level ${character.level} → ${next.level}\nProficiency +${before.proficiencyBonus} → +${after.proficiencyBonus}\nMaximum HP ${before.maximumHp.value ?: "?"} → ${after.maximumHp.value ?: "?"}\nCurrent HP ${before.currentHp ?: "?"} → ${after.currentHp ?: "?"}")
            Text("Spent and unknown spell slots stay as recorded. This does not perform a rest.", color = RebuildPalette.Ice)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
