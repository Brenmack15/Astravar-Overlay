package com.yazsras.astravar.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Historical actors/items stay unenrolled until an explicit user command; this migration copies nothing. */
val CAMPAIGN_MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `genesis_campaigns` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `notes` TEXT NOT NULL, `revision` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `commandId` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `genesis_actor_authority` (`actorId` TEXT NOT NULL, `role` TEXT NOT NULL, `campaignId` TEXT, `ownerActorId` TEXT, `playable` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `activationCommandId` TEXT NOT NULL, `lastCommandId` TEXT NOT NULL, `playableRevision` INTEGER, `projectionFingerprint` TEXT, `resourceBindingsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`actorId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `genesis_item_authority` (`itemId` TEXT NOT NULL, `ownerActorId` TEXT, `definitionJson` TEXT NOT NULL, `instanceJson` TEXT NOT NULL, `revision` INTEGER NOT NULL, `activationCommandId` TEXT NOT NULL, `sourceProofJson` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_genesis_item_authority_ownerActorId` ON `genesis_item_authority` (`ownerActorId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `genesis_authority_receipts` (`commandId` TEXT NOT NULL, `kind` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `requestJson` TEXT NOT NULL, `outcomeJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`commandId`))")
    }
}
