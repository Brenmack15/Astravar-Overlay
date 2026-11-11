package com.yazsras.astravar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yazsras.astravar.AstravarApp
import com.yazsras.astravar.data.GenesisStorageStatus

/** Loading never masquerades as an empty roster; recovery never opens the old store for writes. */
@Composable internal fun GenesisStorageRecovery(app: AstravarApp, status: GenesisStorageStatus) {
    val storage = app.genesisStorage
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    if (page != null) {
        BackHandler { page = null }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { page = null }) { Text("Back to storage recovery") }
            Box(Modifier.weight(1f)) {
                when (page) {
                    "Campaign" -> CampaignBackupPageV2(storage.campaign)
                    "Weapon" -> {
                        val current by app.repository.state.collectAsState()
                        val failure by app.repository.error.collectAsState()
                        val scope = rememberCoroutineScope()
                        val actions = remember(app.repository, scope) { Actions(app.repository, scope) }
                        val saved = current
                        if (saved == null) Text(failure ?: "Opening weapon records…", Modifier.padding(24.dp))
                        else Page { BackupPage(saved, actions) }
                    }
                    "Help" -> HelpPage()
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Preparing your characters", style = MaterialTheme.typography.headlineSmall)
        if (status == GenesisStorageStatus.Loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Moving playable records into campaign storage. Your original database is retained.")
        } else if (status is GenesisStorageStatus.RecoveryRequired) {
            Text("Storage needs review", style = MaterialTheme.typography.titleLarge)
            Text(status.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("Your records have not been reset. The original playable database is retained. Retry after resolving the storage issue; do not uninstall Astravar.")
            Button(onClick = storage::retry) { Text("Retry storage recovery") }
            OutlinedButton(onClick = { page = "Campaign" }) { Text("Open campaign backup") }
            OutlinedButton(onClick = { page = "Weapon" }) { Text("Open weapon backup") }
            TextButton(onClick = { page = "Help" }) { Text("Help and diagnostics") }
        }
    }
}
