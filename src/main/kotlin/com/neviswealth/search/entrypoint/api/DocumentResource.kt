package com.neviswealth.search.entrypoint.api

import com.neviswealth.search.domain.document.FindDocument
import com.neviswealth.search.domain.document.StoreDocument
import com.neviswealth.search.generated.model.CreateDocumentRequest
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import java.util.UUID
import com.neviswealth.search.generated.model.Document as DocumentDto
import com.neviswealth.search.generated.model.DocumentPage as DocumentPageDto

@Path("/clients/{client_id}/documents")
@Produces(MediaType.APPLICATION_JSON)
class DocumentResource
    @Inject
    constructor(
        private val storeDocument: StoreDocument,
        private val findDocument: FindDocument,
    ) {
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        fun create(
            @PathParam("client_id") client_id: UUID,
            @Valid request: CreateDocumentRequest,
            @Context uriInfo: UriInfo,
        ): Response {
            val document = storeDocument.store(client_id, request.title, request.content)
            return Response
                .created(uriInfo.absolutePathBuilder.path(document.id.toString()).build())
                .entity(document.toDto())
                .build()
        }

        @GET
        fun list(
            @PathParam("client_id") client_id: UUID,
            @QueryParam("limit") limit: Int?,
            @QueryParam("cursor") cursor: String?,
        ): DocumentPageDto =
            findDocument.list(client_id, limit, cursor)?.toDto()
                ?: throw NotFoundException("Client $client_id not found")

        @GET
        @Path("{document_id}")
        fun get(
            @PathParam("client_id") client_id: UUID,
            @PathParam("document_id") document_id: UUID,
        ): DocumentDto =
            findDocument.find(client_id, document_id)?.toDto()
                ?: throw NotFoundException("Document $document_id not found")
    }
