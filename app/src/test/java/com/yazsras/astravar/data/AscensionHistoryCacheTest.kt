package com.yazsras.astravar.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class AscensionHistoryCacheTest {
    @Test fun versionTwoCacheMigratesOfflineWithUnknownDatesLastAndStableEqualTimestampPages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-ascension-history-v2.db"
        val binding = CloudBinding("https://fixture.invalid", "campaign", "device:fixture")
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.execSQL("CREATE TABLE records(token TEXT,id TEXT,kind TEXT,life TEXT,actor TEXT,title TEXT,body TEXT,PRIMARY KEY(token,id))")
                old.execSQL("CREATE TABLE workspace(slot INTEGER PRIMARY KEY,origin TEXT,continuity TEXT,subject TEXT,epoch INTEGER,revision INTEGER,grant_revision INTEGER,grant_expiry INTEGER,checked_at INTEGER,token TEXT)")
                old.execSQL("INSERT INTO workspace VALUES(1,?,?,?,1,1,1,1000,1,'old')", arrayOf(binding.origin,binding.continuity,binding.subject))
                old.execSQL("CREATE TABLE commands(id TEXT PRIMARY KEY,origin TEXT,continuity TEXT,subject TEXT,envelope TEXT)")
                old.execSQL("CREATE TABLE fenced(continuity TEXT PRIMARY KEY)"); old.execSQL("INSERT INTO fenced VALUES('campaign')")
                old.execSQL("CREATE TABLE image_cache(id TEXT PRIMARY KEY, body BLOB)")
                listOf("d" to null, "c" to 9L, "b" to 11L, "a" to 11L).forEach { (id,time) ->
                    val row = buildJsonObject { put("id",id); put("data",buildJsonObject { time?.let { put("recordedAt", it) } }); put("revision",1) }
                    old.execSQL("INSERT INTO records VALUES('old',?,'roll','active','actor','Roll',?)",arrayOf(id,row.toString()))
                }
                old.version = 2
            }
            CloudSnapshotCache(context,name).use { upgraded ->
                val first = upgraded.actorHistory(binding,1,"actor",setOf("roll"),1,null)
                assertEquals("b",first.single().cloudText("id"))
                val rest = upgraded.actorHistory(binding,1,"actor",setOf("roll"),4,cloudHistoryCursor(first.single()))
                assertEquals(listOf("a","c","d"),rest.map { it.cloudText("id") })
                assertNull(cloudHistoryCursor(rest.last()).recordedAt)
                assertTrue(upgraded.actorHistory(binding,1000,"actor",setOf("roll"),4,null).isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
}
