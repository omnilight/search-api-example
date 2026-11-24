package com.neviswealth.search.api

import com.neviswealth.search.db.ClientNotFoundException
import com.neviswealth.search.db.EmailAlreadyUsedException
import com.neviswealth.search.generated.model.Problem
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

object Problems {
    fun of(
        status: Int,
        title: String,
        detail: String?,
    ): Response =
        Response
            .status(status)
            .type("application/problem+json")
            .entity(Problem(title = title, status = status, detail = detail))
            .build()
}

class ApiExceptionMappers {
    @ServerExceptionMapper
    fun emailInUse(e: EmailAlreadyUsedException): Response = Problems.of(409, "Email already in use", e.message)

    @ServerExceptionMapper
    fun clientNotFound(e: ClientNotFoundException): Response = Problems.of(404, "Client not found", e.message)
}
