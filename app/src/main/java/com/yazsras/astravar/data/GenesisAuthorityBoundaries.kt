package com.yazsras.astravar.data

/** Partial files remain valid for historical stores, but never sever portable connected authority. */
internal suspend fun genesisRequirePartialBackupAllowed(db: CampaignLedgerDb) {
    require(!db.genesisAuthorityDao().hasAuthority()) {
        "This database contains connected campaign records. Use Complete connected backup to preserve both gameplay and campaign authority together."
    }
}
internal fun genesisRequireUnmanagedPlayable(value: ReforgeBackup) {
    require(value.changes.none { it.operation.startsWith(GENESIS_AUTHORITY_SOURCE) } && value.characters.none {
        it.saved().origin.label == GENESIS_AUTHORITY_SOURCE
    }) { "This partial file contains connected gameplay. Restore its Complete connected backup with portable authority instead." }
}
internal fun genesisRequireUnmanagedCampaign(value: LedgerExport) {
    require(value.events.none { it.source == GENESIS_AUTHORITY_SOURCE } && value.items.none { it.source == GENESIS_AUTHORITY_SOURCE } &&
        value.records.audit.none { it.source == GENESIS_AUTHORITY_SOURCE } && value.records.characterProfiles.none { it.source == GENESIS_AUTHORITY_SOURCE }) {
        "This partial file contains connected campaign projections. Restore its Complete connected backup with portable authority instead."
    }
}

/** Authority parents share a global command namespace with gameplay/source receipts and roll IDs. */
internal fun genesisRejectAuthorityCommandReuse(db: CampaignLedgerDb, vararg commandIds: String) {
    commandIds.distinct().forEach { commandId ->
        val exists = db.openHelper.writableDatabase.query(
            "SELECT EXISTS(SELECT 1 FROM genesis_authority_receipts WHERE commandId=?)", arrayOf(commandId)
        ).use { check(it.moveToFirst()); it.getInt(0) != 0 }
        require(!exists) { "Command identity belongs to an existing connected-authority decision. Nothing changed." }
    }
}
