package com.yazsras.astravar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.yazsras.astravar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wake signals are hints only. No command, item, source prose or gameplay data is accepted here. */
object CampaignWakeProtocol {
    const val TYPE = "ASTRAVAR_CAMPAIGN_SYNC_WAKE"
    private val fields = setOf("type", "version", "campaignId", "credentialId", "grantId", "grantRevision", "issuedAt", "expiresAt", "wakeId", "signature")
    fun verified(data: Map<String, String>, campaignId: String, credentialId: String, grantId: String,
                 grantRevision: Long, credentialHash: String, now: Long): Boolean = runCatching {
        require(data.keys == fields && data.values.all { it.length <= 256 })
        require(data["type"] == TYPE && data["version"] == "1")
        require(data["campaignId"] == campaignId && data["credentialId"] == credentialId && data["grantId"] == grantId)
        require(data.getValue("grantRevision") == grantRevision.toString())
        require(UUID.fromString(data.getValue("wakeId")).toString() == data["wakeId"])
        val issued = data.getValue("issuedAt").toLong(); val expires = data.getValue("expiresAt").toLong()
        require(issued > 0 && issued <= now + 60_000 && expires > now && expires > issued && expires - issued <= 30_000)
        require(credentialHash.matches(Regex("[a-f0-9]{64}")))
        val input = listOf("type", "version", "campaignId", "credentialId", "grantId", "grantRevision", "issuedAt", "expiresAt", "wakeId")
            .joinToString("\n") { data.getValue(it) }.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(credentialHash.toByteArray(Charsets.UTF_8), "HmacSHA256")) }.doFinal(input)
        val signature = Base64.decode(data.getValue("signature"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(signature.size == 32 && MessageDigest.isEqual(mac, signature))
        true
    }.getOrDefault(false)
}

/** No Firebase installation/token traffic is initiated until a valid campaign has been enrolled. */
object CampaignFirebase {
    val configured: Boolean get() = BuildConfig.ASTRAVAR_FIREBASE_APP_ID.isNotBlank() &&
        BuildConfig.ASTRAVAR_FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.ASTRAVAR_FIREBASE_SENDER_ID.isNotBlank() &&
        BuildConfig.ASTRAVAR_FIREBASE_API_KEY.isNotBlank()
    @Synchronized fun messaging(context: Context): FirebaseMessaging? {
        if (!configured) return null
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?:
            FirebaseApp.initializeApp(context.applicationContext, FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.ASTRAVAR_FIREBASE_APP_ID).setProjectId(BuildConfig.ASTRAVAR_FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.ASTRAVAR_FIREBASE_SENDER_ID).setApiKey(BuildConfig.ASTRAVAR_FIREBASE_API_KEY).build())
        require(app.options.gcmSenderId == BuildConfig.ASTRAVAR_FIREBASE_SENDER_ID)
        return FirebaseMessaging.getInstance().also { it.isAutoInitEnabled = true }
    }
    fun disable(context: Context) {
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) return
        runCatching { FirebaseMessaging.getInstance().apply { isAutoInitEnabled = false; deleteToken() } }
    }
}

/** FCM token is installation-sensitive and encrypted with a non-exportable Android Keystore key. */
class CampaignPushStore(context: Context, private val keyProvider: (() -> SecretKey)? = null) {
    private val prefs = context.applicationContext.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE)
    companion object { private val storeLock = Any() }
    private fun key(): SecretKey = keyProvider?.invoke() ?: synchronized(storeLock) {
        val alias = "astravar-campaign-push-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun token(): String? = runCatching {
        val raw = prefs.getString("token", null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP); require(bytes.size >= 29)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD("astravar-fcm-v1".toByteArray()); doFinal(bytes.copyOfRange(12, bytes.size))
        }
        plain.toString(Charsets.UTF_8).also { plain.fill(0) }
    }.getOrNull()
    private fun saveToken(token: String) {
        require(token.matches(Regex("[A-Za-z0-9:_-]{40,4096}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD("astravar-fcm-v1".toByteArray()) }
        val bytes = token.toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(bytes); bytes.fill(0)
        check(prefs.edit().putString("token", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).remove("registered").commit())
    }
    /** A verified enrollment activates callbacks; revocation invalidates them atomically with deletion. */
    fun bindEnrollment(binding: String, expiresAt: Long) = synchronized(storeLock) {
        require(binding.matches(Regex("[a-f0-9]{64}")) && expiresAt > 0)
        check(prefs.edit().putString("enrollmentBinding", binding).putLong("enrollmentExpiresAt", expiresAt).commit())
    }
    fun saveTokenIfBound(token: String, binding: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(storeLock) {
        if (binding.isBlank() || prefs.getString("enrollmentBinding", "") != binding || prefs.getLong("enrollmentExpiresAt", 0) <= now) return@synchronized false
        if (token() != token) saveToken(token)
        true
    }
    var registration: String
        get() = prefs.getString("registered", "") ?: ""
        set(value) { check(prefs.edit().putString("registered", value).commit()) }
    var lastReceivedAt: Long
        get() = prefs.getLong("lastReceivedAt", 0)
        set(value) { prefs.edit().putLong("lastReceivedAt", value).apply() }
    var lastWorkStartedAt: Long
        get() = prefs.getLong("lastWorkStartedAt", 0)
        set(value) { prefs.edit().putLong("lastWorkStartedAt", value).apply() }
    var lastRoomCommittedAt: Long
        get() = prefs.getLong("lastRoomCommittedAt", 0)
        set(value) { prefs.edit().putLong("lastRoomCommittedAt", value).apply() }
    fun claimWake(id: String, expiresAt: Long, now: Long): Boolean = synchronized(storeLock) {
        val previous = (prefs.getString("wakes", "") ?: "").split(',').mapNotNull { entry ->
            val pair = entry.split('|'); if (pair.size != 2) null else pair[1].toLongOrNull()?.let { pair[0] to it }
        }.filter { it.second > now }
        if (previous.any { it.first == id }) return@synchronized false
        check(prefs.edit().putString("wakes", (previous + (id to expiresAt)).takeLast(64).joinToString(",") { "${it.first}|${it.second}" }).commit())
        true
    }
    fun recordStage(stage: String, at: Long, identity: String? = null) = synchronized(storeLock) {
        require(stage in setOf("push_received", "worker_started", "room_committed", "receipt_verified"))
        val prior = runCatching { Json.parseToJsonElement(prefs.getString("timings", "[]")!!).jsonArray }.getOrDefault(JsonArray(emptyList()))
        val event = buildJsonObject { put("stage", stage); put("deviceAt", at); identity?.let { put("identity", it.take(64)) } }
        prefs.edit().putString("timings", JsonArray((prior + event).takeLast(256)).toString()).apply()
    }
    fun timings(): JsonArray = runCatching { Json.parseToJsonElement(prefs.getString("timings", "[]")!!).jsonArray }.getOrDefault(JsonArray(emptyList()))
    fun clearRegistration() = synchronized(storeLock) {
        check(prefs.edit().remove("registered").remove("wakes").remove("enrollmentBinding").remove("enrollmentExpiresAt").commit())
    }
    fun forgetToken() = synchronized(storeLock) {
        check(prefs.edit().remove("registered").remove("wakes").remove("enrollmentBinding").remove("enrollmentExpiresAt").remove("token").commit())
    }
    override fun toString() = "CampaignPushStore(redacted)"
}

/** GMS delivers DATA-only messages to this non-exported service. Authentication is checked again locally. */
class AstravarMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (!CampaignFirebase.configured) return
        val binding = runCatching { runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(2_000) {
                val client = CampaignConnectionClient(applicationContext)
                try { client.currentPushBinding() } finally { client.close() }
            }
        } }.getOrNull() ?: return
        val saved = runCatching { CampaignPushStore(this).saveTokenIfBound(token, binding) }.getOrDefault(false)
        if (saved && !CampaignSyncPreferences(this).paused) CampaignConnectionSyncJob.enqueue(this)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if (!CampaignFirebase.configured || CampaignSyncPreferences(this).paused || message.notification != null ||
            message.from != BuildConfig.ASTRAVAR_FIREBASE_SENDER_ID) return
        // Local credential/Room verification only; network work never runs inside onMessageReceived.
        val accepted = runCatching { runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(2_000) {
                val client = CampaignConnectionClient(applicationContext)
                try { client.acceptWake(message.data) } finally { client.close() }
            } == true
        } }.getOrDefault(false)
        if (!accepted) return
        val visible = CampaignSyncNotifications.canShow(this)
        if (visible) CampaignSyncNotifications.wakeReceived(this)
        CampaignConnectionSyncJob.enqueue(this, expedited = visible && message.priority == RemoteMessage.PRIORITY_HIGH, wakeAt = CampaignPushStore(this).lastReceivedAt)
    }
}
