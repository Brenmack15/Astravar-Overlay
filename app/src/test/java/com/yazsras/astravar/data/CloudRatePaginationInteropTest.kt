package com.yazsras.astravar.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudRatePaginationInteropTest {
    @Test fun initialRefreshResumesThePinnedPageAfterTheServerRateWait(): Unit = runBlocking {
        val driver = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "backend/owner-bridge/fixtures/native-cloud-wire.mjs") }.first(File::isFile)
        val worker = ProcessBuilder("node", driver.absolutePath, "--rate-pagination")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val input = worker.inputStream.bufferedReader()
        val output = worker.outputStream.bufferedWriter()
        val cache = CloudSnapshotCache(ApplicationProvider.getApplicationContext<Context>(), null)
        try {
            fun read(): JsonObject {
                val obj = Json.parseToJsonElement(requireNotNull(input.readLine()) { "The isolated Worker stopped." }).jsonObject
                check("fixtureError" !in obj) { "The pagination fixture failed." }
                return obj
            }
            fun call(message: JsonObject): JsonObject {
                output.write(message.toString()); output.newLine(); output.flush()
                return read()
            }
            val pairing = read()
            val expiry = pairing.getValue("expiresAt").jsonPrimitive.long
            val vault = OwnerSyncCredential(pairing.cloudText("device"), pairing.cloudText("continuity"),
                pairing.cloudText("origin"), expiry, pairing.cloudText("bearer"))
            var now = expiry - 3_600_000L
            var attempts = 0
            var waits = 0
            var throttledPath: String? = null
            val transport = OwnerConnectionTransport { origin, path, credential, _, body ->
                assertEquals(vault.origin, origin)
                assertTrue(path.startsWith("/v3/continuities/${vault.campaignId}/state?limit=20"))
                assertNull("Initial refresh issued a gameplay command.", body)
                assertSame(vault, credential)
                throttledPath?.let { assertEquals("Retry changed the pinned page.", it, path) }
                attempts++
                val result = call(buildJsonObject {
                    put("call", "request"); put("route", path)
                    put("credentialId", vault.credentialId); put("token", vault.bearer)
                })
                val status = result.getValue("status").jsonPrimitive.int
                throttledPath = if (status == 429) path else null
                OwnerSyncResponse(status, result.getValue("json").jsonObject)
            }
            val client = CloudCampaignClient(cache, { CloudSession(vault, null) }, transport, { now },
                waitForRead = { milliseconds ->
                    assertEquals("Initial refresh published partial campaign state.", null, cache.workspace())
                    assertTrue(requireNotNull(throttledPath).matches(
                        Regex(".*after=page-[0-9]{4}&atRevision=1&atEpoch=1&grantRevision=1")))
                    assertTrue(milliseconds in 1L..60_000L)
                    val advanced = call(buildJsonObject {
                        put("call", "advanceRateTime"); putJsonObject("args") { put("milliseconds", milliseconds) }
                    })
                    val observed = advanced.getValue("now").jsonPrimitive.long
                    assertEquals(now + milliseconds, observed)
                    now = observed; waits++
                })
            val snapshot = client.refresh(openExisting = true)
            assertTrue(snapshot.visible); assertEquals(1L, snapshot.version.revision)
            val metrics = call(buildJsonObject { put("call", "rateMetrics") })
            val succeeded = metrics.getValue("successfulPages").jsonPrimitive.int
            val limited = metrics.getValue("limitedPages").jsonPrimitive.int
            assertEquals(123, succeeded); assertTrue(limited > 0)
            assertEquals(limited, waits); assertEquals(succeeded + limited, attempts)
            val expected = metrics.getValue("seededRecords").jsonPrimitive.int
            val ids = mutableListOf<String>()
            var next = ""
            do {
                val page = client.records(after = next, kinds = setOf("actor"))
                ids += page.map { it.cloudText("id") }
                next = page.lastOrNull()?.cloudText("id").orEmpty()
            } while (next.isNotEmpty())
            assertEquals((1..expected).map { "page-" + it.toString().padStart(4, '0') }, ids)
            assertEquals(succeeded + limited, attempts)
        } finally {
            output.close()
            if (!worker.waitFor(3, TimeUnit.SECONDS)) worker.destroyForcibly()
            input.close(); cache.close()
        }
    }
}
