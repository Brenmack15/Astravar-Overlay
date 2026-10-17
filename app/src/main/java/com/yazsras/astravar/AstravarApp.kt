package com.yazsras.astravar

import android.app.Application
import com.yazsras.astravar.data.*
import kotlinx.coroutines.*

class AstravarApp: Application() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val repository by lazy { Repository(AstravarDb.open(this),scope) }
    val preferences by lazy { Preferences(this) }
    // Campaign items are isolated from the existing Room v3 weapon/combat database.
    val campaignLedger by lazy { CampaignLedgerRepository(CampaignLedgerDb.open(this)) }
}
