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

internal val genesisCharacterSections = listOf("Sheet", "Combat", "Equipment", "Spells", "Features", "Resources", "History")

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
        val pages = if (sectionOnly) listOf(requestedSection.takeIf { it in genesisCharacterSections } ?: "Equipment")
            else genesisCharacterSections
        val state = rememberPagerState(initialPage = pages.indexOf(requestedSection).coerceAtLeast(0)) { pages.size }
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
                pages.indexOf(requestedSection).takeIf { it >= 0 }?.let { moveTo(it) }
            }
        }
        // Back closes a modal first, then returns to the sheet, then follows the
        // surrounding application's route. It never rewinds gameplay state.
        BackHandler(enabled = !sectionOnly && !modalOpen && state.currentPage != 0) {
            scope.launch { moveTo(0) }
        }
        Column(modifier.fillMaxSize()) {
            if (!sectionOnly) {
                PlayerTabs(pages, pages[state.currentPage], { label ->
                    scope.launch { moveTo(pages.indexOf(label)) }
                }, Modifier.padding(horizontal = 4.dp).testTag("genesis-character-tabs"))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Swipe between sections", style = MaterialTheme.typography.labelSmall,
                        color = RebuildPalette.Muted)
                    Text("${state.currentPage + 1} / ${pages.size}", style = MaterialTheme.typography.labelSmall,
                        color = RebuildPalette.GoldBright,
                        modifier = Modifier.testTag("genesis-page-indicator").semantics {
                            stateDescription = "${pages[state.currentPage]}, page ${state.currentPage + 1} of ${pages.size}"
                        })
                }
            }
            HorizontalPager(
                state = state,
                key = { pages[it] },
                userScrollEnabled = !sectionOnly && !modalOpen,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("genesis-character-pager"),
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
                            verticalArrangement = Arrangement.spacedBy(14.dp)) { content(section) }
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

/** Small persistent status strip; full abilities and detail remain in the pages. */
@Composable internal fun GenesisCharacterHeader(
    character: ReforgeCharacter,
    projection: CharacterProjection,
    onSwitch: (() -> Unit)?,
    onHp: () -> Unit,
    onDefense: () -> Unit,
    onInitiative: () -> Unit
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().background(RebuildPalette.Canvas).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(character.name, style = MaterialTheme.typography.titleLarge, fontFamily = DisplayFace,
                    color = RebuildPalette.Silver, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Text("Level ${character.level} · Proficiency +${projection.proficiencyBonus}", style = MaterialTheme.typography.labelMedium,
                    color = RebuildPalette.GoldBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onSwitch != null) PlayerTextButton(onClick = onSwitch,
                modifier = Modifier.widthIn(max = if (largeText) 156.dp else 112.dp)
                    .semantics { contentDescription = "Switch character" }) { Text("Switch character") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(if (largeText) 1.5f else 2f).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onHp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "HP ${projection.currentHp ?: "Not set"} / ${projection.maximumHp.value ?: "Not set"}"
                }.padding(vertical = 4.dp)) {
                Text("HP ${projection.currentHp ?: "?"} / ${projection.maximumHp.value ?: "?"}",
                    style = MaterialTheme.typography.titleMedium, color = RebuildPalette.Cyan)
                Text("Temporary ${character.temporaryHp ?: "?"}", style = MaterialTheme.typography.labelSmall,
                    color = RebuildPalette.Muted)
            }
            GenesisHeaderStat("AC", projection.armorClass.value?.toString() ?: "?", "Armor Class",
                Modifier.weight(1f), onDefense)
            GenesisHeaderStat("INIT", reforgeSigned(projection.initiative.value), "Roll initiative",
                Modifier.weight(if (largeText) 1.3f else 1f), onInitiative,
                enabled = projection.initiative.value != null)
        }
        if (character.conditions.isNotEmpty()) Text(character.conditions.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = RebuildPalette.GoldBright,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun GenesisHeaderStat(label: String, value: String, description: String,
    modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    Column(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = RebuildPalette.Muted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = RebuildPalette.GoldBright)
    }
}
