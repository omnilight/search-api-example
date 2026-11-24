package com.neviswealth.search.llm

/** Connectivity failure that is expected to heal (Ollama restarting, timeout). */
class LlmTransientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The LLM rejected the request; retrying the same document will not help. */
class LlmHardException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Local model gateway used for embeddings and document summaries. */
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

data class OllamaEmbedRequest(
    val model: String,
    val input: List<String>,
)

data class OllamaEmbedResponse(
    val embeddings: List<List<Double>>? = null,
)

data class OllamaChatMessage(
    val role: String,
    val content: String,
)

data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = false,
    val options: Map<String, Any> = emptyMap(),
)

data class OllamaChatResponse(
    val message: OllamaChatMessage? = null,
)
