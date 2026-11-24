package com.neviswealth.search.text

object QueryTokenizer {
    data class Query(
        val phrase: String,
        val tokens: List<String>,
    )

    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
    private val camelBoundary = Regex("(?<=[\\p{Ll}\\p{Nd}])(?=[\\p{Lu}])")
    private val whitespace = Regex("\\s+")

    /**
     * Splits a raw query into a lowercase phrase (for trigram similarity) and
     * lowercase tokens (for substring matching). CamelCase words yield both
     * the joined and the split forms, so "NevisWealth" matches the email
     * domain "neviswealth.com" as well as the words "Nevis" and "Wealth".
     */
    fun tokenize(
        raw: String,
        maxTokens: Int = 8,
    ): Query {
        val cleaned = raw.trim().replace(whitespace, " ")
        val phrase = cleaned.lowercase()
        if (phrase.isEmpty()) return Query(phrase, emptyList())

        val originalParts = cleaned.split(nonAlphanumeric).filter { it.isNotEmpty() }
        val camelParts = originalParts.flatMap { part -> camelBoundary.split(part) }

        val tokens =
            (originalParts + camelParts)
                .map { it.lowercase() }
                .filter { it.length >= 2 }
                .distinct()
                .take(maxTokens)
        return Query(phrase, tokens)
    }
}
