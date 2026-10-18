package com.yazsras.astravar.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class UiDraftStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = value is String }
    private fun save(saver: Saver<UiDraftTextState, String>, state: UiDraftTextState): String =
        with(saver) { requireNotNull(saverScope.save(state)) }

    @Test fun oneMillionCharacterDraftHasACompactBundleTokenAndRestoresExactlyAcrossInstances() {
        val filesDir = temporary.newFolder("private")
        val original = "A".repeat(1_000_000)
        val firstSaver = uiDraftTextSaver(UiDraftTextStore(filesDir))
        val token = save(firstSaver, UiDraftTextState(original))
        assertTrue("The Activity Bundle contains only a compact reference", token.toByteArray().size < 128)
        assertFalse(token.contains(original.take(100)))
        assertEquals(1, File(filesDir, "ui-drafts").listFiles()!!.size)
        val secondSaver = uiDraftTextSaver(UiDraftTextStore(filesDir))
        val restored = requireNotNull(secondSaver.restore(token))
        assertEquals(original, restored.value)
        assertTrue(restored.canSave)
        assertNull(restored.error)
        assertEquals(token, save(secondSaver, restored))
        assertEquals("Identical values reuse immutable content", 1, File(filesDir, "ui-drafts").listFiles()!!.size)
    }

    @Test fun smallTextAndNullRemainInlineWithoutAnyDraftDirectory() {
        val filesDir = temporary.newFolder("inline")
        val saver = uiDraftTextSaver(UiDraftTextStore(filesDir))
        listOf(null, "", "Native values · Ω · 🦉", "a".repeat(UiDraftTextStore.INLINE_BYTES)).forEach { value ->
            val restored = requireNotNull(saver.restore(save(saver, UiDraftTextState(value))))
            assertEquals(value, restored.value)
            assertTrue(restored.canSave)
        }
        assertFalse(File(filesDir, "ui-drafts").exists())
        // Byte-based cutoff is required for Unicode rather than only counting characters.
        val unicode = "🦉".repeat(3_000)
        val restored = requireNotNull(saver.restore(save(saver, UiDraftTextState(unicode))))
        assertEquals(unicode, restored.value)
        assertTrue(File(filesDir, "ui-drafts").isDirectory)
    }

    @Test fun missingOrCorruptedDraftFailsExplicitlyAndCannotSilentlySaveEmpty() {
        val filesDir = temporary.newFolder("corrupt")
        val saver = uiDraftTextSaver(UiDraftTextStore(filesDir))
        val original = "ImageSnapshot".repeat(1_000)
        val token = save(saver, UiDraftTextState(original))
        val file = File(filesDir, "ui-drafts").listFiles()!!.single()
        val originalBytes = file.readBytes()
        file.writeBytes(originalBytes.clone().also { it[0] = 'X'.code.toByte() })
        val corrupt = requireNotNull(saver.restore(token))
        assertNull(corrupt.value)
        assertFalse(corrupt.canSave)
        assertEquals(UiDraftTextStore.CORRUPT_ERROR, corrupt.error)
        // A second restoration retains its blocked/error state, not a valid null draft.
        val stillBlocked = requireNotNull(saver.restore(save(saver, corrupt)))
        assertFalse(stillBlocked.canSave)
        assertNotNull(stillBlocked.error)
        assertTrue(file.delete())
        val missing = requireNotNull(saver.restore(token))
        assertFalse(missing.canSave)
        assertEquals(UiDraftTextStore.MISSING_ERROR, missing.error)
        // Only an explicit reload/reselection supplies a valid replacement draft.
        missing.value = "Reloaded saved record"
        assertTrue(missing.canSave)
        assertNull(missing.error)
    }

    @Test fun malformedPathSizeAndHashReferencesCannotReadOutsideThePrivateDraftDirectory() {
        val filesDir = temporary.newFolder("guards")
        val store = UiDraftTextStore(filesDir)
        val token = store.save("B".repeat(9_000))
        val fragments = token.split(':')
        val digest = fragments.last()
        val invalid = listOf(
            token.substringBeforeLast(':') + ":../outside",
            token.replace(":9000:", ":8999:"),
            token.replace(":9000:", ":99999999:"),
            token.replace(digest, "g".repeat(64)),
            "astravar-ui-draft:v99:f:9000:$digest",
            "astravar-ui-draft:v1:i:" + "x".repeat(UiDraftTextStore.INLINE_BYTES + 1)
        )
        invalid.forEach { reference ->
            val restored = store.restore(reference)
            assertNull(restored.value)
            assertFalse("Invalid reference must block persistence: $reference", restored.canSave)
            assertNotNull(restored.error)
        }
    }

    @Test fun oversizedInputIsBlockedBeforeItCanBecomeAnUnboundedFileOrBundle() {
        val filesDir = temporary.newFolder("oversized")
        val saver = uiDraftTextSaver(UiDraftTextStore(filesDir))
        val state = UiDraftTextState("x".repeat(UiDraftTextStore.MAX_BYTES + 1))
        assertFalse(state.canSave)
        assertNotNull(state.error)
        assertEquals(UiDraftTextStore.ERROR_TOKEN, save(saver, state))
        assertFalse(File(filesDir, "ui-drafts").exists())
    }

    @Test fun symlinkedDraftDirectoryIsRejectedWithoutFollowingItsTarget() {
        val filesDir = temporary.newFolder("links")
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(filesDir, "ui-drafts").toPath(), outside.toPath())
        val saver = uiDraftTextSaver(UiDraftTextStore(filesDir))
        val state = UiDraftTextState("Private".repeat(2_000))
        val token = save(saver, state)
        assertEquals(UiDraftTextStore.ERROR_TOKEN, token)
        assertFalse(state.canSave)
        assertNotNull(state.error)
        assertTrue(outside.listFiles()!!.isEmpty())
        assertFalse(requireNotNull(saver.restore(token)).canSave)
    }
}
