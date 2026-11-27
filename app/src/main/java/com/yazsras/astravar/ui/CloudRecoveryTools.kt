package com.yazsras.astravar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.AstravarApp

private enum class LocalRecoveryPage(val label: String) {
    COMPLETE("Complete backup"),
    CHARACTER("Character backup"),
    CAMPAIGN("Campaign backup"),
    IMPORT("Campaign import"),
    ARMOR("Original armor archive")
}

/** An enrolled device may recover its original local stores without ever publishing them. */
@Composable
internal fun CloudRecoveryTools() {
    val app = LocalContext.current.applicationContext as AstravarApp
    var selected by rememberSaveable { mutableStateOf<LocalRecoveryPage?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    Column(Modifier.fillMaxSize()) {
        Text(
            "These tools read and restore the pre-migration records kept on this device. Astravar Cloud uses its separate owner-authorized backup and recovery process.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = RebuildPalette.Muted
        )
        val page = selected
        if (page != null) {
            PlayerTextButton({ selected = null }) { Text("Back to local recovery") }
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                null -> Page {
                    for (choice in LocalRecoveryPage.entries) {
                        PlayerEntry(choice.label, "", "History", { selected = choice })
                    }
                }
                LocalRecoveryPage.COMPLETE -> GenesisUnifiedBackupPage(app.genesisStorage.backup)
                LocalRecoveryPage.CHARACTER -> ReforgeBackupPage(app.genesisStorage.playable)
                LocalRecoveryPage.CAMPAIGN -> CampaignBackupPageV2(app.campaignLedger)
                LocalRecoveryPage.IMPORT -> CampaignArchiveImportV21(app.campaignLedger)
                LocalRecoveryPage.ARMOR -> {
                    val repository = app.repository
                    val state by repository.state.collectAsState()
                    val error by repository.error.collectAsState()
                    val scope = rememberCoroutineScope()
                    val actions = remember(repository, scope) { Actions(repository, scope) }
                    when (val weapon = state) {
                        null -> if (error != null) {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                        } else {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        }
                        else -> Page { BackupPage(weapon, actions) }
                    }
                }
            }
        }
    }
}
