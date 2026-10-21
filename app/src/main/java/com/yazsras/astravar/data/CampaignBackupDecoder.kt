package com.yazsras.astravar.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule

/**
 * Decode the existing backup schema without a growable buffer for an escaped source payload.
 * The generated serializers retain their required fields, defaults and nullable-value behavior.
 * Syntax, duplicate members and resource budgets are checked before typed values are allocated.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun campaignDecodeBackup(text: String): LedgerExport {
    supportedCampaignBackup(text)
    rejectDuplicateJsonMembers(text)
    val cursor = CampaignBackupCursor(text)
    val result = LedgerExport.serializer().deserialize(CampaignBackupDecoder(cursor))
    require(cursor.finished()) { "Unexpected content after campaign backup." }
    return result
}

@OptIn(ExperimentalSerializationApi::class)
private class CampaignBackupDecoder(
    private val cursor: CampaignBackupCursor,
    private val kind: SerialKind? = null
) : AbstractDecoder() {
    override val serializersModule: SerializersModule get() = campaignJson.serializersModule
    private var nextIndex = 0

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        require(descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT || descriptor.kind == StructureKind.LIST) {
            "Unsupported campaign backup value kind."
        }
        cursor.expect(if (descriptor.kind == StructureKind.LIST) '[' else '{')
        return CampaignBackupDecoder(cursor, descriptor.kind)
    }

    override fun decodeSequentially() = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val list = kind == StructureKind.LIST
        require(list || kind == StructureKind.CLASS || kind == StructureKind.OBJECT) { "Campaign backup structure is missing." }
        if (cursor.peek() == if (list) ']' else '}') return CompositeDecoder.DECODE_DONE
        if (nextIndex > 0) cursor.expect(',')
        if (list) return nextIndex++
        val name = cursor.string()
        cursor.expect(':')
        val index = descriptor.getElementIndex(name)
        require(index != CompositeDecoder.UNKNOWN_NAME) { "Unknown campaign backup field '$name'." }
        nextIndex++
        return index
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        cursor.expect(if (kind == StructureKind.LIST) ']' else '}')
    }

    override fun decodeNotNullMark(): Boolean = !cursor.isNull()
    override fun decodeNull(): Nothing? { require(cursor.primitive() == "null") { "Expected a null campaign backup value." }; return null }
    override fun decodeBoolean(): Boolean = when (cursor.primitive()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Expected a Boolean campaign backup value.")
    }
    override fun decodeByte(): Byte = cursor.primitive().toByteOrNull() ?: invalidNumber()
    override fun decodeShort(): Short = cursor.primitive().toShortOrNull() ?: invalidNumber()
    override fun decodeInt(): Int = cursor.primitive().toIntOrNull() ?: invalidNumber()
    override fun decodeLong(): Long = cursor.primitive().toLongOrNull() ?: invalidNumber()
    override fun decodeFloat(): Float = cursor.primitive().toFloatOrNull()?.takeIf { it.isFinite() } ?: invalidNumber()
    override fun decodeDouble(): Double = cursor.primitive().toDoubleOrNull()?.takeIf { it.isFinite() } ?: invalidNumber()
    override fun decodeString(): String = cursor.string()
    override fun decodeChar(): Char = cursor.string().singleOrNull() ?: throw IllegalArgumentException("Expected one campaign backup character.")
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val index = enumDescriptor.getElementIndex(cursor.string())
        require(index != CompositeDecoder.UNKNOWN_NAME) { "Unknown campaign backup enum value." }
        return index
    }
    override fun decodeInline(descriptor: SerialDescriptor): Decoder = error("Inline campaign backup values require an explicit compatible decoder.")
    private fun invalidNumber(): Nothing = throw IllegalArgumentException("Invalid or out-of-range campaign backup number.")
}

/** The input was already strictly validated; this cursor never materializes a JSON tree. */
private class CampaignBackupCursor(private val text: String) {
    private var at = 0
    private var unitAt = 0
    private fun spaces() { while (at < text.length && text[at] in " \t\r\n") at++ }
    fun peek(): Char { spaces(); require(at < text.length) { "Campaign backup is incomplete." }; return text[at] }
    fun expect(value: Char) { require(peek() == value) { "Unexpected campaign backup value type." }; at++ }
    fun finished(): Boolean { spaces(); return at == text.length }
    fun isNull(): Boolean { spaces(); return text.startsWith("null", at) }

    fun primitive(): String {
        spaces()
        val start = at
        while (at < text.length && text[at] !in ",]} \t\r\n") at++
        require(at > start && at - start <= 1024 && text[start] != '"' && text[start] != '{' && text[start] != '[') {
            "Unexpected campaign backup primitive type."
        }
        return text.substring(start, at)
    }

    /** Measure first, then decode through bounded chunks; escaped strings never use StringBuilder. */
    fun string(): String {
        expect('"')
        val start = at
        var scan = start
        var length = 0
        var escaped = false
        while (scan < text.length && text[scan] != '"') {
            if (text[scan++] == '\\') {
                escaped = true
                require(scan < text.length) { "Campaign backup string is incomplete." }
                if (text[scan++] == 'u') scan += 4
            }
            length++
        }
        require(scan < text.length) { "Campaign backup string is incomplete." }
        at = scan + 1
        if (!escaped) return text.substring(start, scan)

        // Public String constructors may allocate source-sized intermediate compression buffers.
        // Build depth-first, balanced pieces instead: only small leaves use a temporary CharArray,
        // and concat allocates each combined result directly. UTF-16 units, including unpaired
        // surrogates or pairs split across pieces, retain their exact original values.
        unitAt = start
        return stringPart(length).also {
            check(unitAt == scan && it.length == length) { "Campaign backup string length changed while decoding." }
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun stringPart(length: Int): String {
        if (length <= 8192) {
            val chars = CharArray(length)
            for (index in chars.indices) chars[index] = stringUnit()
            return String(chars)
        }
        val half = length / 2
        val left = stringPart(half)
        val right = stringPart(length - half)
        // Use the public Java operation explicitly; Kotlin '+' can introduce a growing builder.
        return (left as java.lang.String).concat(right)
    }

    private fun stringUnit(): Char {
        val char = text[unitAt++]
        return if (char != '\\') char else when (val escape = text[unitAt++]) {
            '"', '\\', '/' -> escape
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                var value = 0
                repeat(4) { value = (value shl 4) or text[unitAt++].digitToInt(16) }
                value.toChar()
            }
            else -> throw IllegalArgumentException("Invalid campaign backup string escape.")
        }
    }
}
