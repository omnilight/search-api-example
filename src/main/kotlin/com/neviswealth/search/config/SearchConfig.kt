package com.neviswealth.search.config

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "search")
interface SearchConfig {
    fun worker(): Worker

    fun lexical(): Lexical

    fun semantic(): Semantic

    interface Worker {
        fun interval(): java.time.Duration

        fun batchSize(): Int

        fun maxAttempts(): Int
    }

    interface Lexical {
        fun minSimilarity(): Double

        fun maxQueryLength(): Int

        fun defaultLimit(): Int

        fun maxLimit(): Int

        /** Candidates read from the match set before ranking (early cutoff). */
        fun candidateLimit(): Int
    }

    interface Semantic {
        fun minScore(): Double

        /** How many nearest chunks the HNSW index may return before dedup. */
        fun chunkCandidates(): Int

        /**
         * pgvector `hnsw.ef_search` for search connections. Default pgvector
         * value (40) shows poor recall at these vector counts — measured
         * top-10 recall needs ~400; keep it >= chunkCandidates.
         */
        fun efSearch(): Int
    }
}
