package com.yazsras.astravar.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** Persisted OS retries synchronize only the owner-enrolled HP profile. */
class LiveHpConnectionSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            var client: LiveHpConnectionClient? = null
            var retry = false
            try { client = LiveHpConnectionClient(applicationContext); client.foreground() }
            catch (e: CancellationException) { throw e }
            catch (e: OwnerSyncFailure) { retry = e.code in setOf("REMOTE_UNAVAILABLE", "QUOTA_REACHED"); if (!retry) cancel(applicationContext) }
            catch (_: java.io.IOException) { retry = true }
            catch (_: Exception) { cancel(applicationContext) }
            finally { client?.close(); jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val ID = 0x415348
        fun schedule(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(ID, ComponentName(context, LiveHpConnectionSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(ID) }
    }
}
