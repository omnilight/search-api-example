package com.neviswealth.search.domain.client

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

@ApplicationScoped
class FindClient
    @Inject
    constructor(
        private val storage: ClientStorage,
    ) {
        fun find(id: UUID): Client? = storage.findById(id)
    }
