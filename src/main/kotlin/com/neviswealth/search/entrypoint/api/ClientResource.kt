package com.neviswealth.search.entrypoint.api

import com.neviswealth.search.domain.client.CreateClient
import com.neviswealth.search.domain.client.FindClient
import com.neviswealth.search.generated.model.CreateClientRequest
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
import com.neviswealth.search.generated.model.Client as ClientDto

@Path("/clients")
@Produces(MediaType.APPLICATION_JSON)
class ClientResource
    @Inject
    constructor(
        private val createClient: CreateClient,
        private val findClient: FindClient,
    ) {
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        fun create(
            @Valid request: CreateClientRequest,
            @Context uriInfo: UriInfo,
        ): Response {
            val client =
                createClient.create(
                    firstName = request.firstName,
                    lastName = request.lastName,
                    email = request.email,
                    description = request.description,
                    socialLinks = request.socialLinks,
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
        ): ClientDto = findClient.find(id)?.toDto() ?: throw NotFoundException("Client $id not found")
    }
