package com.yazsras.astravar.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest

/** These digests retain the exact previous UTF-8 / canonical JSON bytes, with a bounded encoder buffer. */
internal fun campaignUtf8Hash(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    OutputStreamWriter(DigestOutputStream(DiscardCampaignBytes, digest), Charsets.UTF_8).use { writer ->
        var offset = 0
        while (offset < text.length) {
            val count = minOf(8192, text.length - offset)
            writer.write(text, offset, count)
            offset += count
        }
    }
    return digest.digest().toCampaignHex()
}

@OptIn(ExperimentalSerializationApi::class)
internal fun <T> campaignSerializedHash(serializer: SerializationStrategy<T>, value: T): String {
    val digest = MessageDigest.getInstance("SHA-256")
    campaignEncodeJson(serializer, value, DigestOutputStream(DiscardCampaignBytes, digest))
    return digest.digest().toCampaignHex()
}

/**
 * kotlinx.serialization 1.9's JVM encodeToStream still grows a CharArray to the entire largest String.
 * The campaign schema uses classes, lists and primitives, so this deliberately narrow encoder drives
 * the same generated serializers while quoting Strings directly through the fixed UTF-8 writer.
 * Unsupported future descriptor kinds fail closed rather than silently changing the archive format.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> campaignEncodeJson(serializer: SerializationStrategy<T>, value: T, output: OutputStream) {
    OutputStreamWriter(output, Charsets.UTF_8).use { writer -> serializer.serialize(CampaignJsonEncoder(writer), value) }
}

@OptIn(ExperimentalSerializationApi::class)
private class CampaignJsonEncoder(private val writer: Writer, private val kind: SerialKind? = null) : AbstractEncoder() {
    override val serializersModule: SerializersModule get() = campaignJson.serializersModule
    private var first = true
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        require(descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT || descriptor.kind == StructureKind.LIST) {
            "Campaign stream encoder does not support ${descriptor.kind}."
        }
        writer.write(if (descriptor.kind == StructureKind.LIST) "[" else "{")
        return CampaignJsonEncoder(writer, descriptor.kind)
    }
    override fun endStructure(descriptor: SerialDescriptor) { writer.write(if (descriptor.kind == StructureKind.LIST) "]" else "}") }
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        if (!first) writer.write(",")
        first = false
        if (kind != StructureKind.LIST) { quoted(descriptor.getElementName(index)); writer.write(":") }
        return true
    }
    override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int) = true
    override fun encodeNull() { writer.write("null") }
    override fun encodeBoolean(value: Boolean) { writer.write(value.toString()) }
    override fun encodeByte(value: Byte) { writer.write(value.toString()) }
    override fun encodeShort(value: Short) { writer.write(value.toString()) }
    override fun encodeInt(value: Int) { writer.write(value.toString()) }
    override fun encodeLong(value: Long) { writer.write(value.toString()) }
    override fun encodeFloat(value: Float) { require(value.isFinite()); writer.write(value.toString()) }
    override fun encodeDouble(value: Double) { require(value.isFinite()); writer.write(value.toString()) }
    override fun encodeChar(value: Char) = quoted(value.toString())
    override fun encodeString(value: String) = quoted(value)
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = quoted(enumDescriptor.getElementName(index))
    override fun encodeInline(descriptor: SerialDescriptor): Encoder = error("Inline campaign values require an explicit compatible stream codec.")
    private fun quoted(value: String) {
        writer.write("\"")
        var start = 0
        for (at in value.indices) {
            val char = value[at]
            val escape = when (char) {
                '\"' -> "\\\""
                '\\' -> "\\\\"
                '\b' -> "\\b"
                '\t' -> "\\t"
                '\n' -> "\\n"
                '\u000C' -> "\\f"
                '\r' -> "\\r"
                else -> if (char.code < 0x20) "\\u00" + HEX[char.code shr 4] + HEX[char.code and 15] else null
            }
            if (escape != null) {
                writeRange(value, start, at)
                writer.write(escape)
                start = at + 1
            }
        }
        writeRange(value, start, value.length)
        writer.write("\"")
    }
    private fun writeRange(value: String, start: Int, end: Int) {
        var at = start
        while (at < end) {
            val count = minOf(8192, end - at)
            writer.write(value, at, count); at += count
        }
    }
    companion object { private const val HEX = "0123456789abcdef" }
}

private object DiscardCampaignBytes : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}

private fun ByteArray.toCampaignHex() = joinToString("") { "%02x".format(it) }

/**
 * A backup must still be a String for the existing Room/SAF API. Measure JSON first, then write into one
 * exact byte array. This avoids the doubling CharArray in encodeToString (88 MB for the native fixture)
 * and preserves the schema-four JSON byte format. No temporary or unencrypted external file is used.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun campaignBackupJson(value: LedgerExport): String {
    val size = CampaignJsonSize()
    campaignEncodeJson(LedgerExport.serializer(), value, size)
    val bytes = ByteArray(size.bytes.toInt())
    val output = object : OutputStream() {
        var at = 0
        override fun write(value: Int) {
            check(at < bytes.size) { "Backup changed while serializing." }
            bytes[at++] = value.toByte()
        }
        override fun write(value: ByteArray, offset: Int, length: Int) {
            check(length <= bytes.size - at) { "Backup changed while serializing." }
            value.copyInto(bytes, at, offset, offset + length); at += length
        }
        override fun close() { check(at == bytes.size) { "Backup serialization was truncated." } }
    }
    campaignEncodeJson(LedgerExport.serializer(), value, output)
    output.close()
    return supportedCampaignBackup(String(bytes, Charsets.UTF_8))
}

internal const val CAMPAIGN_BACKUP_MAX_BYTES = 32 * 1024 * 1024

/** UTF-16 character count is preserved even when UTF-8 code points cross writer chunk boundaries. */
private class CampaignJsonSize : OutputStream() {
    var bytes = 0L
        private set
    private var characters = 0L
    override fun write(value: Int) {
        bytes++
        val octet = value and 0xff
        if (octet and 0xc0 != 0x80) characters += if (octet and 0xf8 == 0xf0) 2 else 1
        require(characters <= CAMPAIGN_BACKUP_MAX_CHARACTERS) {
            "Backup exceeds this preview's 32-million-character export/restore limit. No complete backup was produced; preserve the original app data."
        }
        require(bytes <= CAMPAIGN_BACKUP_MAX_BYTES) {
            "Backup exceeds the verified 32 MiB document-transfer limit. No complete backup was produced; preserve the original app data."
        }
    }
    override fun write(value: ByteArray, offset: Int, length: Int) {
        for (at in offset until offset + length) write(value[at].toInt())
    }
}

/** Reject corrupt UTF-8 with one small CharBuffer, then construct the final String directly from bytes. */
internal fun validatedCampaignUtf8(bytes: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(bytes)
    val chars = CharBuffer.allocate(8192)
    while (true) {
        val result = decoder.decode(input, chars, true)
        if (result.isError) result.throwException()
        chars.clear()
        if (result.isUnderflow) break
    }
    while (true) {
        val result = decoder.flush(chars)
        if (result.isError) result.throwException()
        chars.clear()
        if (result.isUnderflow) break
    }
    return String(bytes, Charsets.UTF_8)
}
