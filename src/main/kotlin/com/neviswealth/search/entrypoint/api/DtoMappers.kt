package com.neviswealth.search.entrypoint.api

import com.neviswealth.search.domain.client.Client
import com.neviswealth.search.domain.document.Document
import com.neviswealth.search.domain.document.DocumentMeta
import com.neviswealth.search.domain.document.DocumentPage
import com.neviswealth.search.domain.query.SearchHit
import com.neviswealth.search.domain.query.SearchHitType
import com.neviswealth.search.generated.model.Client as ClientDto
import com.neviswealth.search.generated.model.Document as DocumentDto
import com.neviswealth.search.generated.model.DocumentPage as DocumentPageDto
import com.neviswealth.search.generated.model.DocumentSummary as DocumentSummaryDto
import com.neviswealth.search.generated.model.SearchResult as SearchResultDto

// The HTTP DTOs are generated from META-INF/openapi.yaml; the HTTP entrypoint
// owns their construction from the domain models.

fun Client.toDto(): ClientDto =
    ClientDto(
        id = id,
        firstName = firstName,
        lastName = lastName,
        email = email,
        description = description,
        socialLinks = socialLinks,
        createdAt = createdAt,
    )

fun Document.toDto(): DocumentDto =
    DocumentDto(
        id = id,
        clientId = clientId,
        title = title,
        content = content,
        summary = summary,
        indexStatus = DocumentDto.IndexStatus.valueOf(indexStatus),
        createdAt = createdAt,
    )

fun DocumentMeta.toDto(): DocumentSummaryDto =
    DocumentSummaryDto(
        id = id,
        clientId = clientId,
        title = title,
        summary = summary,
        indexStatus = DocumentSummaryDto.IndexStatus.valueOf(indexStatus),
        createdAt = createdAt,
    )

fun DocumentPage.toDto(): DocumentPageDto =
    DocumentPageDto(
        documents = documents.map { it.toDto() },
        nextCursor = nextCursor,
    )

fun SearchHit.toDto(): SearchResultDto =
    SearchResultDto(
        type =
            when (type) {
                SearchHitType.CLIENT -> SearchResultDto.Type.client
                SearchHitType.DOCUMENT -> SearchResultDto.Type.document
            },
        score = score,
        matched = matched,
        snippet = snippet,
        client = client?.toDto(),
        document = document?.toDto(),
    )
