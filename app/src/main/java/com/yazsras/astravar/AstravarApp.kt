package com.yazsras.astravar

import android.app.Application
import com.yazsras.astravar.data.*
import kotlinx.coroutines.*

class AstravarApp: Application() {
    override fun onCreate() { super.onCreate(); CampaignPushy.initialize(this) }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val repository by lazy { Repository(AstravarDb.open(this),scope) }
    val preferences by lazy { Preferences(this) }
    // Campaign items are isolated from the existing Room v3 weapon/combat database.
    val campaignLedger by lazy { CampaignLedgerRepository(CampaignLedgerDb.open(this)) }
    // Physically isolated fictional public connectivity lab; no campaign record serialization.
    val sandboxSync by lazy { SandboxSyncRepository(SandboxSyncDb.open(this)) }
    // Explicit opt-in, disposable-profile commands; no real campaign serialization.
    val remoteStats by lazy { RemoteStatRepository(RemoteStatDb.open(this), campaignLedger.records, sandboxSync) }
}
