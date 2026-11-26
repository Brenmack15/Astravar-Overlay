package com.yazsras.astravar.ui

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudArchiveSelectionTest {
    private fun row(id: String, life: String = "active", revision: Int = 3) = buildJsonObject {
        put("id", id); put("kind", "world_entity"); put("title", "Synthetic annotation")
        put("life", life); put("revision", revision)
    }

    private fun inspect(listed: JsonObject, stored: JsonObject = listed, legacy: Int = 0) =
        ArchiveChoice.reviewed(listed, buildJsonObject {
            put("record", stored)
            put("legacyImmutableReceipts", legacy)
        })

    @Test fun selectionRejectsChangedIdRevisionOrDisposition() {
        val first = row("one")
        assertThrows(IllegalArgumentException::class.java) { inspect(first, row("other")) }
        assertThrows(IllegalArgumentException::class.java) { inspect(first, row("one", revision = 4)) }
        assertThrows(IllegalArgumentException::class.java) { inspect(first, row("one", life = "archived")) }
        val selection = inspect(first)
        assertEquals("one", selection.id)
        assertEquals(3L, selection.revision)
    }

    @Test fun draftsIdentifyExactCloudObjectsAndEnforceSeparateArchiveAndRestore() {
        val first = inspect(row("one"))
        val second = inspect(row("two"))
        val archived = inspect(row("archived-one", life = "archived"))
        val parameters = archiveCommandArgs("archive_records", listOf(first, second))
        assertEquals(setOf("records"), parameters.keys)
        val entries = parameters.getValue("records").jsonArray.map { it.jsonObject }
        assertEquals(listOf("one", "two"), entries.map { it.getValue("id").jsonPrimitive.content })
        assertTrue(entries.all { it.keys == setOf("id", "kind", "revision") })
        assertThrows(IllegalArgumentException::class.java) { archiveCommandArgs("archive_records", listOf(first, first)) }
        assertThrows(IllegalArgumentException::class.java) { archiveCommandArgs("archive_records", listOf(archived)) }
        assertThrows(IllegalArgumentException::class.java) { archiveCommandArgs("restore_records", listOf(first)) }
        assertEquals(1, archiveCommandArgs("restore_records", listOf(archived)).getValue("records").jsonArray.size)
    }

    @Test fun deletionRequiresExplicitExternalAndApplicableLegacyAcknowledgements() {
        val recent = inspect(row("recent"))
        val old = inspect(row("old"), legacy = 2)
        assertThrows(IllegalArgumentException::class.java) { archiveCommandArgs("permanently_delete_records", listOf(recent)) }
        assertThrows(IllegalArgumentException::class.java) {
            archiveCommandArgs("permanently_delete_records", listOf(old), externalAccepted = true)
        }
        val newer = archiveCommandArgs("permanently_delete_records", listOf(recent), externalAccepted = true)
        assertFalse("acknowledgeLegacyReceipts" in newer)
        val older = archiveCommandArgs("permanently_delete_records", listOf(recent, old), externalAccepted = true, legacyAccepted = true)
        assertEquals("PERMANENT_DELETE", older.getValue("confirmation").jsonPrimitive.content)
        assertTrue(older.getValue("acknowledgeExternalBackups").jsonPrimitive.boolean)
        assertTrue(older.getValue("acknowledgeLegacyReceipts").jsonPrimitive.boolean)
    }
}
