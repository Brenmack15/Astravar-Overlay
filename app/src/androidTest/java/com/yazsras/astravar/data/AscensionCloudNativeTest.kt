package com.yazsras.astravar.data

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** Keystore-backed production client and cache on the reserved, disposable Android 17 TLS guest. */
@RunWith(AndroidJUnit4::class)
class AscensionCloudNativeTest {
    private lateinit var context: Context
    private lateinit var config: JsonObject
    private lateinit var originalTls: SSLSocketFactory
    private val origin = "https://hp-bridge.example"
    private val scope = "qa-api37-reconnect"
    private val actor = "native-holder"
    private val base = "/v3/continuities/$scope"

    @Before fun verifyGuest() {
        context = ApplicationProvider.getApplicationContext()
        assertEquals("com.yazsras.astravar.v2uidebug",context.packageName)
        assertEquals(37,Build.VERSION.SDK_INT)
        assertEquals("17",Build.VERSION.RELEASE)
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertEquals("1",device.executeShellCommand("getprop ro.kernel.qemu").trim())
        config=Json.parseToJsonElement(File(context.filesDir,"ci-https-config.json").readText()).jsonObject
        assertEquals(origin,config.cloudText("origin"))
        originalTls=HttpsURLConnection.getDefaultSSLSocketFactory()
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val ca=CertificateFactory.getInstance("X.509").generateCertificate(config.cloudText("certificatePem").byteInputStream())
        store.setCertificateEntry("ephemeral-api37",ca)
        val trusts=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null,trusts.trustManagers,null) }.socketFactory)
    }

    @After fun restoreTls() {
        if(::originalTls.isInitialized)HttpsURLConnection.setDefaultSSLSocketFactory(originalTls)
    }

    private fun owner(path:String, payload:JsonObject):JsonObject {
        val connection=URL("$origin/__ci/owner").openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false
        connection.connectTimeout=12000
        connection.readTimeout=12000
        connection.requestMethod="POST"
        connection.doOutput=true
        connection.setRequestProperty("Content-Type","application/json")
        connection.setRequestProperty("X-Astravar-Ci-Control",config.cloudText("controlToken"))
        val envelope=buildJsonObject { put("method","POST");put("path",path);put("body",payload) }
        return try {
            connection.outputStream.use { it.write(envelope.toString().toByteArray()) }
            assertTrue("Private fixture owner HTTP $path",connection.responseCode in 200..299)
            connection.inputStream.use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
        } finally { connection.disconnect() }
    }

    private fun evidence(name:String,value:JsonObject) {
        val dir=File(requireNotNull(context.getExternalFilesDir(null)),"ascension-cloud").apply { mkdirs() }
        File(dir,"$name.json").writeText(value.toString())
    }

    @Test fun pairAndCachePreStopState():Unit=runBlocking {
        val bootstrap=OwnerConnectionBootstrap(context)
        val fragment=URI(bootstrap.begin(origin,scope)).rawFragment.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val bundle=owner("/owner/bootstrap/bundle",buildJsonObject { put("origin",origin);fragment.forEach { (k,v)->put(k,v) } })
        val enrollment=bootstrap.importEncryptedBundle(bundle.toString().toByteArray())
        val vault=OwnerSyncCredentialVault(context)
        bootstrap.completeImport(enrollment) { vault.storeServiceAccess(origin,scope,enrollment.serviceAccessBearer) }
        val secret=Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val transport=OwnerConnectionHttpsTransport()
        suspend fun device(path:String, body:JsonObject):JsonObject {
            val result=transport.request(origin,path,null,vault.serviceAccess(origin,scope),body.toString())
            assertTrue("Private fixture device HTTPS $path",result.status in 200..299)
            return result.json
        }
        val pairing=device("/v1/pairings",buildJsonObject {
            put("campaignId",scope);put("profileId",actor);put("deviceName","Fictional API 37")
            put("deviceSecretHash",OwnerSyncProtocol.hash(secret))
            putJsonObject("campaignAccess") { putJsonArray("readScopes") { add("character") };putJsonArray("writeScopes") {} }
        })
        owner("/v1/owner/pairings/approve",buildJsonObject { put("userCode",pairing.getValue("userCode")) })
        val token=device("/v1/pairings/token",buildJsonObject { put("deviceCode",pairing.getValue("deviceCode"));put("deviceSecret",secret) })
        assertEquals("PAIRED",token.cloudText("status"))
        assertEquals(scope,token.cloudText("campaignId"))
        val id=token.cloudText("credentialId")
        vault.enroll(OwnerSyncCredential(id,scope,origin,token.getValue("expiresAt").jsonPrimitive.long,secret))
        val grants=listOf("character.read","character.write","inventory.read","economy.read","resources.read","chronicle.read","world.read","approval.read")
        val grant=owner("$base/grants/device:$id",buildJsonObject {
            put("scopes",JsonArray(grants.map(::JsonPrimitive)));putJsonArray("actors"){add("*")};putJsonArray("auto"){add("character.write")}
            put("expiresAt",System.currentTimeMillis()+86400000);put("expectedGrantRevision",0);put("revoked",false)
        })
        assertEquals(false,grant.getValue("revoked").jsonPrimitive.boolean)
        val db=CampaignConnectionDb.open(context)
        try { db.connection().settings(CampaignConnectionSettings(origin=origin,campaignId=scope,deviceId=id,profileId=actor,deviceName="Fictional API 37")) }
        finally { db.close() }
        val client=CloudCampaignClient.open(context)
        val snapshot=client.refresh(openExisting=true)
        assertEquals(4L,snapshot.version.revision)
        assertEquals(12,client.record(actor)!!.getValue("data").jsonObject.getValue("abilities").jsonObject.getValue("INT").jsonPrimitive.int)
        assertNull(client.record("native-key"))
        assertNull(client.record("native-event"))
        evidence("before-stop",buildJsonObject { put("api",Build.VERSION.SDK_INT);put("epoch",snapshot.version.epoch);put("revision",snapshot.version.revision);put("credentialBacked",true) })
    }

    @Test fun reconnectVerifyEachReceiptAndCommitDeviceEdit():Unit=runBlocking {
        val client=CloudCampaignClient.open(context)
        val before=requireNotNull(client.workspace())
        assertEquals(4L,before.version.revision)
        val fresh=client.refresh()
        assertEquals(8L,fresh.version.revision)
        assertEquals(1L,fresh.version.epoch)
        val records=client.records()
        assertEquals(records.size,records.map{it.cloudText("id")}.toSet().size)
        fun data(id:String)=records.single{it.cloudText("id")==id}.getValue("data").jsonObject
        assertEquals(13,data(actor).getValue("abilities").jsonObject.getValue("INT").jsonPrimitive.int)
        assertEquals(5,data("native-wallet").getValue("coins").jsonObject.getValue("gp").jsonPrimitive.int)
        assertEquals(1,data("native-pool").getValue("current").jsonPrimitive.int)
        assertEquals(1,data("native-key").getValue("quantity").jsonPrimitive.int)
        assertEquals("confirmed",data("native-event").cloudText("status"))
        assertEquals(1,records.count { it.cloudText("kind")=="item_event" })
        assertEquals(1,records.count { it.cloudText("kind")=="coin_transaction" })
        val ids=listOf("native-change-actor","native-atomic-purchase","native-spend-charge","native-timeline-event")
        val receipts=ids.mapIndexed { i,commandId ->
            val committed=client.verify(commandId)
            assertEquals("COMMITTED",committed.cloudText("status"))
            val receipt=committed.getValue("receipt").jsonObject
            assertEquals(5L+i,receipt.getValue("revision").jsonPrimitive.long)
            receipt.cloudText("transactionId")
        }
        assertEquals(4,receipts.toSet().size)
        assertEquals(records,client.records())
        assertNull(client.record("native-approved"))
        assertEquals(1,client.reviewCount())
        val pending=client.verify("native-pending-world")
        assertEquals("PENDING_APPROVAL",pending.cloudText("status"))
        val decided=owner("$base/approvals/native-pending-world/verify",buildJsonObject { put("digest",pending.getValue("digest")) })
        assertEquals("COMMITTED",decided.cloudText("status"))
        assertEquals("COMMITTED",client.verify("native-pending-world").cloudText("status"))
        val reviewed=client.refresh()
        assertEquals(9L,reviewed.version.revision)
        assertNotNull(client.record("native-approved"))
        val args=buildJsonObject { put("actorId",actor);put("ability","INT");put("score",14) }
        val outcome=client.submit("set_actor_ability",args,reviewed,"native-return-edit")
        assertEquals("COMMITTED",outcome.cloudText("status"))
        assertEquals(outcome,client.verify("native-return-edit"))
        val finalState=client.refresh()
        assertEquals(10L,finalState.version.revision)
        evidence("after-reconnect",buildJsonObject {
            put("api",Build.VERSION.SDK_INT);put("priorCacheRevision",before.version.revision);put("reconnectedRevision",fresh.version.revision)
            put("dmTransactionIds",JsonArray(receipts.map(::JsonPrimitive)));put("pendingBeforeOwner",pending.cloudText("status"))
            put("ownerRevision",reviewed.version.revision);put("deviceRevision",finalState.version.revision)
            put("deviceTransactionId",outcome.getValue("receipt").jsonObject.getValue("transactionId"))
        })
    }
}
