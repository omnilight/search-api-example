package com.neviswealth.search.domain.llm

/** Connectivity failure that is expected to heal (the local model server restarting, timeouts). */
class LlmTransientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The LLM rejected the request; retrying the same document will not help. */
class LlmHardException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Port: the local model gateway used for embeddings and document summaries. */
interface LlmClient {
    /** Embeds a search query (the implementation applies the model's query prefix). */
    fun embedQuery(text: String): FloatArray

    /** Embeds document chunks (the implementation applies the model's document prefix). */
    fun embedDocuments(texts: List<String>): List<FloatArray>

    fun summarize(
        title: String,
        content: String,
    ): String
}
