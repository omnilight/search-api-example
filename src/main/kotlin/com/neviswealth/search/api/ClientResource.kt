package com.neviswealth.search.api

import com.neviswealth.search.db.ClientRepository
import com.neviswealth.search.generated.model.Client
import com.neviswealth.search.generated.model.CreateClientRequest
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

@Path("/clients")
@Produces(MediaType.APPLICATION_JSON)
class ClientResource
    @Inject
    constructor(
        private val clients: ClientRepository,
    ) {
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        fun create(
            @Valid request: CreateClientRequest,
            @Context uriInfo: UriInfo,
        ): Response {
            val client =
                clients.create(
                    firstName = request.firstName.trim(),
                    lastName = request.lastName.trim(),
                    // Lowercase so the unique email constraint is case-insensitive.
                    email = request.email.trim().lowercase(),
                    description = request.description?.trim()?.takeIf { it.isNotEmpty() },
                    socialLinks = request.socialLinks.orEmpty(),
                )
            return Response
                .created(uriInfo.absolutePathBuilder.path(client.id.toString()).build())
                .entity(client.toDto())
                .build()
        }

        @GET
        @Path("{id}")
        fun get(
            @PathParam("id") id: UUID,
        ): Client = clients.findById(id)?.toDto() ?: throw NotFoundException("Client $id not found")
    }
