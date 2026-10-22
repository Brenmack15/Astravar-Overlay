package com.yazsras.astravar.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Room+journal integration with an isolated in-memory protocol fixture; not hosted/physical-device evidence. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CampaignConnectionTest {
    private lateinit var context: Context
    private lateinit var journal: CampaignConnectionDb
    private lateinit var campaign: CampaignLedgerDb
    private lateinit var vault: OwnerSyncCredentialVault
    private val origin = "https://campaign-sync.example"
    private val campaignId = "local-campaign"
    private val profileId = "fixture-traveller"
    private val deviceId = "fixture-device"
    private var time = 1_900_000_000_000L
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric does not automatically run manifest ContentProvider startup.
        // Native instrumentation uses the real AndroidX Startup provider.
        runCatching { androidx.work.WorkManager.getInstance(context) }.getOrElse {
            androidx.startup.AppInitializer.getInstance(context).initializeComponent(androidx.work.WorkManagerInitializer::class.java)
        }
        CampaignSyncPreferences(context).paused = false
        CampaignSyncPreferences(context).notificationsEnabled = false
        CampaignSyncPreferences(context).pushyEnabled = true
        context.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE).edit().clear().commit()
        journal = Room.inMemoryDatabaseBuilder(context, CampaignConnectionDb::class.java).build()
        campaign = Room.inMemoryDatabaseBuilder(context, CampaignLedgerDb::class.java).build()
        vault = OwnerSyncCredentialVault(context, { time }) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        vault.forgetAll()
        CampaignRecordsRepository(campaign).saveProfile(CharacterProfile(id=profileId,name="Fixture traveller",currentHp=17,maximumHp=29,temporaryHp=4,level=9),source="Isolated fixture")
        Unit
    }
    @After fun close() { journal.close(); campaign.close() }
    private fun client(remote: Remote, crash: suspend () -> Unit = {}) = CampaignConnectionClient(context,journal,campaign,vault,remote,{ time },crash)
    private suspend fun current() = requireNotNull(campaign.recordsDao().characterProfile(profileId))
    private suspend fun paired(remote: Remote): CampaignConnectionClient {
        val scopes = CampaignConnectionProtocol.scopeArray(CampaignConnectionProtocol.categories).toString()
        vault.enroll(OwnerSyncCredential(deviceId,campaignId,origin,time+1_000_000,"q".repeat(43)))
        journal.connection().settings(CampaignConnectionSettings(origin=origin,campaignId=campaignId,profileId=profileId,deviceName="Fixture device",deviceId=deviceId,
            requestedReadJson=scopes,requestedWriteJson=scopes,grantJson=remote.grant.toString()))
        return client(remote).also { it.sync() }
    }
    @Test fun injectedTransportFixturesNeverEnrollProductionPushy() = runBlocking {
        val remote = Remote()
        val c = paired(remote)
        c.sync()
        val pushPreferences = context.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE)
        assertFalse(pushPreferences.contains("enrollmentBinding"))
        assertFalse(pushPreferences.contains("token"))
        assertFalse(c.snapshot().getValue("pushRegistered").jsonPrimitive.boolean)
        c.revoke()
        assertFalse(pushPreferences.contains("enrollmentBinding"))
        assertFalse(pushPreferences.contains("token"))
    }
    @Test fun pushyOptOutBlocksWakeButKeepsManualCloudflareSyncOperational() = runBlocking {
        val remote = Remote(); val c = paired(remote)
        CampaignSyncPreferences(context).pushyEnabled = false
        assertFalse(c.acceptWake(wake(remote)))
        c.setAutoSync(setOf("hp")); remote.propose(); c.sync()
        assertEquals(14, current().currentHp)
        assertFalse(c.snapshot().getValue("pushEnabled").jsonPrimitive.boolean)
    }
    @Test fun optionalSdkFailureCannotBlockAuthenticatedCampaignCommit() = runBlocking {
        val remote = Remote(); val c = paired(remote); c.setAutoSync(setOf("hp")); val command = remote.propose()
        var providerCalls = 0
        val failingProvider = CampaignConnectionClient(context, journal, campaign, vault, remote, { time },
            requestPushToken = { _, _ -> providerCalls++; throw IllegalArgumentException("Provider unavailable") })
        failingProvider.sync()
        assertEquals(1, providerCalls)
        assertEquals(14, current().currentHp)
        assertEquals("VERIFIED", journal.connection().command(command)!!.state)
        assertFalse(failingProvider.snapshot().getValue("pushRegistered").jsonPrimitive.boolean)
    }
    @Test fun standing_authorization_commits_once_and_independently_verifies_record_and_receipt() = runBlocking {
        val remote = Remote(); val c = paired(remote); c.setAutoSync(setOf("hp")); val id = remote.propose()
        c.sync(); c.sync()
        assertEquals(14,current().currentHp); assertEquals(2L,current().revision)
        assertEquals("VERIFIED",journal.connection().command(id)!!.state)
        assertEquals(1,remote.decisions.size); assertTrue(remote.receiptReads >= 1); assertTrue(remote.recordReads >= 1)
        assertNotNull(campaign.recordsDao().receipt(CampaignSyncDomain.receiptId(CampaignConnectionProtocol.localCommand(origin,campaignId,id))))
    }
    @Test fun manual_mode_retains_pending_transaction_until_native_decision() = runBlocking {
        val remote = Remote(); val c = paired(remote); val id = remote.propose()
        c.sync(); assertEquals(17,current().currentHp); assertEquals("PENDING_APPROVAL",journal.connection().command(id)!!.state)
        c.decide(id,true); assertEquals(14,current().currentHp); assertEquals("VERIFIED",journal.connection().command(id)!!.state)
    }
    @Test fun lost_ack_and_recreated_client_replays_frozen_decision_without_second_domain_write() = runBlocking {
        val remote = Remote(); val c = paired(remote); c.setAutoSync(setOf("hp")); val id=remote.propose(); remote.loseDecision=true
        assertTrue(runCatching { c.sync() }.isFailure)
        val frozen=journal.connection().command(id)!!.decisionBody
        assertEquals(14,current().currentHp); assertEquals("APPLIED_PENDING",journal.connection().command(id)!!.state)
        client(remote).sync(); assertEquals(frozen,remote.lastDecision); assertEquals(2L,current().revision)
        assertEquals("VERIFIED",journal.connection().command(id)!!.state); assertEquals(1,remote.decisions.size)
    }
    @Test fun process_death_between_domain_commit_and_journal_update_recovers_hashed_parent_receipt() = runBlocking {
        val remote = Remote(); val c=paired(remote); c.setAutoSync(setOf("hp")); val id=remote.propose()
        val interrupted=client(remote) { throw IOException("Injected process boundary") }
        assertTrue(runCatching { interrupted.sync() }.isFailure)
        assertEquals(2L,current().revision); assertEquals("APPLYING",journal.connection().command(id)!!.state)
        client(remote).sync(); assertEquals(2L,current().revision); assertEquals("VERIFIED",journal.connection().command(id)!!.state)
    }
    @Test fun stale_local_revision_rolls_back_and_returns_explicit_conflict() = runBlocking {
        val remote=Remote(); val c=paired(remote); c.setAutoSync(setOf("hp")); val id=remote.propose()
        CampaignRecordsRepository(campaign).saveProfile(current().copy(level=10),1,"Fixture concurrent edit")
        c.sync(); assertEquals(17,current().currentHp); assertEquals(10,current().level); assertEquals("CONFLICT",journal.connection().command(id)!!.state)
        assertEquals("CONFLICT",remote.decisions.getValue(id).getValue("status").jsonPrimitive.content)
    }
    @Test fun disabled_local_scope_blocks_automatic_apply_even_if_remote_grant_still_enabled() = runBlocking {
        val remote=Remote(); val c=paired(remote); c.setAutoSync(setOf("hp")); val old=journal.connection().settings()!!
        journal.connection().settings(old.copy(localAutoSyncJson="[]")); val id=remote.propose()
        c.sync(); assertEquals(17,current().currentHp); assertEquals("PENDING_APPROVAL",journal.connection().command(id)!!.state)
    }
    @Test fun forged_command_fingerprint_fails_before_local_commit() = runBlocking {
        val remote=Remote(); val c=paired(remote); c.setAutoSync(setOf("hp")); val id=remote.propose()
        remote.commands[id]=JsonObject(remote.commands.getValue(id)+("fingerprint" to JsonPrimitive("0".repeat(64))))
        assertTrue(runCatching { c.sync() }.isFailure); assertEquals(17,current().currentHp); assertEquals(1L,current().revision)
    }
    @Test fun lost_grant_response_then_disable_retries_first_frozen_command_before_next_revision() = runBlocking {
        val remote=Remote(); val c=paired(remote); remote.loseGrant=true
        c.setAutoSync(setOf("hp")); val frozen=journal.connection().settings()!!.grantBody
        assertNotNull(frozen); assertEquals(2L,remote.grant.getValue("revision").jsonPrimitive.long)
        c.setAutoSync(emptySet()); assertNull(journal.connection().settings()!!.grantBody)
        assertEquals(3L,remote.grant.getValue("revision").jsonPrimitive.long)
        assertEquals(JsonArray(emptyList()),remote.grant.getValue("autoSyncScopes")); assertEquals(frozen,remote.grantRequests[1])
    }
    @Test fun inconsistentProposedOutcomeRollsBackDomainWritesAuditsAndReceipts() = runBlocking {
        val remote=Remote();val c=paired(remote);c.setAutoSync(setOf("hp"));val id=remote.propose()
        val old=remote.commands.getValue(id);val op=old.getValue("operations").jsonArray.single().jsonObject
        val wrong=JsonObject(op+("proposedFields" to JsonObject(op.getValue("proposedFields").jsonObject+("currentHp" to JsonPrimitive(18)))))
        val changed=JsonObject(old+("operations" to JsonArray(listOf(wrong))))
        remote.commands[id]=JsonObject(changed+("fingerprint" to JsonPrimitive(CampaignConnectionProtocol.fingerprint(campaignId,changed))))
        val audit=campaign.recordsDao().allAudit();c.sync()
        assertEquals(17,current().currentHp);assertEquals(1L,current().revision);assertEquals(audit,campaign.recordsDao().allAudit())
        assertNull(campaign.recordsDao().receipt(CampaignSyncDomain.receiptId(CampaignConnectionProtocol.localCommand(origin,campaignId,id))))
        assertEquals("CONFLICT",journal.connection().command(id)!!.state)
    }
    @Test fun oversizedAtomicReadbackRollsBackEveryDomainWriteAndReceiptBeforeAcknowledgment() = runBlocking {
        repeat(5) { index -> CampaignRecordsRepository(campaign).saveProject(InventionProject("Fixture large project $index",description="x".repeat(16_000),id="fixture-large-$index"),source="Fixture setup") }
        val remote=Remote();val c=paired(remote);c.setAutoSync(setOf("engineering"));val id=UUID.randomUUID().toString()
        val ops=remote.records.filterKeys{it.first=="project"}.values.map { row -> val prior=row.getValue("fields").jsonObject
            buildJsonObject {put("type","set_record");put("kind","project");put("recordId",row.getValue("recordId"));put("expectedRevision",row.getValue("revision"));put("fields",buildJsonObject{put("status","Research")});put("scope","engineering");put("currentFields",prior);put("proposedFields",JsonObject(prior+("status" to JsonPrimitive("Research"))+("revision" to JsonPrimitive(row.getValue("revision").jsonPrimitive.long+1))));put("sideEffects",JsonArray(emptyList()))}
        }
        val body=buildJsonObject {put("commandId",id);put("campaignId",campaignId);put("grantRevision",remote.grant.getValue("revision"));put("operations",JsonArray(ops));put("source","Resolved fictional project progress");put("status","PENDING_DELIVERY");put("createdAt",time)}
        remote.commands[id]=JsonObject(body+("fingerprint" to JsonPrimitive(CampaignConnectionProtocol.fingerprint(campaignId,body))))
        val before=campaign.recordsDao().allAudit()
        c.sync()
        assertTrue(campaign.recordsDao().allProjects().all{it.status=="Proposed"&&it.revision==1L})
        assertEquals(before,campaign.recordsDao().allAudit())
        assertNull(campaign.recordsDao().receipt(CampaignSyncDomain.receiptId(CampaignConnectionProtocol.localCommand(origin,campaignId,id))))
        assertEquals("CONFLICT",journal.connection().command(id)!!.state)
        assertEquals(JsonArray(emptyList()),remote.decisions.getValue(id).getValue("records"))
    }
    @Test fun permission_revocation_is_immediate_locally_and_cannot_be_silently_expanded() = runBlocking {
        val remote=Remote(); val c=paired(remote); c.setAutoSync(setOf("hp")); remote.offline=true
        val narrower=CampaignConnectionProtocol.categories-setOf("hp")
        c.restrictPermissions(narrower,narrower)
        val snapshot=c.snapshot();assertFalse("hp" in CampaignConnectionProtocol.parseScopes(snapshot.getValue("localReadScopes")))
        assertFalse("hp" in CampaignConnectionProtocol.parseScopes(snapshot.getValue("localWriteScopes")))
        assertTrue(runCatching { c.restrictPermissions(CampaignConnectionProtocol.categories,CampaignConnectionProtocol.categories) }.isFailure)
        remote.offline=false;c.sync();assertFalse("hp" in CampaignConnectionProtocol.parseScopes(remote.grant.getValue("readScopes")))
        assertFalse("hp" in CampaignConnectionProtocol.parseScopes(remote.grant.getValue("autoSyncScopes")))
    }
    @Test fun lost_grant_ack_then_permission_revocation_preserves_original_request_and_new_revision() = runBlocking {
        val remote=Remote();val c=paired(remote);remote.loseGrant=true;c.setAutoSync(setOf("hp"))
        val frozen=journal.connection().settings()!!.grantBody
        val narrower=CampaignConnectionProtocol.categories-setOf("hp")
        c.restrictPermissions(narrower,narrower)
        assertEquals(frozen,remote.grantRequests[1]);assertNull(journal.connection().settings()!!.grantBody)
        assertEquals(3L,remote.grant.getValue("revision").jsonPrimitive.long)
        assertFalse("hp" in CampaignConnectionProtocol.parseScopes(remote.grant.getValue("readScopes")))
    }
    @Test fun missing_local_identity_is_removed_only_from_mirror_and_confirmed_absent() = runBlocking {
        val remote=Remote();val c=paired(remote)
        campaign.openHelper.writableDatabase.execSQL("DELETE FROM character_profiles WHERE id=?",arrayOf(profileId))
        c.sync();assertFalse(remote.records.containsKey("character-profile" to profileId))
        assertTrue(journal.connection().mirrors(origin,campaignId,deviceId).none {it.kind=="character-profile"&&it.recordId==profileId})
        assertTrue(campaign.recordsDao().allAudit().isNotEmpty())
    }
    @Test fun expired_grant_and_pending_revocation_never_claim_connected_or_apply() = runBlocking {
        val remote=Remote(); val c=paired(remote); time+=2_000_000
        assertFalse(c.snapshot().getValue("connected").jsonPrimitive.boolean)
        assertTrue(runCatching { c.sync() }.isFailure); assertEquals(17,current().currentHp)
    }
    @Test fun offline_revocation_is_pending_until_independent_server_confirmation() = runBlocking {
        val remote=Remote(); val c=paired(remote); remote.offline=true
        c.revoke(); assertTrue(journal.connection().settings()!!.revokePending); assertNotNull(vault.credential(origin,campaignId))
        assertFalse(c.snapshot().getValue("connected").jsonPrimitive.boolean)
        remote.offline=false; c.sync(); assertFalse(journal.connection().settings()!!.revokePending); assertNull(vault.credential(origin,campaignId))
    }
    private fun wake(remote: Remote, bearer: String = "q".repeat(43)): Map<String, String> {
        val data = linkedMapOf("type" to CampaignWakeProtocol.TYPE, "version" to "2",
            "issuedAt" to time.toString(), "expiresAt" to (time + 30_000).toString(), "wakeId" to UUID.randomUUID().toString())
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(OwnerSyncProtocol.hash(bearer).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }.doFinal(data.values.joinToString("\n").toByteArray(Charsets.UTF_8))
        return data + ("signature" to android.util.Base64.encodeToString(mac,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
    }
    @Test fun authenticatedWakeBindsCurrentCredentialGrantAndSurvivesClientRecreationWithoutReplay() = runBlocking {
        val remote = Remote(); val c = paired(remote); val signed = wake(remote)
        assertTrue(c.acceptWake(signed))
        assertFalse(client(remote).acceptWake(signed))
        assertFalse(c.acceptWake(wake(remote) + ("campaignId" to "foreign-campaign")))
        assertFalse(c.acceptWake(wake(remote) + ("signature" to "A".repeat(43))))
        assertFalse(c.acceptWake(wake(remote) + ("payload" to "no gameplay in push")))
        val stale = wake(remote); time += 30_001
        assertFalse(c.acceptWake(stale))
        val oldCredential = wake(remote)
        vault.enroll(OwnerSyncCredential(deviceId,campaignId,origin,time+1_000_000,"z".repeat(43)))
        assertFalse(c.acceptWake(oldCredential))
        assertTrue(c.acceptWake(wake(remote, "z".repeat(43))))
        assertEquals(17, current().currentHp)
        assertEquals(1L, current().revision)
    }
    @Test fun authenticatedWakeIsValidatedWhileForegroundNetworkDrainIsBlocked() = runBlocking {
        val remote = Remote(); val c = paired(remote); val signed = wake(remote)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        remote.blockPending = entered to release
        val foreground = async(Dispatchers.IO) { c.foreground() }
        try {
            withTimeout(5_000) { entered.await() }
            // The foreground method holds the execution mutex while waiting for transport.
            // A valid wake must still be authenticated locally before the receiver callback budget.
            assertTrue(withTimeout(1_000) { client(remote).acceptWake(signed) })
            assertFalse(withTimeout(1_000) { client(remote).acceptWake(signed) })
            assertEquals(17, current().currentHp)
            assertEquals(1L, current().revision)
        } finally {
            release.complete(Unit)
            withTimeout(5_000) { foreground.await() }
        }
    }
    @Test fun pauseBlocksAutomaticNetworkAndWakeWhileManualSyncRetainsQueueAndStandingGrant() = runBlocking {
        val remote = Remote(); val c = paired(remote); c.setAutoSync(setOf("hp")); val id = remote.propose()
        val originalGrant = remote.grant
        val signed = wake(remote)
        CampaignSyncPreferences(context).paused = true
        remote.offline = true
        assertFalse(c.acceptWake(signed))
        c.foreground()
        assertEquals(17, current().currentHp)
        assertTrue(remote.decisions.isEmpty())
        assertEquals(originalGrant, remote.grant)
        assertNotNull(vault.credential(origin,campaignId))
        remote.offline = false
        c.sync()
        assertEquals(14, current().currentHp)
        assertEquals("VERIFIED", journal.connection().command(id)!!.state)
        assertTrue(CampaignSyncPreferences(context).paused)
        assertEquals(originalGrant, remote.grant)
        CampaignSyncPreferences(context).paused = false
    }
    private suspend fun queueIndependentHpPages(remote: Remote, client: CampaignConnectionClient, count: Int): List<String> {
        val profiles = (0 until count).map { "fixture-paged-$it" }
        for (id in profiles) CampaignRecordsRepository(campaign).saveProfile(
            CharacterProfile(id=id,name=id,currentHp=17,maximumHp=29,temporaryHp=4,level=9),source="Isolated paging fixture")
        client.sync()
        remote.pendingLimit = 1
        remote.pendingReads = 0
        return profiles.map { remote.propose(it) }
    }
    @Test fun collapsedWakeDrainsByteBoundedAutomaticPagesInOneSync() = runBlocking {
        val remote = Remote(); val c = paired(remote); c.setAutoSync(setOf("hp"))
        val commands = queueIndependentHpPages(remote,c,3)
        val snapshot = c.foreground()
        assertEquals(3,remote.pendingReads)
        assertEquals(3,remote.decisions.size)
        assertTrue(commands.all { journal.connection().command(it)?.state == "VERIFIED" })
        assertFalse(snapshot.getValue("syncContinuationRequired").jsonPrimitive.boolean)
        assertFalse(snapshot.getValue("serverPendingUnfetched").jsonPrimitive.boolean)
    }
    @Test fun boundedAutomaticDrainRequestsDurableWorkerContinuationThenCompletesNextPass() = runBlocking {
        val remote = Remote(); val c = paired(remote); c.setAutoSync(setOf("hp"))
        val commands = queueIndependentHpPages(remote,c,5)
        val first = c.foreground()
        assertEquals(4,remote.pendingReads)
        assertEquals(4,remote.decisions.size)
        assertTrue(first.getValue("syncContinuationRequired").jsonPrimitive.boolean)
        assertTrue(first.getValue("serverPendingUnfetched").jsonPrimitive.boolean)
        assertEquals("Pending delivery · more queued transactions",first.getValue("status").jsonPrimitive.content)
        // The actual journal/server decisions are retained; the next recovery fetches only the remaining page.
        val recovered = client(remote).foreground()
        assertEquals(5,remote.decisions.size)
        assertTrue(commands.all { journal.connection().command(it)?.state == "VERIFIED" })
        assertFalse(recovered.getValue("syncContinuationRequired").jsonPrimitive.boolean)
        assertFalse(recovered.getValue("serverPendingUnfetched").jsonPrimitive.boolean)
    }
    @Test fun manualFirstPageWithMorePendingStopsWithoutAnAutomaticRetryLoop() = runBlocking {
        val remote = Remote(); val c = paired(remote)
        val commands = queueIndependentHpPages(remote,c,2)
        val snapshot = c.foreground()
        assertEquals(1,remote.pendingReads)
        assertTrue(remote.decisions.isEmpty())
        assertEquals("PENDING_APPROVAL",journal.connection().command(commands.first())?.state)
        assertNull(journal.connection().command(commands.last()))
        assertFalse(snapshot.getValue("syncContinuationRequired").jsonPrimitive.boolean)
        assertTrue(snapshot.getValue("serverPendingUnfetched").jsonPrimitive.boolean)
        assertEquals("Pending approval · additional queued transactions",snapshot.getValue("status").jsonPrimitive.content)
    }
    @Test fun pauseBetweenAutomaticPagesPreventsNextFetchAndPreservesRemainingCommands() = runBlocking {
        val remote = Remote(); val c = paired(remote); c.setAutoSync(setOf("hp"))
        val commands = queueIndependentHpPages(remote,c,2)
        remote.afterRecordRead = { CampaignSyncPreferences(context).paused = true }
        assertTrue(runCatching { c.foreground() }.isFailure)
        assertEquals(1,remote.pendingReads)
        assertEquals(1,remote.decisions.size)
        assertEquals("VERIFIED",journal.connection().command(commands.first())?.state)
        assertNull(journal.connection().command(commands.last()))
        remote.afterRecordRead = null
        CampaignSyncPreferences(context).paused = false
        client(remote).foreground()
        assertTrue(commands.all { journal.connection().command(it)?.state == "VERIFIED" })
        assertEquals(2,remote.decisions.size)
    }
    private inner class Remote: OwnerConnectionTransport {
        var grant=buildJsonObject { put("grantId","fixture-grant");put("revision",1);put("readScopes",CampaignConnectionProtocol.scopeArray(CampaignConnectionProtocol.categories));put("writeScopes",CampaignConnectionProtocol.scopeArray(CampaignConnectionProtocol.categories));put("autoSyncScopes",JsonArray(emptyList()));put("expiresAt",time+1_000_000) }
        val records=linkedMapOf<Pair<String,String>,JsonObject>()
        val publications=linkedMapOf<String,List<JsonObject>>()
        val commands=linkedMapOf<String,JsonObject>(); val decisions=linkedMapOf<String,JsonObject>()
        val grantCommands=mutableMapOf<String,String>();val grantRequests=mutableListOf<String>()
        var loseDecision=false;var loseGrant=false;var offline=false;var lastDecision:String?=null;var receiptReads=0;var recordReads=0
        var blockPending: Pair<CompletableDeferred<Unit>,CompletableDeferred<Unit>>? = null
        var pendingLimit = Int.MAX_VALUE
        var pendingReads = 0
        var afterRecordRead: (() -> Unit)? = null
        fun propose(targetProfileId: String = profileId):String {
            val id=UUID.randomUUID().toString();val row=records.getValue("character-profile" to targetProfileId)
            val fields=row.getValue("fields").jsonObject
            val body=buildJsonObject { put("commandId",id);put("campaignId",campaignId);put("grantRevision",grant.getValue("revision"));put("source","Explicit resolved fixture damage")
                put("operations",buildJsonArray { add(buildJsonObject {put("type","adjust_hp");put("recordId",targetProfileId);put("kind","character-profile");put("expectedRevision",row.getValue("revision"));put("fields",buildJsonObject {put("delta",-3)});put("scope","hp");put("currentFields",fields);put("proposedFields",JsonObject(fields+("currentHp" to JsonPrimitive(14))+("revision" to JsonPrimitive(row.getValue("revision").jsonPrimitive.long+1))));put("sideEffects",JsonArray(emptyList()))}) })
                put("status",if ("hp" in CampaignConnectionProtocol.parseScopes(grant.getValue("autoSyncScopes"))) "PENDING_DELIVERY" else "PENDING_APPROVAL");put("createdAt",time) }
            commands[id]=JsonObject(body+("fingerprint" to JsonPrimitive(CampaignConnectionProtocol.fingerprint(campaignId,body))))
            return id
        }
        override suspend fun request(origin:String,path:String,credential:OwnerSyncCredential?,serviceAccess:String?,body:String?):OwnerSyncResponse {
            if(offline)throw IOException("Fixture offline")
            require(credential?.credentialId==deviceId)
            fun answer(vararg values:Pair<String,JsonElement>)=OwnerSyncResponse(200,buildJsonObject {put("mode",CampaignConnectionProtocol.MODE);put("campaignId",campaignId);values.forEach{put(it.first,it.second)}})
            val b=body?.let{Json.parseToJsonElement(it).jsonObject}
            return when {
                path.endsWith("/grant")&&b==null -> answer("grant" to grant)
                path.endsWith("/grant") -> {
                    val id=b!!.getValue("commandId").jsonPrimitive.content;grantRequests+=body!!
                    if(!grantCommands.containsKey(id)){require(b.getValue("expectedRevision")==grant.getValue("revision"));grantCommands[id]=body
                        grant=JsonObject(grant+("revision" to JsonPrimitive(grant.getValue("revision").jsonPrimitive.long+1))+("autoSyncScopes" to b.getValue("autoSyncScopes"))+
                            listOf("readScopes","writeScopes").filter{b.containsKey(it)}.associateWith{b.getValue(it)})
                    }else require(grantCommands[id]==body)
                    if(loseGrant){loseGrant=false;throw IOException("Lost fixture grant response")};answer("grant" to grant)
                }
                path.endsWith("/pending") -> {
                    blockPending?.let { (entered, release) -> entered.complete(Unit); release.await() }
                    pendingReads++
                    val waiting = commands.filterKeys{it !in decisions}.values.toList()
                    answer("commands" to JsonArray(waiting.take(pendingLimit)),"hasMore" to JsonPrimitive(waiting.size > pendingLimit))
                }
                path.endsWith("/records") -> {
                    val rows=b!!.getValue("records").jsonArray.map {it.jsonObject}
                    (b["removed"] as? JsonArray).orEmpty().forEach { raw -> val r=raw.jsonObject;records.remove(r.getValue("kind").jsonPrimitive.content to r.getValue("recordId").jsonPrimitive.content) }
                    rows.forEach {r -> records[r.getValue("kind").jsonPrimitive.content to r.getValue("recordId").jsonPrimitive.content]=r}
                    publications[b.getValue("commandId").jsonPrimitive.content]=rows
                    answer("published" to b.getValue("records"),"publishedAt" to JsonPrimitive(time))
                }
                path.contains("/publications/") -> {val rows=publications.getValue(path.substringAfterLast('/')).map { row -> records.getValue(row.getValue("kind").jsonPrimitive.content to row.getValue("recordId").jsonPrimitive.content) }; answer("records" to JsonArray(rows))}
                path.contains("/records/") -> {recordReads++;val kind=path.substringAfter("/records/").substringBefore('/');val id=java.net.URLDecoder.decode(path.substringAfterLast('/'),"UTF-8");(records[kind to id]?.let { answer("records" to JsonArray(listOf(it))) } ?: OwnerSyncResponse(404,buildJsonObject {put("mode",CampaignConnectionProtocol.MODE);put("error",buildJsonObject{put("code","RECORD_NOT_PUBLISHED")})})).also { afterRecordRead?.invoke() }}
                path.endsWith("/decisions") -> {
                    lastDecision=body;val id=b!!.getValue("commandId").jsonPrimitive.content
                    val receipt=decisions.getOrPut(id) { b.getValue("records").jsonArray.forEach { val r=it.jsonObject;records[r.getValue("kind").jsonPrimitive.content to r.getValue("recordId").jsonPrimitive.content]=r }
                        JsonObject(b+("deviceCredentialId" to JsonPrimitive(deviceId))+("recordedAt" to JsonPrimitive(time))+("eventId" to JsonPrimitive(UUID.randomUUID().toString()))) }
                    if(loseDecision){loseDecision=false;throw IOException("Lost fixture decision response")};answer("receipt" to receipt)
                }
                path.contains("/receipts/") -> {receiptReads++;answer("receipt" to decisions.getValue(path.substringAfterLast('/')))}
                path.endsWith("/devices/revoke") -> answer("revoked" to JsonPrimitive(true),"credentialId" to JsonPrimitive(deviceId))
                else -> error("Unexpected fixture path $path")
            }
        }
    }
}
