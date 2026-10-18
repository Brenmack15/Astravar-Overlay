package com.yazsras.astravar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Nullable UI text with explicit restoration failure. Large image/baseline strings
 * must never be copied into the Activity Bundle. The authoritative Room records
 * are unrelated to this private, content-addressed draft store.
 *
 * Callers must show [error] and disable persistence while [canSave] is false.
 * Assigning [value] after an explicit reload/reselection starts a valid draft.
 */
class UiDraftTextState internal constructor(initial: String?, initialError: String? = null) : MutableState<String?> {
    private var text by mutableStateOf(initial)
    private var failure by mutableStateOf(initialError ?: UiDraftTextStore.validationError(initial))
    val error: String? get() = failure
    val canSave: Boolean get() = failure == null
    override var value: String?
        get() = text
        set(value) { text = value; failure = UiDraftTextStore.validationError(value) }
    override fun component1(): String? = value
    override fun component2(): (String?) -> Unit = { value = it }
    internal fun reportFailure(message: String) { failure = message }
}

/** Saveable primitives stay inline; large text gets an immutable private-file reference. */
@Composable
fun rememberUiDraftText(initial: String? = null): UiDraftTextState {
    val filesDir = LocalContext.current.applicationContext.filesDir
    val store = remember(filesDir.absolutePath) { UiDraftTextStore(filesDir) }
    return rememberSaveable(saver = uiDraftTextSaver(store)) { UiDraftTextState(initial) }
}

internal fun uiDraftTextSaver(store: UiDraftTextStore): Saver<UiDraftTextState, String> = Saver(
    save = { state ->
        if (!state.canSave) UiDraftTextStore.ERROR_TOKEN
        else try { store.save(state.value) }
        catch (_: Exception) {
            state.reportFailure(UiDraftTextStore.STORAGE_ERROR)
            UiDraftTextStore.ERROR_TOKEN
        }
    },
    restore = { token -> store.restore(token) }
)

internal class UiDraftTextStore(private val filesDir: File) {
    companion object {
        const val INLINE_BYTES = 8 * 1024
        // Covers a one-million-character image plus a JSON snapshot and Unicode notes.
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val PREFIX = "astravar-ui-draft:v1:"
        internal const val ERROR_TOKEN = "${PREFIX}e"
        internal const val STORAGE_ERROR = "This UI draft could not be preserved. Reload the saved record or reselect its image before saving."
        internal const val MISSING_ERROR = "A saved UI draft is missing. Reload the saved record or reselect its image before saving."
        internal const val CORRUPT_ERROR = "A saved UI draft could not be verified. Reload the saved record or reselect its image before saving."
        private const val TOO_LARGE_ERROR = "This UI draft is too large. Reload the saved record or choose a smaller image before saving."
        private val fileReference = Regex("f:([0-9]{1,8}):([a-f0-9]{64})")

        private fun utf8(value: String): ByteArray {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            return ByteArray(encoded.remaining()).also { encoded.get(it) }
        }
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun validationError(value: String?): String? = try {
            if (value != null && (value.length > MAX_BYTES || utf8(value).size > MAX_BYTES)) TOO_LARGE_ERROR else null
        } catch (_: Exception) { CORRUPT_ERROR }
    }

    private fun directory(create: Boolean): File {
        val privateRoot = filesDir.canonicalFile
        require(privateRoot.isDirectory) { "Private files directory unavailable" }
        val directory = File(privateRoot, "ui-drafts")
        require(!Files.isSymbolicLink(directory.toPath())) { "Draft directory must not be a link" }
        if (!directory.exists() && create) require(directory.mkdir() || directory.isDirectory) { "Could not create draft directory" }
        if (!directory.exists()) throw FileNotFoundException("Draft directory unavailable")
        require(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "Invalid draft directory" }
        return directory
    }

    private fun file(directory: File, digest: String): File {
        // Only strict lowercase SHA-256 values may reach a path; tokens contain no path.
        require(digest.matches(Regex("[a-f0-9]{64}")))
        val file = File(directory, "$digest.txt")
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == directory) { "Invalid draft file" }
        return file
    }

    private fun readVerified(file: File, size: Int, digest: String): String {
        if (!file.exists()) throw FileNotFoundException("Draft unavailable")
        require(file.isFile && file.length() == size.toLong() && size in (INLINE_BYTES + 1)..MAX_BYTES) { "Draft size mismatch" }
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream(size)
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= size) { "Draft grew while reading" }
                output.write(buffer, 0, count)
            }
            require(total == size) { "Draft size changed" }
            output.toByteArray()
        }
        require(hash(bytes) == digest) { "Draft hash mismatch" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    internal fun save(value: String?): String {
        if (value == null) return "${PREFIX}n"
        require(value.length <= MAX_BYTES) { "Draft too large" }
        val bytes = utf8(value)
        require(bytes.size <= MAX_BYTES) { "Draft too large" }
        if (bytes.size <= INLINE_BYTES) return "${PREFIX}i:$value"
        val digest = hash(bytes)
        val directory = directory(create = true)
        val target = file(directory, digest)
        val existingValid = target.exists() && runCatching { readVerified(target, bytes.size, digest) == value }.getOrDefault(false)
        if (!existingValid) {
            val temporary = File.createTempFile("$digest-", ".tmp", directory)
            try {
                // Context.filesDir is app-private; restrict the temporary file as well.
                temporary.setReadable(false, false); temporary.setWritable(false, false)
                require(temporary.setReadable(true, true) && temporary.setWritable(true, true))
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                // Same-directory atomic replacement leaves either a complete old or new file.
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        return "${PREFIX}f:${bytes.size}:$digest"
    }

    internal fun restore(token: String): UiDraftTextState {
        if (token == ERROR_TOKEN) return UiDraftTextState(null, STORAGE_ERROR)
        return try {
            require(token.startsWith(PREFIX)) { "Unknown draft reference" }
            val payload = token.removePrefix(PREFIX)
            when {
                payload == "n" -> UiDraftTextState(null)
                payload.startsWith("i:") -> {
                    val value = payload.substring(2)
                    require(value.length <= INLINE_BYTES && utf8(value).size <= INLINE_BYTES) { "Oversized inline draft" }
                    UiDraftTextState(value)
                }
                else -> {
                    val match = fileReference.matchEntire(payload) ?: error("Invalid draft reference")
                    val size = match.groupValues[1].toInt()
                    require(size in (INLINE_BYTES + 1)..MAX_BYTES) { "Invalid draft length" }
                    val digest = match.groupValues[2]
                    UiDraftTextState(readVerified(file(directory(create = false), digest), size, digest))
                }
            }
        } catch (_: FileNotFoundException) { UiDraftTextState(null, MISSING_ERROR) }
        catch (_: Exception) { UiDraftTextState(null, CORRUPT_ERROR) }
    }
}
