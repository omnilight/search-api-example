package com.neviswealth.search.domain.document.indexing

/**
 * Splits document content into word-aligned chunks of roughly [targetChars]
 * with an [overlapChars]-sized shared tail, so sentences cut at a chunk border
 * stay retrievable. Chunk size trades embedding cost against recall; Nomic
 * class models handle ~512 tokens per input, so ~900 chars per chunk.
 */
object ContentChunker {
    private val whitespace = Regex("\\s+")

    fun chunk(
        content: String,
        targetChars: Int = 900,
        overlapChars: Int = 120,
        maxChunks: Int = 128,
    ): List<String> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()

        val words = trimmed.split(whitespace).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < words.size) {
            val word = words[i]
            if (current.isNotEmpty() && current.length + word.length + 1 > targetChars) {
                chunks += current.toString().trim()
                val tail = tailWords(words, i, overlapChars)
                current.setLength(0)
                for (t in tail) current.append(t).append(' ')
            }
            current.append(word).append(' ')
            i++
        }
        if (current.isNotBlank()) chunks += current.toString().trim()
        return chunks.take(maxChunks)
    }

    /** Longest suffix of words[0, nextIndex) that fits into [overlapChars]. */
    private fun tailWords(
        words: List<String>,
        nextIndex: Int,
        overlapChars: Int,
    ): List<String> {
        val tail = ArrayDeque<String>()
        var length = 0
        var j = nextIndex - 1
        while (j >= 0 && length < overlapChars) {
            val word = words[j]
            if (length + word.length + 1 > overlapChars) break
            tail.addFirst(word)
            length += word.length + 1
            j--
        }
        return tail
    }
}
