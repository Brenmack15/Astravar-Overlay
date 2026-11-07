package com.yazsras.astravar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One compact vocabulary for identity, state, evidence and equipment properties. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ReforgeBadgeRow(labels: List<String>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.filter(String::isNotBlank).distinct().forEach { label ->
            Text(label, color = RebuildPalette.Ice, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(RebuildPalette.Active, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}
