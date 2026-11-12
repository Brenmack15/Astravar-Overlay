package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yazsras.astravar.rules.reforge.ItemDefinition
import com.yazsras.astravar.rules.reforge.ItemInstance
import com.yazsras.astravar.rules.reforge.ReforgeCharacter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class GenesisAuthorityLargeCampaignTest {
    @Test fun retainedArchivesBeyondCompleteDocumentCapDoNotBlockAffectedRecordCommands() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fictional-genesis-large-campaign.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).allowMainThreadQueries().build()
        try {
            val source = "Fictional large archive fixture"
            val body = "x".repeat(2_000_000)
            val hash = campaignHash(body)
            // Simulate retained sources accumulated across multiple historical imports. Each
            // source fits its original row contract while the complete archive exceeds 64 MiB.
            repeat(34) { index ->
                db.archiveDao().insert(CampaignReferenceRecord("retained-$index", "retained-campaign", "reference-$index",
                    "Fictional retained reference $index", "Notes", body, "{}", "https://example.invalid/fixture/$index",
                    "revision-1", hash, "fixture-package-$index"))
            }
            fun retainedBytes() = db.openHelper.writableDatabase.query("SELECT sum(length(CAST(body AS BLOB))) FROM campaign_references").use { it.moveToFirst(); it.getLong(0) }
            assertTrue(retainedBytes() > GENESIS_AUTHORITY_DOCUMENT_BYTES)
            val campaign = CampaignLedgerRepository(db)
            val authority = GenesisAuthorityRepository(db)
            val playable = ReforgeRepository(db)
            val world = authority.createCampaign("Fictional archive-scale campaign", commandId = "large-campaign")
            val actor = authority.createActor("Fictional scout", GenesisActorRole.PLAYER, world.id,
                playable = ReforgeCharacter("large-scout", "Fictional scout"), commandId = "large-scout-create")
            val added = authority.createItem(actor.actor.actorId, actor.playable!!.revision,
                ItemDefinition("large-lantern-template", "Fictional lantern"), ItemInstance("large-lantern", "large-lantern-template"), "large-item")
            val npc = authority.createActor("Fictional contact", GenesisActorRole.NPC, world.id, commandId = "large-npc")
            authority.updateActorAssociation(npc.actor.actorId, npc.actor.revision, GenesisActorRole.NPC, null, null, "large-unassign")
            // Source-item acquisition uses an existing explicit actor decision, not a name alias.
            val id = "large-existing-owner"
            campaign.records.saveProfile(CharacterProfile(id = id, name = "Fictional existing owner"), source = source, commandId = "large-existing-profile")
            val item = campaign.create(LedgerDraft("Fictional source compass", quantity = 2, itemState = "Carried", evidenceStatus = "Proposed", source = source),
                "large-source-item", "large-compass")
            campaign.party.moveItem(ItemMoveCommand(item.id, item.revision, holderProfileId = id, source = source, commandId = "large-source-placement"))
            playable.create(ReforgeCharacter(id, "Fictional existing owner"), "large-existing-playable")
            authority.acceptActorAuthority(authority.reviewActorAuthority(id, GenesisActorRole.PLAYER, world.id), "large-connect")
            val review = playable.reviewCampaignItem(campaign, id, item.id)
            val acquired = playable.acceptCampaignItem(campaign, review, "large-acquire")
            assertEquals(2, acquired.character.items.single().quantity)
            assertTrue(acquired.character.itemDefinitions.single().effects.isEmpty())
            assertEquals("Proposed", db.ledgerDao().getItem(item.id)?.evidenceStatus)
            assertEquals(68_000_000L, retainedBytes())
            val exportFailure = runCatching { authority.backup.exportWithProof() }.exceptionOrNull()
            assertNotNull(exportFailure)
            assertTrue(exportFailure!!.message.orEmpty(), exportFailure.message.orEmpty().contains("64 MiB"))
            db.close()
            db = Room.databaseBuilder(context, CampaignLedgerDb::class.java, name).allowMainThreadQueries().build()
            assertEquals(added, ReforgeRepository(db).get(actor.actor.actorId))
            assertEquals(acquired, ReforgeRepository(db).get(id))
            assertEquals(world.id, db.genesisAuthorityDao().actor(id)?.campaignId)
            assertNull(db.genesisAuthorityDao().actor(npc.actor.actorId)?.campaignId)
            assertEquals(68_000_000L, retainedBytes())
            db.archiveDao().allReferences().forEach { assertEquals(hash, campaignHash(it.body)); assertEquals(hash, it.contentSha256) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
