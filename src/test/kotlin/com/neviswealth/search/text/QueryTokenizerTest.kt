package com.neviswealth.search.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTokenizerTest {
    @Test
    fun `camelCase query yields joined and split tokens`() {
        val query = QueryTokenizer.tokenize("NevisWealth")
        assertEquals("neviswealth", query.phrase)
        assertEquals(listOf("neviswealth", "nevis", "wealth"), query.tokens)
    }

    @Test
    fun `query with separators yields a token per part`() {
        val query = QueryTokenizer.tokenize("john.doe@example.com")
        assertEquals("john.doe@example.com", query.phrase)
        assertEquals(listOf("john", "doe", "example", "com"), query.tokens)
    }

    @Test
    fun `unicode letters survive tokenization`() {
        val query = QueryTokenizer.tokenize("Иван Иванов")
        assertEquals("иван иванов", query.phrase)
        assertEquals(listOf("иван", "иванов"), query.tokens)
    }

    @Test
    fun `short parts and punctuation are dropped from tokens but kept in phrase`() {
        val query = QueryTokenizer.tokenize("  a. B!  ")
        assertEquals("a. b!", query.phrase)
        assertTrue(query.tokens.isEmpty())
    }

    @Test
    fun `blank query yields an empty phrase`() {
        val query = QueryTokenizer.tokenize("   ")
        assertEquals("", query.phrase)
        assertTrue(query.tokens.isEmpty())
    }

    @Test
    fun `token count is capped`() {
        val query = QueryTokenizer.tokenize("one two three four five six seven eight nine ten", maxTokens = 4)
        assertEquals(4, query.tokens.size)
    }
}
