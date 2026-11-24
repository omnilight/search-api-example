package com.neviswealth.search.domain.client

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Creates a client, normalizing the input the way the API contract expects. */
@ApplicationScoped
class CreateClient
    @Inject
    constructor(
        private val storage: ClientStorage,
    ) {
        fun create(
            firstName: String,
            lastName: String,
            email: String,
            description: String?,
            socialLinks: List<String>?,
        ): Client =
            storage.create(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
                // Lowercase so the unique email constraint is case-insensitive.
                email = email.trim().lowercase(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                socialLinks = socialLinks.orEmpty(),
            )
    }
