package com.neviswealth.search.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentChunkerTest {
    @Test
    fun `short content stays in one chunk`() {
        assertEquals(listOf("Hello world"), ContentChunker.chunk("Hello world"))
    }

    @Test
    fun `long content is split with overlap`() {
        val words = (1..300).joinToString(" ") { "word$it" }
        val chunks = ContentChunker.chunk(words, targetChars = 100, overlapChars = 30)

        assertTrue(chunks.size > 1)
        // chunks[1] must begin with a non-empty suffix of chunks[0]'s words.
        val firstWords = chunks[0].split(" ")
        val secondWords = chunks[1].split(" ")
        val sharedSuffix =
            (1..minOf(8, firstWords.size, secondWords.size))
                .any { k -> secondWords.take(k) == firstWords.takeLast(k) }
        assertTrue(sharedSuffix)
    }

    @Test
    fun `chunks do not grow unbounded for long words`() {
        val chunks = ContentChunker.chunk("a".repeat(50), targetChars = 10, overlapChars = 3)
        assertEquals(1, chunks.size)
    }

    @Test
    fun `maxChunks caps the result`() {
        val long = (1..5000).joinToString(" ") { "word$it" }
        val chunks = ContentChunker.chunk(long, targetChars = 100, overlapChars = 20, maxChunks = 5)
        assertEquals(5, chunks.size)
    }

    @Test
    fun `blank content yields no chunks`() {
        assertTrue(ContentChunker.chunk("   ").isEmpty())
    }
}
