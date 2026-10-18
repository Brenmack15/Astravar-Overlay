package com.yazsras.astravar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.yazsras.astravar.R
import com.yazsras.astravar.rules.DamageType

/** Original owner-approved particle files, unchanged. Labels remain native accessible text. */
@Composable fun ElementParticle(type: DamageType, modifier: Modifier = Modifier) {
    val asset = when(type) {
        DamageType.ACID -> R.drawable.particle_acid
        DamageType.COLD -> R.drawable.particle_cold
        DamageType.FIRE -> R.drawable.particle_fire
        DamageType.LIGHTNING -> R.drawable.particle_lightning
        DamageType.POISON -> R.drawable.particle_poison
        DamageType.THUNDER -> R.drawable.particle_thunder
        DamageType.NECROTIC -> R.drawable.particle_necrotic
        DamageType.PSYCHIC -> R.drawable.particle_psychic
        DamageType.RADIANT -> R.drawable.particle_radiant
        DamageType.FORCE -> R.drawable.particle_force
    }
    val resources = LocalContext.current.resources
    // The originals are 1536px; small medallions need a bounded decoded bitmap, not 9MiB each.
    val bitmap = remember(asset, resources) {
        BitmapFactory.decodeResource(resources, asset, BitmapFactory.Options().apply { inSampleSize = 4 }).asImageBitmap()
    }
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.clip(CircleShape).border(.8.dp, Brass.copy(alpha=.55f), CircleShape).padding(2.dp))
}
