package com.neviswealth.search.integration.ollama

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "ollama")
interface OllamaConfig {
    /** Embedding model; must produce vectors matching vector(768) in the schema. */
    fun embeddingModel(): String

    /** Model-specific embedding prefixes (empty strings for models without them). */
    fun embeddingQueryPrefix(): String

    fun embeddingDocumentPrefix(): String

    fun chatModel(): String

    fun summarizeContentChars(): Int

    /** Ollama CPU threads for embeddings and summaries. */
    fun numThreads(): Int
}
