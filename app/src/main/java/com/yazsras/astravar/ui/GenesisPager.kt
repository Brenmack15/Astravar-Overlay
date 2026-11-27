package com.yazsras.astravar.ui

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.rules.reforge.CharacterProjection
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.launch

internal val genesisCharacterSections = listOf("Sheet", "Combat", "Equipment", "Currency", "Spells", "Features", "Resources", "History")

/** Navigation only; never a second copy of the character or its editable state. */
data class GenesisCharacterRoute(val profileId: String, val section: String = "Sheet",
    val detailDialog: String? = null, val token: Int = 0)

/**
 * The pager owns its position. External shortcuts are explicit, one-shot requests;
 * a recomposition or restored Activity must never replay an old route over a swipe.
 * Each page's saveable state and vertical reading position belong to this actor.
 */
@Composable internal fun GenesisCharacterPager(
    actorId: String,
    requestedSection: String = "Sheet",
    requestToken: Int = 0,
    sectionOnly: Boolean = false,
    modalOpen: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(String) -> Unit
) {
    key(actorId, sectionOnly) {
        val sectionKey = if (requestedSection == "Actions") "Combat" else requestedSection
        val pages = if (sectionOnly) listOf(sectionKey.takeIf { it in genesisCharacterSections } ?: "Equipment")
            else genesisCharacterSections
        val state = rememberPagerState(initialPage = pages.indexOf(sectionKey).coerceAtLeast(0)) { pages.size }
        var consumedRequest by rememberSaveable { mutableIntStateOf(requestToken) }
        val pageStates = rememberSaveableStateHolder()
        val scope = rememberCoroutineScope()
        suspend fun moveTo(index: Int) {
            if (ValueAnimator.areAnimatorsEnabled()) state.animateScrollToPage(index)
            else state.scrollToPage(index)
        }
        LaunchedEffect(requestToken) {
            if (consumedRequest != requestToken) {
                consumedRequest = requestToken
                pages.indexOf(sectionKey).takeIf { it >= 0 }?.let { moveTo(it) }
            }
        }
        // Back closes a modal first, then returns to the sheet, then follows the
        // surrounding application's route. It never rewinds gameplay state.
        BackHandler(enabled = !sectionOnly && !modalOpen && state.currentPage != 0) {
            scope.launch { moveTo(0) }
        }
        Column(modifier.fillMaxSize()) {
            if (!sectionOnly) {
                val labels = pages.map { if (it == "Combat") "Actions" else it }
                PlayerTabs(labels, labels[state.currentPage], { label ->
                    scope.launch { moveTo(labels.indexOf(label)) }
                }, Modifier.padding(horizontal = 4.dp).testTag("genesis-character-tabs"))
            }
            HorizontalPager(
                state = state,
                key = { pages[it] },
                userScrollEnabled = !sectionOnly && !modalOpen,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("genesis-character-pager").semantics {
                    val page = pages[state.currentPage]
                    stateDescription = "${if (page == "Combat") "Actions" else page}, page ${state.currentPage + 1} of ${pages.size}"
                },
                verticalAlignment = Alignment.Top
            ) { index ->
                val section = pages[index]
                pageStates.SaveableStateProvider(section) {
                    // Page() intentionally resets scroll on entry for legacy
                    // routes. Pager pages instead retain their reading positions.
                    val scroll = rememberScrollState()
                    Box(Modifier.fillMaxSize().instrumentSurface(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()
                            .testTag("genesis-page-$section")
                            .verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                content(section)
                                Spacer(Modifier.height(56.dp))
                            }
                    }
                }
            }
        }
    }
}

/**
 * A nested horizontal control owns its whole gesture, including the unused delta
 * at either end. Vertical gestures still reach the page. Sliders, editors and
 * drag customizers live in dialogs, outside the pager's pointer-input subtree.
 */
private object GenesisHorizontalControl : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(available.x, 0f)
}

internal fun Modifier.genesisHorizontalControl(): Modifier = nestedScroll(GenesisHorizontalControl)
    .pointerInput(Unit) {
        // When every chip fits, horizontalScroll cannot consume a drag. Keep
        // that same control region from suddenly becoming a page-swipe target.
        detectHorizontalDragGestures { change, _ -> change.consume() }
    }

/** Character interactions use the selected actor's verified projection. */
@Composable internal fun GenesisCharacterHeader(
    character: ReforgeCharacter, projection: CharacterProjection, compact: Boolean,
    onProfile: () -> Unit, onPortrait: () -> Unit, onControls: () -> Unit,
    onHp: () -> Unit, onDefense: () -> Unit, onConditions: () -> Unit, onRest: () -> Unit,
    onInitiative: () -> Unit, onInitiativeOptions: () -> Unit
) {
    val hp = projection.currentHp
    val max = projection.maximumHp.value
    val temp = character.temporaryHp?.takeIf { it > 0 }
    if (compact && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 560) {
        Row(Modifier.fillMaxWidth().background(RebuildPalette.Canvas).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(character.name, Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Open Character Profile", onClick = onProfile)
                .semantics { heading() }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PlayerTextButton(onClick = onDefense, modifier = Modifier.semantics { contentDescription = "Armor Class" }) { Text("AC ${projection.armorClass.value ?: "?"}") }
            SheetRollTarget("Initiative", projection.initiative.value, onInitiative, onInitiativeOptions, small = true)
            IconButton(onClick = onRest, modifier = Modifier.size(48.dp).semantics { contentDescription = "Rest" }) {
                ArcaneIcon("fire", Modifier.size(22.dp), RebuildPalette.GoldBright)
            }
            IconButton(onClick = onControls, modifier = Modifier.size(48.dp).testTag("genesis-character-controls").semantics { contentDescription = "Character controls" }) {
                ArcaneIcon("Settings", Modifier.size(22.dp), RebuildPalette.GoldBright)
            }
            ReforgePortrait(character.visiblePortrait(), Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onPortrait)
                .semantics { contentDescription = "Manage ${character.name} portrait" })
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${hp ?: "?"} / ${max ?: "?"}${temp?.let { " ($it)" } ?: ""}", Modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onHp).semantics { contentDescription = "HP ${hp ?: "Not set"} / ${max ?: "Not set"}" +
                        (temp?.let { ", $it temporary" } ?: "") }, style = MaterialTheme.typography.titleMedium, color = RebuildPalette.Cyan)
            }
            IconButton(onClick = onConditions, modifier = Modifier.size(48.dp).semantics { contentDescription = "Conditions" }) {
                ArcaneIcon("Armor", Modifier.size(20.dp), RebuildPalette.GoldBright)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().background(RebuildPalette.Canvas).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button,
                onClickLabel = "Open Character Profile", onClick = onProfile), verticalArrangement = Arrangement.Center) {
                Text(character.name, style = MaterialTheme.typography.titleLarge, fontFamily = DisplayFace,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (!compact) Text(listOf("Level ${character.level}", character.className, character.species)
                    .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = RebuildPalette.GoldBright)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1.2f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button,
                        onClickLabel = "Inspect armor class", onClick = onDefense).semantics { contentDescription = "Armor Class" }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("AC", style = MaterialTheme.typography.labelSmall)
                        Text(projection.armorClass.value?.toString() ?: "?", style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("INIT", style = MaterialTheme.typography.labelSmall)
                        SheetRollTarget("Initiative", projection.initiative.value, onInitiative, onInitiativeOptions)
                    }
                }
                Row {
                    IconButton(onClick = onRest, modifier = Modifier.weight(1f).semantics { contentDescription = "Rest" }) {
                        ArcaneIcon("fire", Modifier.size(22.dp), RebuildPalette.GoldBright)
                    }
                    IconButton(onClick = onControls, modifier = Modifier.weight(1f).testTag("genesis-character-controls")
                        .semantics { contentDescription = "Character controls" }) {
                        ArcaneIcon("Settings", Modifier.size(22.dp), RebuildPalette.GoldBright)
                    }
                }
            }
            ReforgePortrait(character.visiblePortrait(), Modifier.size(if (compact) 48.dp else 66.dp)
                .clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onPortrait)
                .semantics { contentDescription = "Manage ${character.name} portrait" })
            Column(Modifier.weight(1.25f)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .background(RebuildPalette.Panel).clickable(role = Role.Button, onClickLabel = "Manage hit points", onClick = onHp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "HP ${hp ?: "Not set"} / ${max ?: "Not set"}" + (temp?.let { ", $it temporary" } ?: "")
                    }.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("HP", style = MaterialTheme.typography.labelSmall, color = RebuildPalette.Muted)
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        append("${hp ?: "?"} / ${max ?: "?"}")
                        if (temp != null) {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = RebuildPalette.Ice))
                            append(" ($temp)"); pop()
                        }
                    }, style = MaterialTheme.typography.titleMedium)
                    if (hp != null && max != null && max > 0) LinearProgressIndicator(progress = { (hp.toFloat()/max).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp), color = RebuildPalette.Cyan, trackColor = RebuildPalette.Outline)
                }
                PlayerTextButton(onClick = onConditions) { Text(if (character.conditions.isEmpty()) "Conditions" else "Conditions (${character.conditions.size})") }
            }
        }
    }
}
