package com.neviswealth.search.domain.document

import java.time.OffsetDateTime
import java.util.UUID

/** Full document, including content. */
data class Document(
    val id: UUID,
    val clientId: UUID,
    val title: String,
    val content: String,
    val summary: String?,
    val indexStatus: String,
    val createdAt: OffsetDateTime,
)

/** Document metadata without content: lists and search results stay compact. */
data class DocumentMeta(
    val id: UUID,
    val clientId: UUID,
    val title: String,
    val summary: String?,
    val indexStatus: String,
    val createdAt: OffsetDateTime,
)
