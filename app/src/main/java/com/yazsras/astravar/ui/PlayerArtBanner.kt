package com.yazsras.astravar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The artwork is decorative. All meaningful information stays in real, accessible Compose text. */
@Composable fun PlayerArtBanner(eyebrow: String, title: String, subtitle: String, artwork: Int) {
    Box(Modifier.fillMaxWidth().heightIn(min = 116.dp).clip(RoundedCornerShape(10.dp))) {
        Image(painterResource(artwork), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to RebuildPalette.Void.copy(alpha = .96f),
            .65f to RebuildPalette.Void.copy(alpha = .52f),
            1f to Color.Transparent)))
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(eyebrow, style = MaterialTheme.typography.labelSmall,
                color = RebuildPalette.GoldBright, letterSpacing = 1.5.sp)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontFamily = DisplayFace,
                fontWeight = FontWeight.SemiBold, color = RebuildPalette.Silver)
            Text(subtitle, color = RebuildPalette.Ice, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
