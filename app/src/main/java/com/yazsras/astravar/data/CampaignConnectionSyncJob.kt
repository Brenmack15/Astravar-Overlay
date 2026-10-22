package com.yazsras.astravar.data

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/** Short-lived constrained recovery. WorkManager persists boot recovery; no perpetual dataSync service. */
class CampaignConnectionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean("manual", false)
        if (!manual && CampaignSyncPreferences(applicationContext).paused) return Result.success()
        val store = CampaignPushStore(applicationContext)
        val requestedSequence = inputData.getLong("wakeSequence", 0)
        // APPEND_OR_REPLACE closes the wake-vs-worker-completion race. A burst queued while an
        // earlier job is draining is coalesced here without skipping token/periodic/manual recovery.
        if (!manual && store.isWakeCompleted(requestedSequence)) return Result.success()
        val drainingWakeSequence = store.lastWakeSequence.coerceAtLeast(requestedSequence)
        store.lastWorkStartedAt = System.currentTimeMillis()
        store.recordStage("worker_started", store.lastWorkStartedAt)
        var client: CampaignConnectionClient? = null
        return try {
            client = CampaignConnectionClient(applicationContext)
            val snapshot = withTimeout(45_000) { client.foreground(manual = manual) }
            // A bounded drain may leave another byte-limited page. Retry without
            // completing this wake generation so a collapsed burst cannot strand it.
            if (snapshot["syncContinuationRequired"]?.jsonPrimitive?.booleanOrNull == true) return retryBounded()
            if (requestedSequence > 0) store.completeWake(drainingWakeSequence)
            Result.success()
        } catch (_: TimeoutCancellationException) { retryBounded() }
        catch (error: CancellationException) { throw error }
        catch (error: OwnerSyncFailure) {
            when (error.code) {
                "AUTOMATIC_SYNC_PAUSED" -> Result.success()
                "CREDENTIAL_REVOKED_OR_EXPIRED", "AUTHENTICATION_REQUIRED", "CAMPAIGN_NOT_AUTHORIZED", "CAMPAIGN_GRANT_EXPIRED", "CREDENTIAL_SCOPE_OR_EXPIRY" -> {
                    CampaignConnectionSyncJob.cancel(applicationContext); Result.failure()
                }
                "REMOTE_UNAVAILABLE", "SERVICE_UNAVAILABLE", "OPERATION_FAILED", "QUOTA_REACHED", "PRIVATE_GATEWAY_AUTH_REQUIRED" -> retryBounded()
                else -> Result.failure()
            }
        } catch (_: java.io.IOException) { retryBounded() }
        catch (_: Exception) { Result.failure() }
        finally { client?.close() }
    }
    private fun retryBounded() = if (runAttemptCount < 5) Result.retry() else Result.failure()
}

/** Legacy JobService remains declared so old OS job IDs can be retired safely on update. */
class CampaignConnectionSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean { schedule(applicationContext); return false }
    override fun onStopJob(params: JobParameters) = false
    companion object {
        const val WAKE = "astravar-campaign-wake-v1"
        const val MANUAL = "astravar-campaign-manual-v1"
        const val PERIODIC = "astravar-campaign-recovery-v1"
        private const val LEGACY_ID = 0x415343
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun enqueue(context: Context, manual: Boolean = false, expedited: Boolean = false, wakeSequence: Long = 0) {
            if (!manual && CampaignSyncPreferences(context).paused) return
            val request = OneTimeWorkRequestBuilder<CampaignConnectionWorker>().setConstraints(network)
                .setInputData(workDataOf("manual" to manual, "wakeSequence" to wakeSequence))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("astravar-campaign-sync")
            if (expedited && android.os.Build.VERSION.SDK_INT >= 31 && CampaignSyncNotifications.canShow(context)) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(context).enqueueUniqueWork(if (manual) MANUAL else WAKE,
                ExistingWorkPolicy.APPEND_OR_REPLACE, request.build())
        }
        fun schedule(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(LEGACY_ID)
            if (CampaignSyncPreferences(context).paused) { cancel(context); return }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CampaignConnectionWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag("astravar-campaign-sync").build())
        }
        /** Pause never deletes queued commands or credentials. Explicit manual work remains available. */
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(LEGACY_ID)
            WorkManager.getInstance(context).cancelUniqueWork(WAKE)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
