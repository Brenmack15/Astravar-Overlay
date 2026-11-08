package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A failed weapon read must not turn diagnostics and separate backups into dead links. */
@Composable internal fun StorageRecoveryPanel(error: String?, open: (String) -> Unit) {
    Page {
        if (error == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Opening saved data…")
        } else {
            PlayerHeading("Saved data needs attention")
            Text("The weapon store could not open. Restart Astravar and inspect diagnostics if the problem continues. Your records have not been reset.")
        }
        PlayerOutlinedButton(onClick = { open("Help & diagnostics") }) { Text("Open diagnostics") }
        if (error != null) {
            PlayerOutlinedButton(onClick = { open("Character backup") }) { Text("Open character recovery") }
            PlayerOutlinedButton(onClick = { open("Campaign backup") }) { Text("Open campaign recovery") }
        }
    }
}
