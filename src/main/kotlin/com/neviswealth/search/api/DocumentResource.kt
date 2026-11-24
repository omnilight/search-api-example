package com.neviswealth.search.api

import com.neviswealth.search.db.DocumentRepository
import com.neviswealth.search.generated.model.CreateDocumentRequest
import com.neviswealth.search.generated.model.Document
import com.neviswealth.search.generated.model.DocumentSummary
import com.neviswealth.search.model.toDto
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import java.util.UUID

@Path("/clients/{client_id}/documents")
@Produces(MediaType.APPLICATION_JSON)
class DocumentResource
    @Inject
    constructor(
        private val documents: DocumentRepository,
    ) {
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        fun create(
            @PathParam("client_id") client_id: UUID,
            @Valid request: CreateDocumentRequest,
            @Context uriInfo: UriInfo,
        ): Response {
            val document = documents.create(client_id, request.title.trim(), request.content)
            return Response
                .created(uriInfo.absolutePathBuilder.path(document.id.toString()).build())
                .entity(document.toDto())
                .build()
        }

        @GET
        fun list(
            @PathParam("client_id") client_id: UUID,
        ): List<DocumentSummary> =
            documents.listByClient(client_id)?.map { it.toDto() }
                ?: throw NotFoundException("Client $client_id not found")

        @GET
        @Path("{document_id}")
        fun get(
            @PathParam("client_id") client_id: UUID,
            @PathParam("document_id") document_id: UUID,
        ): Document =
            documents.findById(client_id, document_id)?.toDto()
                ?: throw NotFoundException("Document $document_id not found")
    }
