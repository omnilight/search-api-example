package com.neviswealth.search.storage.postgres

import com.neviswealth.search.domain.client.Client
import com.neviswealth.search.domain.document.Document
import com.neviswealth.search.domain.document.DocumentMeta
import org.postgresql.util.PGobject
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Shared JDBC helpers for the Postgres adapters. */
internal object PgJdbc {
    fun pgVector(vector: FloatArray): PGobject =
        PGobject().apply {
            type = "vector"
            value = vector.joinToString(",", prefix = "[", postfix = "]") { it.toString() }
        }

    fun mapClient(rs: ResultSet): Client =
        Client(
            id = rs.getObject("id") as UUID,
            firstName = rs.getString("first_name"),
            lastName = rs.getString("last_name"),
            email = rs.getString("email"),
            description = rs.getString("description"),
            socialLinks = (rs.getArray("social_links").array as Array<*>).map { it.toString() },
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        )

    fun mapDocument(rs: ResultSet): Document =
        Document(
            id = rs.getObject("id") as UUID,
            clientId = rs.getObject("client_id") as UUID,
            title = rs.getString("title"),
            content = rs.getString("content"),
            summary = rs.getString("summary"),
            indexStatus = rs.getString("index_status"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        )

    fun mapDocumentMeta(rs: ResultSet): DocumentMeta =
        DocumentMeta(
            id = rs.getObject("id") as UUID,
            clientId = rs.getObject("client_id") as UUID,
            title = rs.getString("title"),
            summary = rs.getString("summary"),
            indexStatus = rs.getString("index_status"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        )
}
