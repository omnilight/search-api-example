package com.neviswealth.search.config

import io.smallrye.config.ConfigMapping
import java.time.Duration

@ConfigMapping(prefix = "search")
interface SearchConfig {
    fun worker(): Worker

    fun lexical(): Lexical

    fun semantic(): Semantic

    interface Worker {
        fun interval(): Duration

        fun batchSize(): Int

        fun maxAttempts(): Int
    }

    interface Lexical {
        fun minSimilarity(): Double

        fun maxQueryLength(): Int

        fun defaultLimit(): Int

        fun maxLimit(): Int
    }

    interface Semantic {
        fun minScore(): Double
    }
}

@ConfigMapping(prefix = "ollama")
interface OllamaConfig {
    /** Embedding model; must produce vectors matching vector(768) in the schema. */
    fun embeddingModel(): String

    /** Model-specific embedding prefixes (empty strings for models without them). */
    fun embeddingQueryPrefix(): String

    fun embeddingDocumentPrefix(): String

    fun chatModel(): String

    fun summarizeContentChars(): Int
}
