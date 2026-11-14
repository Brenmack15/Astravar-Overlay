package com.yazsras.astravar.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.yazsras.astravar.data.OverlayPrefs
import com.yazsras.astravar.rules.Campaign

/** Original installation state is read-only evidence for reviewed character adoption. */
internal data class GenesisArmorLegacy(val campaign: Campaign, val preferences: OverlayPrefs)

internal val LocalGenesisArmorLegacy = staticCompositionLocalOf<GenesisArmorLegacy?> { null }

/** Old saved routes open the corresponding character page instead of resurrecting duplicate editors. */
internal fun genesisRetiredToolSection(tool: String?): String? = when (tool) {
    "Armor systems", "Rules & profile" -> "Features"
    "Armor weapon" -> "Combat"
    "Spell reservoir", "Spell library" -> "Spells"
    "Resources & recovery" -> "Resources"
    "Roll history" -> "History"
    else -> null
}
