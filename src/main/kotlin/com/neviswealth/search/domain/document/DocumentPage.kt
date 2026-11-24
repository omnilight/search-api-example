package com.neviswealth.search.domain.document

import java.time.OffsetDateTime
import java.util.UUID

/** One page of a client's documents plus the cursor of the next page. */
data class DocumentPage(
    val documents: List<DocumentMeta>,
    val nextCursor: String?,
)

/** Keyset position: the last row of the previous page (created_at DESC, id DESC). */
data class DocumentKeyset(
    val createdAt: OffsetDateTime,
    val id: UUID,
)

class InvalidPaginationException(
    message: String,
) : RuntimeException(message)
