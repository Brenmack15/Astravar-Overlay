package com.yazsras.astravar.data

import java.text.Normalizer
import java.util.Locale

/** A presentation index. IDs retain their record kind; equal titles never merge records. */
data class ReforgeSearchDocument(val id: String, val kind: String, val title: String, val body: String)
data class ReforgeSearchHit(val document: ReforgeSearchDocument, val score: Int, val excerpt: String)

object ReforgeSearchIndex {
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    fun search(documents: List<ReforgeSearchDocument>, query: String, limit: Int = 60): List<ReforgeSearchHit> {
        require(limit in 1..500)
        val tokens = normalized(query.take(300)).trim().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
        if (tokens.isEmpty()) return emptyList()
        return documents.asSequence().distinctBy { "${it.kind}:${it.id}" }.mapNotNull { doc ->
            val title = normalized(doc.title)
            val body = normalized(doc.body)
            if (!tokens.all { it in title || it in body || it in normalized(doc.kind) }) return@mapNotNull null
            val score = tokens.sumOf { token -> when { title == token -> 100; title.startsWith(token) -> 50; token in title -> 30; token in body -> 5; else -> 1 } }
            val at = tokens.map { body.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val start = (at - 50).coerceAtLeast(0).coerceAtMost(doc.body.length)
            val excerpt = doc.body.substring(start).take(220).replace(Regex("\\s+"), " ")
            ReforgeSearchHit(doc, score, (if (start > 0) "…" else "") + excerpt)
        }.sortedWith(compareByDescending<ReforgeSearchHit> { it.score }.thenBy { normalized(it.document.title) }
            .thenBy { it.document.kind }.thenBy { it.document.id }).take(limit).toList()
    }
}
