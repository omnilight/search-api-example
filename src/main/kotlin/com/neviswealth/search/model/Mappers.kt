package com.neviswealth.search.model

import com.neviswealth.search.db.ClientRepository.ClientRow
import com.neviswealth.search.db.DocumentRepository.DocumentMetaRow
import com.neviswealth.search.db.DocumentRepository.DocumentRow
import com.neviswealth.search.generated.model.Client
import com.neviswealth.search.generated.model.Document
import com.neviswealth.search.generated.model.DocumentSummary

// The HTTP DTOs are generated from META-INF/openapi.yaml; these mappers bridge
// the handwritten database rows to the generated models.

fun ClientRow.toDto() =
    Client(
        id = id,
        firstName = firstName,
        lastName = lastName,
        email = email,
        description = description,
        socialLinks = socialLinks,
        createdAt = createdAt,
    )

fun DocumentRow.toDto() =
    Document(
        id = id,
        clientId = clientId,
        title = title,
        content = content,
        summary = summary,
        indexStatus = Document.IndexStatus.valueOf(indexStatus),
        createdAt = createdAt,
    )

fun DocumentMetaRow.toDto() =
    DocumentSummary(
        id = id,
        clientId = clientId,
        title = title,
        summary = summary,
        indexStatus = DocumentSummary.IndexStatus.valueOf(indexStatus),
        createdAt = createdAt,
    )
