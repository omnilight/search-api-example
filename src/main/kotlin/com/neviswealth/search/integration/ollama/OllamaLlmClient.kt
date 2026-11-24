package com.neviswealth.search.integration.ollama

import com.neviswealth.search.domain.llm.LlmClient
import com.neviswealth.search.domain.llm.LlmHardException
import com.neviswealth.search.domain.llm.LlmTransientException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient

private const val SUMMARY_SYSTEM_PROMPT =
    """You summarize documents for financial advisors. Summarize the document in one or two plain sentences, at most 60 words. Output only the summary text: no preamble, no quotes, no markdown."""

@ApplicationScoped
class OllamaLlmClient
    @Inject
    constructor(
        @RestClient private val api: OllamaApi,
        // Search-path client: short timeouts, see quarkus.rest-client.ollama-query-api.
        @RestClient private val queryApi: OllamaQueryApi,
        private val config: OllamaConfig,
    ) : LlmClient {
        override fun embedQuery(text: String): FloatArray {
            val request =
                OllamaEmbedRequest(
                    model = config.embeddingModel(),
                    input = listOf(config.embeddingQueryPrefix() + text),
                    options = mapOf("num_thread" to config.numThreads()),
                )
            return callLlm { singleEmbedding(queryApi.embed(request), 1) }.first()
        }

        override fun embedDocuments(texts: List<String>): List<FloatArray> {
            val request =
                OllamaEmbedRequest(
                    model = config.embeddingModel(),
                    input = texts.map { config.embeddingDocumentPrefix() + it },
                    options = mapOf("num_thread" to config.numThreads()),
                )
            return callLlm { singleEmbedding(api.embed(request), texts.size) }
        }

        private fun singleEmbedding(
            response: OllamaEmbedResponse,
            expected: Int,
        ): List<FloatArray> {
            val embeddings =
                response.embeddings
                    ?: throw LlmTransientException("Ollama returned no embeddings")
            if (embeddings.size != expected) {
                throw LlmTransientException("Ollama returned ${embeddings.size} embeddings for $expected inputs")
            }
            return embeddings.map { vector -> FloatArray(vector.size) { i -> vector[i].toFloat() } }
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
                        options =
                            mapOf(
                                "temperature" to 0.1,
                                "num_predict" to 96,
                                "num_thread" to config.numThreads(),
                            ),
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
