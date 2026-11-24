package com.neviswealth.search.api

import com.neviswealth.search.generated.model.SearchResult
import com.neviswealth.search.service.SearchService
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
class SearchResource
    @Inject
    constructor(
        private val search: SearchService,
    ) {
        @GET
        fun search(
            @QueryParam("q") q: String?,
            @QueryParam("type") type: String?,
            @QueryParam("limit") limit: Int?,
        ): List<SearchResult> {
            if (q.isNullOrBlank()) throw BadRequestException("Query parameter 'q' is required")
            return search.search(q, type, limit)
        }
    }
