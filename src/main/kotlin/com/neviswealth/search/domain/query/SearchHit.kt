package com.neviswealth.search.domain.query

import com.neviswealth.search.domain.client.Client
import com.neviswealth.search.domain.document.DocumentMeta

/**
 * One entry of the ranked search result. Scores are per type: clients get 1.0
 * for a substring hit and otherwise a pg_trgm similarity; documents get the
 * cosine similarity of the best chunk (or the same lexical scheme when only
 * lexical matching applied).
 */
data class SearchHit(
    val type: SearchHitType,
    val score: Double,
    val matched: List<String>,
    val snippet: String?,
    val client: Client?,
    val document: DocumentMeta?,
)

enum class SearchHitType { CLIENT, DOCUMENT }

class InvalidSearchQueryException(
    message: String,
) : RuntimeException(message)
