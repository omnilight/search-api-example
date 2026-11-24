package com.neviswealth.search.worker

import com.neviswealth.search.FakeLlmClient
import com.neviswealth.search.FakeLlmTestProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(FakeLlmTestProfile::class)
class IndexWorkerTest {
    @Inject
    lateinit var worker: IndexWorker

    @AfterEach
    fun resetFake() = FakeLlmClient.reset()

    @Test
    fun `transient LLM failures keep the document pending until the service heals`() {
        val clientId = createClient("Olga", "Ivanova", "olga-${System.nanoTime()}@example.com")
        val documentId = createDocument(clientId, "Payroll statement", "Monthly payroll and income statement for Q1.")

        FakeLlmClient.transientFailure = true
        worker.pollOnce()

        // Ollama is down: the document stays queued, no attempts burned.
        assertEquals("pending", indexStatusOf(clientId, documentId))

        FakeLlmClient.reset()
        worker.pollOnce()
        assertEquals("indexed", indexStatusOf(clientId, documentId))
    }

    @Test
    fun `repeated hard failures mark the document failed`() {
        val clientId = createClient("Petr", "Petrov", "petr-${System.nanoTime()}@example.com")
        val documentId = createDocument(clientId, "Poison", "Data that makes the fake fail.")

        FakeLlmClient.hardFailure = true
        repeat(3) { worker.pollOnce() }

        assertEquals("failed", indexStatusOf(clientId, documentId))
    }

    @Test
    fun `summary failure does not block semantic indexing`() {
        val clientId = createClient("Irina", "Petrova", "irina-${System.nanoTime()}@example.com")
        val documentId =
            createDocument(
                clientId,
                "Household record",
                "Monthly electricity utility bill kept as the household's official utility bill record.",
            )

        FakeLlmClient.summaryFailure = true
        worker.pollOnce()

        // Embeddings are stored even though the optional summary failed.
        assertEquals("indexed", indexStatusOf(clientId, documentId))

        given()
            .queryParam("q", "address proof")
            .queryParam("type", "document")
            .`when`()
            .get("/search")
            .then()
            .statusCode(200)
            .body("find { it.document.id == '$documentId' }.document.id", equalTo(documentId))
    }

    private fun indexStatusOf(
        clientId: String,
        documentId: String,
    ): String =
        given()
            .`when`()
            .get("/clients/{clientId}/documents/{documentId}", clientId, documentId)
            .then()
            .statusCode(200)
            .extract()
            .path("index_status")

    private fun createClient(
        first: String,
        last: String,
        email: String,
    ): String =
        given()
            .contentType(ContentType.JSON)
            .body("""{"first_name":"$first","last_name":"$last","email":"$email"}""")
            .`when`()
            .post("/clients")
            .then()
            .statusCode(201)
            .extract()
            .path("id")

    private fun createDocument(
        clientId: String,
        title: String,
        content: String,
    ): String =
        given()
            .contentType(ContentType.JSON)
            .body("""{"title":"$title","content":"$content"}""")
            .`when`()
            .post("/clients/{clientId}/documents", clientId)
            .then()
            .statusCode(201)
            .extract()
            .path("id")
}
