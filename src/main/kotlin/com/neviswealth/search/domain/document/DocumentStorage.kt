package com.neviswealth.search.domain.document

import java.util.UUID

interface DocumentStorage {
    fun create(
        clientId: UUID,
        title: String,
        content: String,
    ): Document

    fun findById(
        clientId: UUID,
        documentId: UUID,
    ): Document?

    /**
     * Keyset page of the client's documents, newest first. Null means the
     * client does not exist; [before] is exclusive.
     */
    fun listByClient(
        clientId: UUID,
        fetchLimit: Int,
        before: DocumentKeyset?,
    ): List<DocumentMeta>?
}
