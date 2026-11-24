package com.neviswealth.search.service

import com.neviswealth.search.config.SearchConfig
import com.neviswealth.search.db.ClientRepository
import com.neviswealth.search.db.DocumentRepository
import com.neviswealth.search.generated.model.SearchResult
import com.neviswealth.search.llm.LlmClient
import com.neviswealth.search.llm.LlmTransientException
import com.neviswealth.search.model.toDto
import com.neviswealth.search.text.QueryTokenizer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import java.util.UUID
import java.util.logging.Logger

@ApplicationScoped
class SearchService
    @Inject
    constructor(
        private val clients: ClientRepository,
        private val documents: DocumentRepository,
        private val llm: LlmClient,
        private val config: SearchConfig,
    ) {
        private val log = Logger.getLogger(SearchService::class.java.name)

        fun search(
            rawQuery: String,
            type: String?,
            limitOverride: Int?,
        ): List<SearchResult> {
            val query = rawQuery.trim()
            val lexical = config.lexical()
            if (query.isEmpty()) throw BadRequestException("Query parameter 'q' must not be blank")
            if (query.length > lexical.maxQueryLength()) {
                throw BadRequestException("Query must be at most ${lexical.maxQueryLength()} characters")
            }
            val limit = limitOverride ?: lexical.defaultLimit()
            if (limit !in 1..lexical.maxLimit()) {
                throw BadRequestException("limit must be between 1 and ${lexical.maxLimit()}")
            }
            val wantClients: Boolean
            val wantDocuments: Boolean
            when (type) {
                null -> {
                    wantClients = true
                    wantDocuments = true
                }
                "client" -> {
                    wantClients = true
                    wantDocuments = false
                }
                "document" -> {
                    wantClients = false
                    wantDocuments = true
                }
                else -> throw BadRequestException("type must be 'client' or 'document'")
            }

            val tokenized = QueryTokenizer.tokenize(query)
            val tokenRegex = tokenized.tokens.takeIf { it.isNotEmpty() }?.let(::posixRegexOr)
            val minSimilarity = lexical.minSimilarity()

            val clientResults =
                if (wantClients) {
                    clients.search(tokenized.phrase, tokenRegex, minSimilarity, limit).map { row ->
                        SearchResult(
                            type = SearchResult.Type.client,
                            score = if (row.hits.isNotEmpty()) 1.0 else row.similarity,
                            matched = row.matchedFields(minSimilarity),
                            snippet = null,
                            client = row.client.toDto(),
                            document = null,
                        )
                    }
                } else {
                    emptyList()
                }

            val documentResults =
                if (wantDocuments) {
                    searchDocuments(tokenized, tokenRegex, limit)
                } else {
                    emptyList()
                }

            return (clientResults + documentResults)
                .sortedByDescending { it.score }
                .take(limit)
        }

        /**
         * Documents match either semantically (query embedding vs chunk embeddings,
         * cosine similarity via pgvector) or lexically (substring + trigram over
         * title and content). Lexical search also serves as the fallback while
         * the background index worker has not processed a document yet, and when
         * Ollama is unavailable. Results are merged per document id.
         */
        private fun searchDocuments(
            tokenized: QueryTokenizer.Query,
            tokenRegex: String?,
            limit: Int,
        ): List<SearchResult> {
            val lexical = config.lexical()
            val minSimilarity = lexical.minSimilarity()

            val lexicalRows = documents.lexicalSearch(tokenized.phrase, tokenRegex, minSimilarity, limit)

            val semanticRows =
                try {
                    val vector = llm.embedQuery(tokenized.phrase)
                    documents.semanticSearch(vector, config.semantic().minScore(), limit)
                } catch (e: LlmTransientException) {
                    log.warning("Embedding unavailable, documents searched lexically only: ${e.message}")
                    emptyList<DocumentRepository.DocumentSearchRow>()
                }

            val merged = LinkedHashMap<UUID, SearchResult>()
            for (row in semanticRows) {
                merged[row.document.id] =
                    SearchResult(
                        type = SearchResult.Type.document,
                        score = row.score,
                        matched = row.matched.toList(),
                        snippet = row.snippet,
                        client = null,
                        document = row.document.toDto(),
                    )
            }
            for (row in lexicalRows) {
                val existing = merged[row.document.id]
                if (existing == null) {
                    merged[row.document.id] =
                        SearchResult(
                            type = SearchResult.Type.document,
                            score = row.score,
                            matched = row.matched.toList(),
                            snippet = row.snippet,
                            client = null,
                            document = row.document.toDto(),
                        )
                } else {
                    merged[row.document.id] =
                        existing.copy(
                            score = maxOf(existing.score, row.score),
                            matched = (existing.matched + row.matched).distinct(),
                            snippet = existing.snippet ?: row.snippet,
                        )
                }
            }
            return merged.values.toList()
        }

        private fun ClientRepository.ClientSearchRow.matchedFields(minSimilarity: Double): List<String> =
            if (hits.isNotEmpty()) {
                hits.toList()
            } else {
                fieldSimilarities.filterValues { it >= minSimilarity }.keys.toList()
            }

        private companion object {
            // Postgres uses POSIX-ish regex, which does not understand \Q...\E
            // from java.util.regex. Tokens are alphanumeric by construction; the
            // escaping is a safety net for anything regex-special.
            private val regexSpecial = Regex("[.^$*+?()\\[\\]{}|\\\\]")

            fun posixRegexOr(tokens: List<String>): String =
                tokens.joinToString("|") { token -> regexSpecial.replace(token) { "\\${it.value}" } }
        }
    }
