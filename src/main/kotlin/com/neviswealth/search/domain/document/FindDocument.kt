package com.neviswealth.search.domain.document

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@ApplicationScoped
class FindDocument
    @Inject
    constructor(
        private val storage: DocumentStorage,
    ) {
        fun find(
            clientId: UUID,
            documentId: UUID,
        ): Document? = storage.findById(clientId, documentId)

        /**
         * Keyset page of the client's documents, newest first (created_at DESC,
         * id DESC). Null means the client does not exist. One extra row is read
         * to know whether a next page exists.
         */
        fun list(
            clientId: UUID,
            limitOverride: Int?,
            cursor: String?,
        ): DocumentPage? {
            val limit = limitOverride ?: DEFAULT_LIMIT
            if (limit !in 1..MAX_LIMIT) {
                throw InvalidPaginationException("limit must be between 1 and $MAX_LIMIT")
            }
            val before = cursor?.let(::decodeCursor)
            val rows = storage.listByClient(clientId, limit + 1, before) ?: return null
            val page = rows.take(limit)
            val nextCursor = if (rows.size > limit) encodeCursor(page.last()) else null
            return DocumentPage(page, nextCursor)
        }

        private fun encodeCursor(document: DocumentMeta): String {
            // Instant ISO-8601 keeps microseconds; epoch millis would skip rows
            // that share a millisecond.
            val position = "${document.createdAt.toInstant()}|${document.id}"
            return Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(position.toByteArray(StandardCharsets.UTF_8))
        }

        private fun decodeCursor(cursor: String): DocumentKeyset {
            val decoded =
                runCatching {
                    String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                }.getOrElse { throw InvalidPaginationException("cursor is not valid") }
            val separator = decoded.indexOf('|')
            if (separator <= 0) throw InvalidPaginationException("cursor is not valid")
            val instant =
                runCatching { Instant.parse(decoded.substring(0, separator)) }
                    .getOrElse { throw InvalidPaginationException("cursor is not valid") }
            val id =
                runCatching { UUID.fromString(decoded.substring(separator + 1)) }
                    .getOrElse { throw InvalidPaginationException("cursor is not valid") }
            return DocumentKeyset(
                createdAt = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
                id = id,
            )
        }

        companion object {
            const val DEFAULT_LIMIT = 50
            const val MAX_LIMIT = 100
        }
    }
