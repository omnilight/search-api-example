package com.neviswealth.search.api

import com.neviswealth.search.FakeLlmClient
import com.neviswealth.search.FakeLlmTestProfile
import com.neviswealth.search.worker.IndexWorker
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.emptyString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@TestProfile(FakeLlmTestProfile::class)
class SearchApiTest {
    @Inject
    lateinit var worker: IndexWorker

    @BeforeEach
    fun resetFake() = FakeLlmClient.reset()

    @Test
    fun `search finds a client by camelCase company token in the email`() {
        val email = "john.doe-${System.nanoTime()}@neviswealth.com"
        createClient("John", "Doe", email, "Senior advisor")

        given()
            .queryParam("q", "NevisWealth")
            .`when`()
            .get("/search")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].type", equalTo("client"))
            .body("[0].matched", hasItem("email"))
            .body("[0].client.email", equalTo(email))
            .body("[0].score", equalTo(1.0f))
    }

    @Test
    fun `search finds a client by name parts`() {
        val email = "maria.garcia-${System.nanoTime()}@example.com"
        createClient("Maria", "Garcia", email, null)

        given()
            .queryParam("q", "Maria Garcia")
            .queryParam("type", "client")
            .`when`()
            .get("/search")
            .then()
            .statusCode(200)
            .body("[0].client.email", equalTo(email))
    }

    @Test
    fun `semantic search finds a document by related terms`() {
        val clientId = createClient("Alice", "Smith", "alice-${System.nanoTime()}@example.com", null)
        // The title deliberately avoids the embedded terms so the hit comes
        // from the content chunks, not from the title chunk.
        val documentId =
            createDocument(
                clientId,
                "Residency verification document",
                "Monthly electricity utility bill for 42 Baker Street, kept on file as the household's official utility bill record.",
            )
        // Negative case: a document of a different kind that must not match.
        val passportId =
            createDocument(
                clientId,
                "Passport scan",
                "Certified scan of the biometric passport: identity page with photo, passport number, issue and expiry dates.",
            )

        worker.pollOnce()

        // The optional feature: the worker also produced a summary.
        given()
            .`when`()
            .get("/clients/{clientId}/documents/{documentId}", clientId, documentId)
            .then()
            .statusCode(200)
            .body("index_status", equalTo("indexed"))
            .body("summary", not(emptyString()))

        given()
            .queryParam("q", "address proof")
            .queryParam("type", "document")
            .`when`()
            .get("/search")
            .then()
            .statusCode(200)
            .body("find { it.document.id == '$documentId' }.type", equalTo("document"))
            .body("find { it.document.id == '$documentId' }.matched", hasItem("content"))
            .body("find { it.document.id == '$documentId' }.document.title", equalTo("Residency verification document"))
            .body("find { it.document.id == '$documentId' }.snippet", containsString("utility bill"))
            .body("find { it.document.id == '$documentId' }.score", notNullValue())
            .body("find { it.document.id == '$passportId' }", nullValue())
    }

    @Test
    fun `search returns an empty list for unrelated terms`() {
        given()
            .queryParam("q", "zzzqqq ${System.nanoTime()}")
            .`when`()
            .get("/search")
            .then()
            .statusCode(200)
            .body("size()", equalTo(0))
    }

    @Test
    fun `invalid email is rejected`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"first_name":"A","last_name":"B","email":"not-an-email"}""")
            .`when`()
            .post("/clients")
            .then()
            .statusCode(400)
    }

    @Test
    fun `missing required fields are rejected`() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .`when`()
            .post("/clients")
            .then()
            .statusCode(400)
    }

    @Test
    fun `duplicate email in any case returns 409`() {
        val email = "dup-${System.nanoTime()}@example.com"
        createClient("First", "User", email, null)

        given()
            .contentType(ContentType.JSON)
            .body("""{"first_name":"Second","last_name":"User","email":"${email.uppercase()}"}""")
            .`when`()
            .post("/clients")
            .then()
            .statusCode(409)
    }

    @Test
    fun `document for unknown client returns 404`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"title":"T","content":"C"}""")
            .`when`()
            .post("/clients/{id}/documents", UUID.randomUUID())
            .then()
            .statusCode(404)
    }

    @Test
    fun `search requires the q parameter`() {
        given()
            .`when`()
            .get("/search")
            .then()
            .statusCode(400)
    }

    @Test
    fun `search rejects a bogus type`() {
        given()
            .queryParam("q", "x")
            .queryParam("type", "bogus")
            .`when`()
            .get("/search")
            .then()
            .statusCode(400)
    }

    @Test
    fun `search rejects limit zero`() {
        given()
            .queryParam("q", "x")
            .queryParam("limit", 0)
            .`when`()
            .get("/search")
            .then()
            .statusCode(400)
    }

    private fun createClient(
        first: String,
        last: String,
        email: String,
        description: String?,
    ): String {
        val body =
            buildString {
                append("""{"first_name":"$first","last_name":"$last","email":"$email"""")
                if (description != null) append(""","description":"$description"""")
                append("}")
            }
        return given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`()
            .post("/clients")
            .then()
            .statusCode(201)
            .extract()
            .path<String>("id")
    }

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
            .path<String>("id")
}
