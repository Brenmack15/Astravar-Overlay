package com.yazsras.astravar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Only the visible, human-readable code is accepted here; never pass a credential. */
@Composable fun PairingCodeCopy(code: String, label: String = "Pairing code") {
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    Column {
        PlayerOutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, code))
                copied = true
                Toast.makeText(context, "Copied to clipboard.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("pairing-code-copy")
                .semantics { contentDescription = "Copy $label: $code" }
        ) {
            Text("$label: $code", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, color = RebuildPalette.GoldBright)
            Spacer(Modifier.width(12.dp))
            val color = RebuildPalette.GoldBright
            Canvas(Modifier.size(24.dp)) {
                val stroke = Stroke(width = 1.7.dp.toPx())
                drawRoundRect(color, Offset(size.width * .15f, size.height * .1f),
                    Size(size.width * .55f, size.height * .65f), CornerRadius(2.dp.toPx()), style = stroke)
                drawRoundRect(color, Offset(size.width * .3f, size.height * .25f),
                    Size(size.width * .55f, size.height * .65f), CornerRadius(2.dp.toPx()), style = stroke)
            }
        }
        if (copied) Text("Copied to clipboard.", color = RebuildPalette.Ice,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("pairing-code-copied")
                .semantics { liveRegion = LiveRegionMode.Polite })
    }
}
