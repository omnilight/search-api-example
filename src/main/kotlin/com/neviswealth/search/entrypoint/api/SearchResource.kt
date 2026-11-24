package com.neviswealth.search.entrypoint.api

import com.neviswealth.search.domain.query.Search
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import com.neviswealth.search.generated.model.SearchResult as SearchResultDto

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
class SearchResource
    @Inject
    constructor(
        private val search: Search,
    ) {
        @GET
        fun search(
            @QueryParam("q") q: String?,
            @QueryParam("type") type: String?,
            @QueryParam("limit") limit: Int?,
        ): List<SearchResultDto> = search.search(q.orEmpty(), type, limit).map { it.toDto() }
    }
