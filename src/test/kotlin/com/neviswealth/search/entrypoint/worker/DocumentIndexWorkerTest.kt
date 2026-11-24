package com.neviswealth.search.entrypoint.worker

import com.neviswealth.search.FakeLlmClient
import com.neviswealth.search.FakeLlmTestProfile
import com.neviswealth.search.domain.document.indexing.IndexPendingDocuments
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(FakeLlmTestProfile::class)
class DocumentIndexWorkerTest {
    @Inject
    lateinit var indexPendingDocuments: IndexPendingDocuments

    @AfterEach
    fun resetFake() = FakeLlmClient.reset()

    /**
     * The app shares one database across test classes: drain documents that
     * earlier tests left pending so each poll here sees only its own rows.
     */
    @BeforeEach
    fun drainPendingQueue() {
        repeat(50) { indexPendingDocuments.pollOnce() }
    }

    @Test
    fun `transient LLM failures keep the document pending until the service heals`() {
        val clientId = createClient("Olga", "Ivanova", "olga-${System.nanoTime()}@example.com")
        val documentId = createDocument(clientId, "Payroll statement", "Monthly payroll and income statement for Q1.")

        FakeLlmClient.transientFailure = true
        indexPendingDocuments.pollOnce()

        // Ollama is down: the document stays queued, no attempts burned.
        assertEquals("pending", indexStatusOf(clientId, documentId))

        FakeLlmClient.reset()
        indexPendingDocuments.pollOnce()
        assertEquals("indexed", indexStatusOf(clientId, documentId))
    }

    @Test
    fun `repeated hard failures mark the document failed`() {
        val clientId = createClient("Petr", "Petrov", "petr-${System.nanoTime()}@example.com")
        val documentId = createDocument(clientId, "Poison", "Data that makes the fake fail.")

        FakeLlmClient.hardFailure = true
        repeat(3) { indexPendingDocuments.pollOnce() }

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
        indexPendingDocuments.pollOnce()

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

    @Test
    fun `storage failure during indexing does not leave the document stuck in indexing`() {
        val clientId = createClient("Vera", "Klein", "vera-${System.nanoTime()}@example.com")
        val documentId = createDocument(clientId, "Broken embeddings", "Content whose vectors have the wrong size.")

        FakeLlmClient.badVectorDimensions = true
        repeat(3) { indexPendingDocuments.pollOnce() }

        // markFailed counts attempts, so the document leaves 'indexing' and ends 'failed'.
        assertEquals("failed", indexStatusOf(clientId, documentId))
    }

    @Test
    fun `a failing document does not stop the rest of the batch`() {
        val clientId = createClient("Klara", "Batch", "klara-${System.nanoTime()}@example.com")
        val brokenId = createDocument(clientId, "Poison record", "content with POISON_EMBED_MARKER inside")
        val healthyId = createDocument(clientId, "Healthy record", "ordinary billable content")

        FakeLlmClient.poisonMarker = "POISON_EMBED_MARKER"
        indexPendingDocuments.pollOnce()

        // The failing document left 'indexing' (attempt counted, still retryable)...
        assertEquals("pending", indexStatusOf(clientId, brokenId))
        // ...and the healthy document in the same batch was still indexed.
        assertEquals("indexed", indexStatusOf(clientId, healthyId))
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
