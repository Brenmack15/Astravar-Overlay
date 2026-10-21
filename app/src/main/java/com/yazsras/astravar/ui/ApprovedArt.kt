package com.yazsras.astravar.ui

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.yazsras.astravar.R
import com.yazsras.astravar.rules.Ability
import kotlin.math.min
import kotlin.math.roundToInt

/** Exact original medallion art only; labels, cards, values and controls are native Compose. */
enum class ApprovedAttackArt { ELEMENTAL, GAUNTLET, CHARGED_GAUNTLET, SPELL_SHOT, SPELL_STRIKE, DISCHARGE }

private object ApprovedArtCache {
    private val images = mutableMapOf<Int, ImageBitmap>()
    fun read(resources: Resources, id: Int): ImageBitmap = synchronized(images) {
        images.getOrPut(id) {
            BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false }).asImageBitmap()
        }
    }
}

@Composable private fun OriginalMedallion(id: Int, x: Int, y: Int, width: Int, height: Int, modifier: Modifier) {
    val image = ApprovedArtCache.read(LocalContext.current.resources, id)
    Canvas(modifier.clip(CircleShape)) {
        val scale = min(size.width / width, size.height / height)
        val destination = IntSize((width * scale).roundToInt(), (height * scale).roundToInt())
        drawImage(image, srcOffset = IntOffset(x, y), srcSize = IntSize(width, height),
            dstOffset = IntOffset(((size.width - destination.width) / 2).roundToInt(), ((size.height - destination.height) / 2).roundToInt()),
            dstSize = destination, filterQuality = FilterQuality.High)
    }
}

@Composable fun ApprovedAttackMedallion(art: ApprovedAttackArt, modifier: Modifier) {
    val index = art.ordinal
    OriginalMedallion(R.drawable.approved_attack_art, 310 + (index % 3) * 312,
        if (index < 3) 272 else 587, 204, 198, modifier)
}

fun Ability.approvedArt() = when (this) {
    Ability.ARCANE, Ability.ELEMENTAL -> ApprovedAttackArt.ELEMENTAL
    Ability.SPELL -> ApprovedAttackArt.SPELL_SHOT
    Ability.GAUNTLET, Ability.GUARDIAN -> ApprovedAttackArt.GAUNTLET
    Ability.SPELL_GAUNTLET -> ApprovedAttackArt.SPELL_STRIKE
    Ability.DISCHARGE, Ability.SHATTER -> ApprovedAttackArt.DISCHARGE
}

@Composable fun ApprovedAbilityMedallion(key: String, modifier: Modifier) {
    val index = listOf("STR", "DEX", "CON", "INT", "WIS", "CHA").indexOf(key).coerceAtLeast(0)
    OriginalMedallion(R.drawable.approved_ability_art, 242 + (index % 3) * 398,
        if (index < 3) 188 else 597, 168, 158, modifier)
}
