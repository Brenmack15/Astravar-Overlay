package com.yazsras.astravar.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Persisted code35/schema4 evidence is built independently from the new matching helper. Fictional records only. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignArchiveSchemaCompatibilityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: CampaignLedgerDb
    private lateinit var repo: CampaignLedgerRepository
    private val name="fictional-archive-schema4-to5.db"
    private val campaign="fictional-legacy-campaign"
    private val source="Verified fictional code35 source"
    @Before fun open() { db=Room.inMemoryDatabaseBuilder(context,CampaignLedgerDb::class.java).allowMainThreadQueries().build();repo=CampaignLedgerRepository(db) }
    @After fun close() {db.close();context.deleteDatabase(name)}
    private fun stateHashAtSchema4(value:LedgerExport):String = campaignHash(campaignJson.encodeToString(value.copy(schema=4,exportedAt=0,
        archive=value.archive.copy(receipts=emptyList(),backups=emptyList()))))
    private fun payload(id:String="fictional-note",mapped:LedgerExport?=null):CampaignArchivePayload {
        val body="Fictional café 星図 🧭 source; no gameplay event occurs."
        return CampaignArchivePayload(campaign,"Fictional preserved archive",1,"https://example.invalid/root","code35-source-revision",
            listOf(CampaignArchiveRecord(id,"Fictional source note","Chronicle",body,"{}","https://example.invalid/$id","original-source-revision",campaignHash(body),visibility="player")),mapped)
    }
    private fun envelope(value:CampaignArchivePayload):String {
        val original=campaignJson.encodeToString(value)
        return campaignJson.encodeToString(CampaignArchiveEnvelope("astravar-campaign-archive",1,campaignHash(original),original))
    }
    private suspend fun seedWallet() = repo.party.saveWallet(PartyWallet("fictional-wallet",name="Fictional café purse",gold=10,campaignId=campaign),source=source,commandId="code35-wallet")
    private data class LegacyEvidence(val receipt:CampaignImportReceipt,val backup:CampaignImportBackup,val sourceRow:CampaignArchiveSource)
    private suspend fun seedPersistedCode35Import():LegacyEvidence {
        seedWallet()
        val before=CampaignBackup(db).snapshot(includeBackups=false)
        val oldSnapshot=before.copy(schema=4,exportedAt=0,archive=before.archive.copy(backups=emptyList()))
        val oldText=campaignJson.encodeToString(oldSnapshot)
        assertTrue(oldText.contains("\"schema\":4"));assertFalse(oldText.contains("\"platinum\""));assertFalse(oldText.contains("\"electrum\""))
        val value=payload();val original=campaignJson.encodeToString(value);val hash=campaignHash(original)
        val backup=CampaignImportBackup(hash,oldText,campaignHash(oldText),stateHashAtSchema4(before),123)
        db.archiveDao().insert(backup)
        val reference=value.records.single()
        db.archiveDao().insert(CampaignReferenceRecord("$campaign::${reference.externalId}",campaign,reference.externalId,reference.title,reference.category,
            reference.body,reference.propertiesJson,reference.sourceUrl,reference.sourceRevision,reference.contentSha256,hash))
        val originalSource=CampaignArchiveSource(hash,original);db.archiveDao().insert(originalSource)
        val after=CampaignBackup(db).snapshot(includeBackups=false)
        val receipt=CampaignImportReceipt(hash,campaign,value.title,456,1,0,"APPLIED",stateHashAtSchema4(after),"Original code35 fictional import receipt")
        db.archiveDao().insert(receipt)
        return LegacyEvidence(receipt,backup,originalSource)
    }
    /** Recreate the checked-in frozen Room4 schema and copy only its original columns before opening Room5. */
    private fun migrateFrozenRoom4() {
        context.deleteDatabase(name)
        val path="schemas/com.yazsras.astravar.data.CampaignLedgerDb/4.json"
        val schema=JSONObject(listOf(File(path),File("app/$path")).first{it.exists()}.readText()).getJSONObject("database")
        val old=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
        val entities=schema.getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
            fun sql(value:String)=value.replace("\${TABLE_NAME}",table)
            old.execSQL(sql(entity.getString("createSql")))
            val indices=entity.optJSONArray("indices");if(indices!=null)for(j in 0 until indices.length())old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            val fields=entity.getJSONArray("fields");val columns=(0 until fields.length()).map{fields.getJSONObject(it).getString("columnName")}
            db.openHelper.readableDatabase.query("SELECT "+columns.joinToString(","){"\"$it\""}+" FROM \"$table\"").use {cursor ->
                while(cursor.moveToNext()) {
                    val row=ContentValues()
                    columns.forEachIndexed {index,column -> when(cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> row.putNull(column)
                        Cursor.FIELD_TYPE_INTEGER -> row.put(column,cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(column,cursor.getDouble(index))
                        Cursor.FIELD_TYPE_BLOB -> row.put(column,cursor.getBlob(index))
                        else -> row.put(column,cursor.getString(index))
                    }}
                    old.insertOrThrow(table,null,row)
                }
            }
        }
        old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        old.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")));old.version=4;old.close();db.close()
        db=Room.databaseBuilder(context,CampaignLedgerDb::class.java,name).addMigrations(CAMPAIGN_MIGRATION_4_5, CAMPAIGN_MIGRATION_5_6).allowMainThreadQueries().build()
        repo=CampaignLedgerRepository(db);assertEquals(6,db.openHelper.writableDatabase.version)
    }
    private suspend fun rejects(action:suspend()->Unit) {var failed=false;try{action()}catch(_:IllegalStateException){failed=true};assertTrue("Recovery must preserve changed campaign records",failed)}

    @Test fun frozenRoom4ReceiptAndBackupVerifyAndRollbackAcrossRoom5WithoutRewritingEvidence() = runBlocking {
        val legacy=seedPersistedCode35Import();migrateFrozenRoom4()
        assertEquals(legacy.backup,db.archiveDao().backup(legacy.backup.packageHash));assertEquals(legacy.receipt,db.archiveDao().receipt(legacy.receipt.packageHash))
        val verified=repo.archive.verify(legacy.receipt.packageHash);assertTrue(verified.errors.toString(),verified.valid)
        assertEquals(legacy.sourceRow,db.archiveDao().source(legacy.sourceRow.packageHash))
        val recovered=repo.archive.rollback(legacy.receipt.packageHash,true)
        assertEquals("ROLLED_BACK",recovered.state);assertEquals(legacy.backup.sourceStateSha256,recovered.readbackSha256)
        assertEquals(legacy.receipt,db.archiveDao().receipt(legacy.receipt.packageHash));assertEquals(legacy.backup,db.archiveDao().backup(legacy.backup.packageHash))
        assertEquals(2,db.archiveDao().allReceipts().size);assertTrue(db.archiveDao().allReferences().isEmpty())
        assertEquals(10L,db.partyDao().wallet("fictional-wallet")?.gold);assertNull(db.partyDao().wallet("fictional-wallet")?.electrum);assertNull(db.partyDao().wallet("fictional-wallet")?.platinum)
    }

    @Test fun oldSchema4PreimportBackupIsReusableAgainstFreshSchema5ReviewAndRemainsByteExact() = runBlocking {
        seedWallet();val incoming=payload("fictional-new-note");val text=envelope(incoming)
        val before=CampaignBackup(db).snapshot(includeBackups=false)
        val originalSnapshot=before.copy(schema=4,exportedAt=0,archive=before.archive.copy(backups=emptyList()))
        val oldText=campaignJson.encodeToString(originalSnapshot)
        val initial=repo.archive.preview(text);assertTrue(initial.conflicts.toString(),initial.valid)
        val old=CampaignImportBackup(initial.fingerprint,oldText,campaignHash(oldText),stateHashAtSchema4(before),123)
        assertNotEquals(old.sourceStateSha256,initial.currentFingerprint)
        db.archiveDao().insert(old)
        assertEquals(oldText,repo.archive.exportPreImportBackup(initial.fingerprint))
        val imported=repo.archive.commit(initial,true);assertTrue(repo.archive.verify(imported.packageHash).valid)
        repo.archive.rollback(imported.packageHash,true)
        assertEquals(old,db.archiveDao().backup(old.packageHash));assertEquals(imported,db.archiveDao().receipt(imported.packageHash))
        assertEquals(10L,db.partyDao().wallet("fictional-wallet")?.gold)
    }

    @Test fun knownZeroAndNonzeroNewDenominationsBlockLegacyReceiptFallbackWithoutChangingOtherMetadata() = runBlocking {
        val legacy=seedPersistedCode35Import();val wallet=requireNotNull(db.partyDao().wallet("fictional-wallet"))
        for((electrum,platinum) in listOf(0L to null,null to 0L,2L to null,null to 3L)) {
            val changed=wallet.copy(electrum=electrum,platinum=platinum)
            db.partyDao().put(changed) // Preserve revision/source intentionally: the changed coin itself must be detected.
            val verify=repo.archive.verify(legacy.receipt.packageHash)
            assertFalse(verify.valid);assertTrue(verify.errors.any{it.contains("changed after")})
            rejects{repo.archive.rollback(legacy.receipt.packageHash,true)}
            assertEquals(changed,db.partyDao().wallet(wallet.id));assertEquals(legacy.receipt,db.archiveDao().receipt(legacy.receipt.packageHash));assertEquals(legacy.backup,db.archiveDao().backup(legacy.backup.packageHash))
            assertEquals(legacy.sourceRow,db.archiveDao().source(legacy.sourceRow.packageHash));assertNull(db.archiveDao().receipt("rollback:${legacy.receipt.packageHash}"))
        }
    }

    @Test fun schema5MappedElectrumAndPlatinumImportAndRollbackWhileSchema4CannotClaimThem() = runBlocking {
        val wallet=PartyWallet("fictional-new-wallet",name="Fictional new purse",gold=3,source=source,campaignId=campaign,electrum=0,platinum=7)
        val mapped=LedgerExport(schema=5,exportedAt=1,items=emptyList(),events=emptyList(),records=CampaignRecordsExport(wallets=listOf(wallet)))
        val legacy=repo.archive.preview(envelope(payload(mapped=mapped.copy(schema=4))))
        assertFalse(legacy.valid);assertTrue(legacy.conflicts.toString(),legacy.conflicts.any{it.contains("schema 5")})
        val incoming=payload(mapped=mapped);val preview=repo.archive.preview(envelope(incoming))
        assertTrue(preview.conflicts.toString(),preview.valid);repo.archive.exportPreImportBackup(preview.fingerprint)
        val receipt=repo.archive.commit(preview,true)
        assertEquals(wallet,db.partyDao().wallet(wallet.id));assertTrue(repo.archive.verify(receipt.packageHash).valid)
        assertEquals(campaignJson.encodeToString(incoming),db.archiveDao().source(receipt.packageHash)?.payloadJson)
        repo.archive.rollback(receipt.packageHash,true);assertNull(db.partyDao().wallet(wallet.id));assertEquals(receipt,db.archiveDao().receipt(receipt.packageHash))
    }
}
