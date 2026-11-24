package com.neviswealth.search.domain.document

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

/** Stores a new document for a client; indexing happens in the background. */
@ApplicationScoped
class StoreDocument
    @Inject
    constructor(
        private val storage: DocumentStorage,
    ) {
        fun store(
            clientId: UUID,
            title: String,
            content: String,
        ): Document = storage.create(clientId, title.trim(), content)
    }
