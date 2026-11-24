package com.neviswealth.search.integration.ollama

import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** Long-budget client: indexing embeddings and summaries. */
@RegisterRestClient(configKey = "ollama-api")
interface OllamaApi {
    @POST
    @Path("/api/embed")
    fun embed(request: OllamaEmbedRequest): OllamaEmbedResponse

    @POST
    @Path("/api/chat")
    fun chat(request: OllamaChatRequest): OllamaChatResponse
}

/**
 * Short-budget client for the search-path query embedding: a stalled model
 * must not block the API, the search falls back to lexical results.
 */
@RegisterRestClient(configKey = "ollama-query-api")
interface OllamaQueryApi {
    @POST
    @Path("/api/embed")
    fun embed(request: OllamaEmbedRequest): OllamaEmbedResponse
}

// Wire format of the local Ollama instance (see its /api/embed and /api/chat).

data class OllamaEmbedRequest(
    val model: String,
    val input: List<String>,
    val options: Map<String, Any> = emptyMap(),
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
