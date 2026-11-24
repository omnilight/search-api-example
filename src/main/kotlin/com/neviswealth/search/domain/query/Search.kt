package com.neviswealth.search.domain.query

import com.neviswealth.search.config.SearchConfig
import com.neviswealth.search.domain.llm.LlmClient
import com.neviswealth.search.domain.llm.LlmTransientException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID
import java.util.logging.Logger

/**
 * Search scenario: validates the query, embeds it (before any database work,
 * so no JDBC connection is held while the model answers), reads one bounded
 * candidate set and merges/ranks the hits.
 *
 * Documents match either semantically (query embedding vs chunk embeddings,
 * cosine similarity via pgvector) or lexically (substring + trigram over
 * title and content). Lexical search also serves as the fallback while a
 * document is still indexing, and when the LLM is unavailable. Results are
 * merged per document id.
 */
@ApplicationScoped
class Search
    @Inject
    constructor(
        private val storage: SearchStorage,
        private val llm: LlmClient,
        private val config: SearchConfig,
    ) {
        private val log = Logger.getLogger(Search::class.java.name)

        fun search(
            rawQuery: String,
            type: String?,
            limitOverride: Int?,
        ): List<SearchHit> {
            val query = rawQuery.trim()
            val lexical = config.lexical()
            if (query.isEmpty()) throw InvalidSearchQueryException("Query must not be blank")
            if (query.length > lexical.maxQueryLength()) {
                throw InvalidSearchQueryException("Query must be at most ${lexical.maxQueryLength()} characters")
            }
            val limit = limitOverride ?: lexical.defaultLimit()
            if (limit !in 1..lexical.maxLimit()) {
                throw InvalidSearchQueryException("limit must be between 1 and ${lexical.maxLimit()}")
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
                else -> throw InvalidSearchQueryException("type must be 'client' or 'document'")
            }

            val tokenized = QueryTokenizer.tokenize(query)
            val minSimilarity = lexical.minSimilarity()

            // Embed first: the vector branch of the candidate query consumes the
            // prepared vector, so no database connection is held during the call.
            val queryVector =
                if (wantDocuments) {
                    try {
                        llm.embedQuery(tokenized.phrase)
                    } catch (e: LlmTransientException) {
                        log.warning("Embedding unavailable, documents searched lexically only: ${e.message}")
                        null
                    }
                } else {
                    null
                }

            val candidates =
                storage.findCandidates(
                    SearchStorage.CandidateCriteria(
                        phrase = tokenized.phrase,
                        tokens = tokenized.tokens,
                        minSimilarity = minSimilarity,
                        minScore = config.semantic().minScore(),
                        limit = limit,
                        candidateLimit = lexical.candidateLimit(),
                        queryVector = queryVector,
                        chunkCandidates = config.semantic().chunkCandidates(),
                        wantClients = wantClients,
                        wantDocuments = wantDocuments,
                    ),
                )

            val clientHits =
                candidates.clients.map { match ->
                    SearchHit(
                        type = SearchHitType.CLIENT,
                        score = if (match.hits.isNotEmpty()) 1.0 else match.similarity,
                        matched = match.matchedFields(minSimilarity),
                        snippet = null,
                        client = match.client,
                        document = null,
                    )
                }
            val documentHits = mergeDocuments(candidates)

            return (clientHits + documentHits)
                .sortedByDescending { it.score }
                .take(limit)
        }

        private fun mergeDocuments(candidates: SearchStorage.CandidateSet): List<SearchHit> {
            val merged = LinkedHashMap<UUID, SearchHit>()
            for (match in candidates.semanticDocuments) {
                merged[match.documentId] = searchHit(match)
            }
            for (match in candidates.lexicalDocuments) {
                val existing = merged[match.documentId]
                merged[match.documentId] =
                    if (existing == null) {
                        searchHit(match)
                    } else {
                        existing.copy(
                            score = maxOf(existing.score, match.score),
                            matched = (existing.matched + match.matched).distinct(),
                            snippet = existing.snippet ?: match.snippet,
                        )
                    }
            }
            return merged.values.toList()
        }

        private fun searchHit(match: SearchStorage.DocumentCandidate) =
            SearchHit(
                type = SearchHitType.DOCUMENT,
                score = match.score,
                matched = match.matched.toList(),
                snippet = match.snippet,
                client = null,
                document = match.document,
            )

        private fun SearchStorage.ClientMatch.matchedFields(minSimilarity: Double): List<String> =
            if (hits.isNotEmpty()) {
                hits.toList()
            } else {
                fieldSimilarities.filterValues { it >= minSimilarity }.keys.toList()
            }
    }
