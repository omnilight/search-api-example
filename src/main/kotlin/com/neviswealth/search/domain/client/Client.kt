package com.neviswealth.search.domain.client

import java.time.OffsetDateTime
import java.util.UUID

data class Client(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val email: String,
    val description: String?,
    val socialLinks: List<String>,
    val createdAt: OffsetDateTime,
)

class ClientAlreadyExistsException(
    val email: String,
) : RuntimeException("Email '$email' is already registered")

class ClientNotFoundException(
    val clientId: UUID,
) : RuntimeException("Client $clientId not found")
