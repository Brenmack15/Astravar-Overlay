package com.yazsras.astravar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import com.yazsras.astravar.BuildConfig
import me.pushy.sdk.Pushy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
    internal val fields = setOf("type", "version", "issuedAt", "expiresAt", "wakeId", "signature")
    fun verified(data: Map<String, String>, credentialHash: String, now: Long): Boolean = runCatching {
        require(data.keys == fields && data.values.all { it.length <= 256 })
        require(data["type"] == TYPE && data["version"] == "2")
        require(UUID.fromString(data.getValue("wakeId")).toString() == data["wakeId"])
        val issued = data.getValue("issuedAt").toLong(); val expires = data.getValue("expiresAt").toLong()
        require(issued > 0 && issued <= now + 60_000 && expires > now && expires > issued && expires - issued <= 30_000)
        require(credentialHash.matches(Regex("[a-f0-9]{64}")))
        val input = listOf("type", "version", "issuedAt", "expiresAt", "wakeId")
            .joinToString("\n") { data.getValue(it) }.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(credentialHash.toByteArray(Charsets.UTF_8), "HmacSHA256")) }.doFinal(input)
        val signature = Base64.decode(data.getValue("signature"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(signature.size == 32 && MessageDigest.isEqual(mac, signature))
        true
    }.getOrDefault(false)
}

/** Optional, device-consented socket wake transport. It never carries campaign records.
 * SDK registration runs independently: a missing Pushy account cannot block Cloudflare sync. */
object CampaignPushy {
    val configured: Boolean get() = !BuildConfig.DEBUG
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registering = AtomicBoolean(false)
    private val controlLock = Any()
    @Volatile private var nextRegistrationAttemptAt = 0L
    private fun components(context: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        listOf("me.pushy.sdk.receivers.PushyBootReceiver", "me.pushy.sdk.receivers.PushyUpdateReceiver").forEach {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, it), state, PackageManager.DONT_KILL_APP)
        }
    }
    private fun configure(context: Context) {
        Pushy.toggleFCM(false, context)
        Pushy.togglePushPayloadLogging(false, context)
        Pushy.toggleForegroundService(false, context)
    }
    fun initialize(context: Context) {
        configure(context)
        // A validated enrollment must explicitly restart the SDK after process creation.
        // Boot/update receivers remain disabled until registration succeeds.
        if (!configured || !CampaignSyncPreferences(context).pushyEnabled || CampaignSyncPreferences(context).paused) disable(context)
    }
    fun requestToken(context: Context, binding: String) {
        val app = context.applicationContext
        if (!configured || !CampaignSyncPreferences(app).pushyEnabled || CampaignSyncPreferences(app).paused || !registering.compareAndSet(false, true)) return
        scope.launch {
            try {
                configure(app)
                val store = CampaignPushStore(app)
                val cached = store.token()
                // The SDK can clear its credentials after MQTT authorization failure while
                // Astravar retains its encrypted copy. A local token alone cannot restart it.
                val usableCached = cachedTokenForSdk(cached, Pushy.getDeviceCredentials(app)?.token)
                // Failed account/network setup is retried conservatively; it never stalls sync.
                if (usableCached == null && android.os.SystemClock.elapsedRealtime() < nextRegistrationAttemptAt) return@launch
                val token = usableCached ?: run {
                    nextRegistrationAttemptAt = android.os.SystemClock.elapsedRealtime() + 300_000
                    Pushy.register(app)
                }
                val active = synchronized(controlLock) {
                    val preferences = CampaignSyncPreferences(app)
                    if (!preferences.pushyEnabled || preferences.paused || !store.saveTokenIfBound(token, binding)) {
                        disable(app); false
                    } else {
                        Pushy.toggleNotifications(true, app)
                        components(app, true)
                        Pushy.listen(app)
                        true
                    }
                }
                // Cached SDK results must never recursively append another sync worker.
                if (active && shouldEnqueueTokenRefresh(cached, token)) CampaignConnectionSyncJob.enqueue(app)
            } catch (_: Exception) {
                // Authentication, missing provider setup, or connectivity leave Cloudflare operational.
            } finally { registering.set(false) }
        }
    }
    internal fun cachedTokenForSdk(cached: String?, sdkToken: String?): String? =
        cached?.takeIf { it.isNotBlank() && it == sdkToken }
    internal fun shouldEnqueueTokenRefresh(previous: String?, current: String) = previous != current
    fun disable(context: Context) = synchronized(controlLock) {
        runCatching { configure(context); Pushy.toggleNotifications(false, context); components(context, false) }
        Unit
    }
}

/** Pushy token is installation-sensitive and encrypted with a non-exportable Android Keystore key. */
class CampaignPushStore(context: Context, private val keyProvider: (() -> SecretKey)? = null) {
    private val prefs = context.applicationContext.getSharedPreferences("astravar-campaign-push", Context.MODE_PRIVATE)
    init {
        // Retire provider-specific token ciphertext and enrollment only. Timing and wake sequence
        // evidence survives the upgrade; campaign credentials and all Room stores are untouched.
        synchronized(storeLock) {
            if (prefs.getString("provider", "") != "pushy") {
                check(prefs.edit().remove("token").remove("registered").remove("wakes")
                    .remove("enrollmentBinding").remove("enrollmentExpiresAt").putString("provider", "pushy").commit())
            }
        }
    }
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
            updateAAD("astravar-pushy-v1".toByteArray()); doFinal(bytes.copyOfRange(12, bytes.size))
        }
        plain.toString(Charsets.UTF_8).also { plain.fill(0) }
    }.getOrNull()
    private fun saveToken(token: String) {
        require(token.matches(Regex("[A-Za-z0-9:_-]{16,4096}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD("astravar-pushy-v1".toByteArray()) }
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
    val lastReceivedAt: Long get() = prefs.getLong("lastReceivedAt", 0)
    // Drain identity must not depend on wall-clock time: clock corrections and two
    // wakes in the same millisecond must still produce distinct recovery work.
    val lastWakeSequence: Long get() = prefs.getLong("wakeSequence", 0)
    fun isWakeCompleted(sequence: Long): Boolean = sequence > 0 && sequence <= prefs.getLong("completedWakeSequence", 0)
    fun completeWake(sequence: Long): Unit = synchronized(storeLock) {
        require(sequence > 0 && sequence <= lastWakeSequence)
        val completed = prefs.getLong("completedWakeSequence", 0)
        if (sequence > completed) check(prefs.edit().putLong("completedWakeSequence", sequence).commit())
    }
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
        val sequence = Math.addExact(lastWakeSequence.coerceAtLeast(0), 1L)
        check(prefs.edit()
            .putString("wakes", (previous + (id to expiresAt)).takeLast(64).joinToString(",") { "${it.first}|${it.second}" })
            .putLong("wakeSequence", sequence).putLong("lastReceivedAt", now).commit())
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

/** Pushy 1.0.129 adds a raw JSON wrapper to its broadcast. It is transport metadata,
 * never parsed, logged or treated as authority. Every other field remains strictly checked. */
internal fun campaignPushyWakeData(intent: Intent): Map<String, String>? = runCatching {
    if (intent.action != "pushy.me") return null
    val extras = intent.extras ?: return null
    val keys = extras.keySet() - "__json"
    if (keys != CampaignWakeProtocol.fields) return null
    keys.associateWith { key -> (extras.get(key) as? String) ?: return null }
}.getOrNull()

/** Non-exported SDK receiver. Strict authenticated wake validation precedes constrained work. */
class AstravarPushyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "pushy.me" || !CampaignPushy.configured || !CampaignSyncPreferences(context).pushyEnabled || CampaignSyncPreferences(context).paused) return
        val data = campaignPushyWakeData(intent) ?: return
        // Local credential/Room verification only; network work never runs in the receiver.
        val accepted = runCatching { runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(2_000) {
                val client = CampaignConnectionClient(context.applicationContext)
                try { client.acceptWake(data) } finally { client.close() }
            } == true
        } }.getOrDefault(false)
        if (!accepted) return
        if (CampaignSyncNotifications.canShow(context)) CampaignSyncNotifications.wakeReceived(context)
        CampaignConnectionSyncJob.enqueue(context, wakeSequence = CampaignPushStore(context).lastWakeSequence)
    }
}
