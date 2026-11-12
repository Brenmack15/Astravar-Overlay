package com.yazsras.astravar.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds empty encounter references only. No existing actor, initiative or campaign event is inferred. */
val CAMPAIGN_MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `genesis_encounters` (`id` TEXT NOT NULL, `campaignId` TEXT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT NOT NULL, `status` TEXT NOT NULL, `round` INTEGER NOT NULL, `currentActorId` TEXT, `revision` INTEGER NOT NULL, `creationCommandId` TEXT NOT NULL, `lastCommandId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`campaignId`) REFERENCES `genesis_campaigns`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_genesis_encounters_campaignId` ON `genesis_encounters` (`campaignId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `genesis_encounter_participants` (`encounterId` TEXT NOT NULL, `actorId` TEXT NOT NULL, `initiative` INTEGER, `position` INTEGER NOT NULL, PRIMARY KEY(`encounterId`, `actorId`), FOREIGN KEY(`encounterId`) REFERENCES `genesis_encounters`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`actorId`) REFERENCES `genesis_actor_authority`(`actorId`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_genesis_encounter_participants_actorId` ON `genesis_encounter_participants` (`actorId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_genesis_encounter_participants_encounterId_position` ON `genesis_encounter_participants` (`encounterId`, `position`)")
    }
}
