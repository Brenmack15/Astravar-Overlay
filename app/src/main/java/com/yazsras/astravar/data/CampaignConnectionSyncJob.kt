package com.yazsras.astravar.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** Persisted OS retries synchronize only the owner-enrolled campaign and granted record categories. */
class CampaignConnectionSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            var client: CampaignConnectionClient? = null
            var retry = false
            try { client = CampaignConnectionClient(applicationContext); client.foreground() }
            catch (e: CancellationException) { throw e }
            catch (e: OwnerSyncFailure) {
                retry = e.code in setOf("REMOTE_UNAVAILABLE", "SERVICE_UNAVAILABLE", "OPERATION_FAILED", "QUOTA_REACHED")
                if (e.code in setOf("CREDENTIAL_REVOKED_OR_EXPIRED", "AUTHENTICATION_REQUIRED", "CAMPAIGN_NOT_AUTHORIZED")) cancel(applicationContext)
                // A visible conflict or a transient service failure does not erase the persisted periodic job.
            }
            catch (_: java.io.IOException) { retry = true }
            catch (_: Exception) { /* Preserve periodic recovery; foreground diagnostics retain the concrete issue. */ }
            finally { client?.close(); jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val ID = 0x415343
        fun schedule(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(ID, ComponentName(context, CampaignConnectionSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(ID) }
    }
}
