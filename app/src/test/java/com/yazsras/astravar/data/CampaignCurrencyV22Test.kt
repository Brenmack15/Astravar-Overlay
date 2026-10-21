package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignCurrencyV22Test {
    private lateinit var db: CampaignLedgerDb
    private lateinit var ledger: CampaignLedgerRepository
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun open() { db=Room.inMemoryDatabaseBuilder(context,CampaignLedgerDb::class.java).allowMainThreadQueries().build(); ledger=CampaignLedgerRepository(db) }
    @After fun close() = db.close()
    private suspend fun rejects(action:suspend ()->Unit) { var failed=false; try{action()}catch (_:Exception){failed=true}; assertTrue(failed) }

    @Test fun omittedNewCoinDefaultsKeepExactLegacyFingerprintEncoding() {
        val command=CurrencyCommand("fictional-wallet",1,goldDelta=-2,source="Fictional purchase",commandId="")
        assertEquals("""{"walletId":"fictional-wallet","expectedRevision":1,"copperDelta":0,"silverDelta":0,"goldDelta":-2,"source":"Fictional purchase","commandId":""}""",campaignJson.encodeToString(command))
        val wallet=PartyWallet("fictional-wallet",name="Fictional purse",gold=3)
        assertEquals("""{"id":"fictional-wallet","holderProfileId":null,"name":"Fictional purse","copper":null,"silver":null,"gold":3,"revision":1,"source":"","updatedAt":0,"campaignId":"local-campaign"}""",campaignJson.encodeToString(wallet))
        assertNull(campaignJson.decodeFromString<PartyWallet>(campaignJson.encodeToString(wallet)).platinum)
    }

    @Test fun knownPlatinumCanBeSpentOnceAndUnknownElectrumNeverBecomesZero() = runBlocking {
        ledger.party.saveWallet(PartyWallet("fictional-wallet",name="Fictional purse",gold=3,platinum=7),source="Confirmed fictional opening")
        val command=CurrencyCommand("fictional-wallet",1,source="Confirmed fictional purchase",commandId="platinum-once",platinumDelta=-2)
        val receipt=ledger.party.adjustCurrency(command)
        assertEquals(receipt,ledger.party.adjustCurrency(command))
        val after=requireNotNull(db.partyDao().wallet("fictional-wallet"))
        assertEquals(5L,after.platinum); assertEquals(3L,after.gold); assertNull(after.electrum); assertEquals(2L,after.revision)
        rejects { ledger.party.adjustCurrency(command.copy(commandId="unknown-electrum",expectedRevision=2,electrumDelta=-1,platinumDelta=-1)) }
        assertEquals(5L,db.partyDao().wallet("fictional-wallet")?.platinum)
        assertNull(db.partyDao().wallet("fictional-wallet")?.electrum)
        val read=CampaignSyncDomain(db).snapshot("local-campaign").single {it.kind=="wallet"}
        assertEquals(JsonNull,read.fields["electrum"]); assertEquals(5L,read.fields["platinum"]?.jsonPrimitive?.long)
    }

    @Test fun newBackupRoundTripsAllFiveDenominationsAndLegacyBackupRetainsUnknowns() = runBlocking {
        ledger.party.saveWallet(PartyWallet("fictional-wallet",name="Fictional purse",copper=1,silver=2,gold=3,electrum=4,platinum=5),source="Confirmed fictional opening")
        val exported=ledger.export()
        val data=campaignDecodeBackup(exported)
        assertEquals(5,data.schema); assertEquals(4L,data.records.wallets.single().electrum); assertEquals(5L,data.records.wallets.single().platinum)
        val target=Room.inMemoryDatabaseBuilder(context,CampaignLedgerDb::class.java).allowMainThreadQueries().build()
        try {
            val repo=CampaignLedgerRepository(target); val preview=repo.previewImport(exported)
            assertTrue(preview.summary+preview.conflicts,preview.valid)
            repo.restore(preview,"fictional-five-coins-restore")
            assertEquals(data.records.wallets.single(),target.partyDao().allWallets().single())
        } finally {target.close()}
        val old=LedgerExport(schema=4,exportedAt=0,items=emptyList(),events=emptyList(),records=CampaignRecordsExport(wallets=listOf(PartyWallet("legacy-wallet",name="Legacy fictional purse",gold=3))))
        val decoded=campaignDecodeBackup(campaignJson.encodeToString(old))
        assertNull(decoded.records.wallets.single().electrum); assertNull(decoded.records.wallets.single().platinum)
    }

    @Test fun frozenSchemaFourPreservesKnownCoinsUnknownsAndReceiptBytesThroughFiveMigration() = runBlocking {
        val name="fictional-wallet-v4-v5.db"; context.deleteDatabase(name)
        val schemaPath="schemas/com.yazsras.astravar.data.CampaignLedgerDb/4.json"
        val schema=JSONObject(listOf(File(schemaPath),File("app/$schemaPath")).first {it.exists()}.readText()).getJSONObject("database")
        val old=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
        val entities=schema.getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val entity=entities.getJSONObject(i); val table=entity.getString("tableName")
            fun sql(raw:String)=raw.replace("\${TABLE_NAME}",table)
            old.execSQL(sql(entity.getString("createSql")))
            val indices=entity.optJSONArray("indices"); if(indices!=null) for(j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
        }
        old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        old.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")))
        old.execSQL("INSERT INTO party_wallets VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>("fictional-wallet",null,"Fictional purse",null,2,3,2,"Fictional old source",100,"local-campaign"))
        val command=CurrencyCommand("fictional-wallet",1,goldDelta=-2,source="Fictional purchase",commandId="legacy-expense")
        val receipt=CommandReceipt("legacy-expense",campaignHash(campaignJson.encodeToString(command.copy(commandId=""))),"CURRENCY","fictional-wallet",2,100,outcomeJson="""{"original":"exact legacy outcome"}""")
        old.execSQL("INSERT INTO command_receipts VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(receipt.commandId,receipt.fingerprint,receipt.kind,receipt.recordId,receipt.resultingRevision,receipt.createdAt,receipt.outputId,receipt.outcomeJson))
        old.version=4;old.close()
        val migrated=Room.databaseBuilder(context,CampaignLedgerDb::class.java,name).addMigrations(CAMPAIGN_MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            assertEquals(5,migrated.openHelper.writableDatabase.version)
            val wallet=migrated.partyDao().allWallets().single()
            assertNull(wallet.copper);assertEquals(2L,wallet.silver);assertEquals(3L,wallet.gold);assertNull(wallet.electrum);assertNull(wallet.platinum);assertEquals(2L,wallet.revision)
            assertEquals(receipt,migrated.recordsDao().receipt("legacy-expense"))
            assertEquals(receipt,PartyRecordsRepository(migrated).adjustCurrency(command))
            assertEquals(3L,migrated.partyDao().allWallets().single().gold)
            migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {assertEquals(0,it.count)}
        } finally {migrated.close();context.deleteDatabase(name)}
    }
}
