package com.neviswealth.search.domain.query

import com.neviswealth.search.domain.client.Client
import com.neviswealth.search.domain.document.DocumentMeta
import java.util.UUID

/** Read side of the search indexes; one bounded candidate read per query. */
interface SearchStorage {
    /**
     * Runs one prepared statement with bounded branches: clients (lexical),
     * lexical document candidates and — when [CandidateCriteria.queryVector]
     * is present — vector candidates from the HNSW index. Ranking and the
     * per-document merge happen in the scenario.
     */
    fun findCandidates(criteria: CandidateCriteria): CandidateSet

    data class CandidateCriteria(
        val phrase: String,
        val tokens: List<String>,
        val minSimilarity: Double,
        val minScore: Double,
        val limit: Int,
        /** Matching rows read per branch before ranking (early cutoff). */
        val candidateLimit: Int,
        /** Null means the embedding is unavailable: lexical-only fallback. */
        val queryVector: FloatArray?,
        val chunkCandidates: Int,
        val wantClients: Boolean,
        val wantDocuments: Boolean,
    )

    data class CandidateSet(
        val clients: List<ClientMatch>,
        val lexicalDocuments: List<DocumentCandidate>,
        val semanticDocuments: List<DocumentCandidate>,
    )

    data class ClientMatch(
        val client: Client,
        val similarity: Double,
        /** Fields with a direct substring hit, by their snake_case names. */
        val hits: Set<String>,
        /** Trigram similarity per field, keyed by snake_case field name. */
        val fieldSimilarities: Map<String, Double>,
    )

    data class DocumentCandidate(
        val document: DocumentMeta,
        val score: Double,
        val matched: Set<String>,
        /** Best-matching chunk for semantic candidates; null for lexical ones. */
        val snippet: String?,
        /** Document content for lexical candidates; used to cut the snippet. */
        val content: String?,
    ) {
        val documentId: UUID get() = document.id
    }
}
