package com.neviswealth.search.entrypoint.api

import com.neviswealth.search.domain.client.ClientAlreadyExistsException
import com.neviswealth.search.domain.client.ClientNotFoundException
import com.neviswealth.search.domain.document.InvalidPaginationException
import com.neviswealth.search.domain.query.InvalidSearchQueryException
import com.neviswealth.search.generated.model.Problem
import io.quarkus.runtime.annotations.RegisterForReflection
import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

// The DTO is returned as an untyped Response entity, so Quarkus cannot infer it.
@RegisterForReflection(targets = [Problem::class])
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

/** HTTP mapping of errors; the contract promises Problem JSON for every 4xx response. */
class ApiExceptionMappers {
    @ServerExceptionMapper
    fun emailInUse(e: ClientAlreadyExistsException): Response = Problems.of(409, "Email already in use", e.message)

    @ServerExceptionMapper
    fun clientNotFound(e: ClientNotFoundException): Response = Problems.of(404, "Client not found", e.message)

    @ServerExceptionMapper
    fun invalidQuery(e: InvalidSearchQueryException): Response = Problems.of(400, "Invalid search query", e.message)

    @ServerExceptionMapper
    fun invalidPagination(e: InvalidPaginationException): Response = Problems.of(400, "Invalid pagination", e.message)

    @ServerExceptionMapper
    fun invalidBody(e: ConstraintViolationException): Response =
        Problems.of(
            400,
            "Validation failed",
            e.constraintViolations.joinToString("; ") { "${it.propertyPath}: ${it.message}" },
        )

    @ServerExceptionMapper
    fun notFound(e: NotFoundException): Response = Problems.of(404, "Not found", e.message)

    @ServerExceptionMapper
    fun badRequest(e: BadRequestException): Response = Problems.of(400, "Bad request", e.message)
}
