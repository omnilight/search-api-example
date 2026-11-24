package com.neviswealth.search.llm

import com.neviswealth.search.config.OllamaConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient

@RegisterRestClient(configKey = "ollama-api")
interface OllamaApi {
    @POST
    @Path("/api/embed")
    fun embed(request: OllamaEmbedRequest): OllamaEmbedResponse

    @POST
    @Path("/api/chat")
    fun chat(request: OllamaChatRequest): OllamaChatResponse
}

private const val SUMMARY_SYSTEM_PROMPT =
    """You summarize documents for financial advisors. Summarize the document in one or two plain sentences, at most 60 words. Output only the summary text: no preamble, no quotes, no markdown."""

@ApplicationScoped
class OllamaLlmClient
    @Inject
    constructor(
        @RestClient private val api: OllamaApi,
        private val config: OllamaConfig,
    ) : LlmClient {
        override fun embedQuery(text: String): FloatArray = embedPrefixed(listOf(text), config.embeddingQueryPrefix()).first()

        override fun embedDocuments(texts: List<String>): List<FloatArray> = embedPrefixed(texts, config.embeddingDocumentPrefix())

        private fun embedPrefixed(
            texts: List<String>,
            prefix: String,
        ): List<FloatArray> =
            callLlm {
                val response = api.embed(OllamaEmbedRequest(config.embeddingModel(), texts.map { prefix + it }))
                val embeddings =
                    response.embeddings
                        ?: throw LlmTransientException("Ollama returned no embeddings")
                if (embeddings.size != texts.size) {
                    throw LlmTransientException("Ollama returned ${embeddings.size} embeddings for ${texts.size} inputs")
                }
                embeddings.map { vector -> FloatArray(vector.size) { i -> vector[i].toFloat() } }
            }

        override fun summarize(
            title: String,
            content: String,
        ): String =
            callLlm {
                val request =
                    OllamaChatRequest(
                        model = config.chatModel(),
                        messages =
                            listOf(
                                OllamaChatMessage("system", SUMMARY_SYSTEM_PROMPT),
                                OllamaChatMessage("user", "Title: $title\n\n${content.take(config.summarizeContentChars())}"),
                            ),
                        options = mapOf("temperature" to 0.1, "num_predict" to 96),
                    )
                val summary =
                    api
                        .chat(request)
                        .message
                        ?.content
                        ?.trim()
                        ?: throw LlmTransientException("Ollama returned no chat message")
                if (summary.isEmpty()) throw LlmTransientException("Ollama returned an empty summary")
                summary
            }

        private fun <T> callLlm(block: () -> T): T =
            try {
                block()
            } catch (e: LlmTransientException) {
                throw e
            } catch (e: LlmHardException) {
                throw e
            } catch (e: ProcessingException) {
                throw LlmTransientException("Ollama unreachable: ${e.cause?.message ?: e.message}", e)
            } catch (e: WebApplicationException) {
                val status = e.response.status
                if (status >= 500) {
                    throw LlmTransientException("Ollama server error $status", e)
                }
                throw LlmHardException("Ollama rejected request with $status: ${e.response.readEntity(String::class.java).take(200)}", e)
            }
    }
