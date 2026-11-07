package com.yazsras.astravar.data

import java.net.URI

/** Lossless-at-rest presentation only. Never write this projection back over imported source. */
object RecordPresentation {
    private val mention = Regex("""<mention\b[^>]*\burl\s*=\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</mention\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val activeHtml = Regex("""<(script|style|iframe|object)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tags = Regex("<[^>]*>")
    private val label = Regex("""^\s*(?:\*\*)?(Evidence|Status|Era|Known facts|Promises/open threads|Open threads|Original record|External ID|Source)(?:\*\*)?\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun safeLink(value: String): String? = runCatching {
        val text = value.trim()
        require(text.none { it.isISOControl() || it == '\\' })
        val uri = URI(text)
        when (uri.scheme?.lowercase()) {
            "https", "http" -> { require(!uri.host.isNullOrBlank() && uri.userInfo == null); text }
            "astravar" -> { require(uri.host == "record" && uri.path.orEmpty().split('/').filter(String::isNotBlank).size >= 2); text }
            else -> null
        }
    }.getOrNull()

    fun normalize(raw: String): String {
        // Decode before sanitizing so encoded Notion/HTML tags cannot leak into the reading view.
        val decoded = raw.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        val withoutActive = activeHtml.replace(decoded, "")
        val mentions = mention.replace(withoutActive) { match ->
            val title = tags.replace(match.groupValues[2], "").trim().ifBlank { "Linked record" }
            val url = safeLink(match.groupValues[1])
            if (url == null) title else "[${title.replace("[", "").replace("]", "")}]($url)"
        }
        return tags.replace(mentions.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"), "")
    }

    data class NpcProfile(val overview: String, val facts: String, val openThreads: String,
        val status: String, val era: String, val evidence: String, val technical: List<Pair<String, String>>)

    fun npc(raw: String): NpcProfile {
        val parts = linkedMapOf<String, StringBuilder>("overview" to StringBuilder())
        var current = "overview"
        raw.replace("\r\n", "\n").lineSequence().forEach { line ->
            val match = label.matchEntire(line)
            if (match != null) {
                current = match.groupValues[1].lowercase()
                parts.getOrPut(current) { StringBuilder() }.appendLine(match.groupValues[2])
            } else parts.getOrPut(current) { StringBuilder() }.appendLine(line)
        }
        fun part(key: String) = parts[key]?.toString()?.trim().orEmpty()
        fun display(key: String) = normalize(part(key))
        val era = display("era").removePrefix("[").removeSuffix("]").replace("\"", "")
        return NpcProfile(display("overview"), display("known facts"),
            listOf(display("promises/open threads"), display("open threads")).filter(String::isNotBlank).joinToString("\n\n"),
            display("status"), era, display("evidence"),
            listOf("Original record", "External ID", "Source").mapNotNull { name -> part(name.lowercase()).takeIf(String::isNotBlank)?.let { name to it } })
    }

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class ListItem(val marker: String, val text: String, val checked: Boolean? = null) : Block
        data class Quote(val text: String) : Block
        data class Code(val text: String) : Block
        data class Image(val alt: String, val url: String?) : Block
        data class Table(val rows: List<List<String>>) : Block
        data object Divider : Block
    }

    fun blocks(raw: String): List<Block> {
        val lines = normalize(raw).replace("\r\n", "\n").lines()
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flush() { if (paragraph.isNotEmpty()) { blocks += Block.Paragraph(paragraph.joinToString("\n")); paragraph.clear() } }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isBlank() -> flush()
                trimmed.startsWith("```") -> {
                    flush(); val code = mutableListOf<String>(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    blocks += Block.Code(code.joinToString("\n"))
                }
                Regex("^#{1,6}\\s+.*").matches(trimmed) -> {
                    flush(); val level = trimmed.takeWhile { it == '#' }.length
                    blocks += Block.Heading(level, trimmed.drop(level).trim())
                }
                trimmed in setOf("---", "***", "___") -> { flush(); blocks += Block.Divider }
                trimmed.startsWith(">") -> { flush(); blocks += Block.Quote(trimmed.removePrefix(">").trim()) }
                Regex("^!\\[.*]\\(.*\\)$").matches(trimmed) -> {
                    flush(); val split = trimmed.indexOf("](")
                    blocks += Block.Image(trimmed.substring(2, split), safeLink(trimmed.substring(split + 2, trimmed.length - 1)))
                }
                trimmed.contains('|') && i + 1 < lines.size && Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$").matches(lines[i + 1]) -> {
                    flush(); fun cells(row: String) = row.trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)
                    val rows = mutableListOf(cells(line)); i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++])
                    blocks += Block.Table(rows); i--; 
                }
                Regex("^[-*+]\\s+\\[[ xX]]\\s+.*").matches(trimmed) -> {
                    flush(); val checked = trimmed[3].lowercaseChar() == 'x'
                    blocks += Block.ListItem(if (checked) "☑" else "☐", trimmed.drop(6), checked)
                }
                Regex("^[-*+]\\s+.*").matches(trimmed) -> { flush(); blocks += Block.ListItem("•", trimmed.drop(2)) }
                Regex("^\\d+[.)]\\s+.*").matches(trimmed) -> {
                    flush(); val split = trimmed.indexOf(' '); blocks += Block.ListItem(trimmed.take(split), trimmed.drop(split + 1))
                }
                else -> paragraph += line
            }
            i++
        }
        flush(); return blocks
    }
}
