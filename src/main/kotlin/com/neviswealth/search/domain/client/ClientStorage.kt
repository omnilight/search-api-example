package com.neviswealth.search.domain.client

import java.util.UUID

interface ClientStorage {
    fun create(
        firstName: String,
        lastName: String,
        email: String,
        description: String?,
        socialLinks: List<String>,
    ): Client

    fun findById(id: UUID): Client?
}
