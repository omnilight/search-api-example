package com.neviswealth.search.storage.postgres

import com.neviswealth.search.domain.client.ClientNotFoundException
import com.neviswealth.search.domain.document.Document
import com.neviswealth.search.domain.document.DocumentKeyset
import com.neviswealth.search.domain.document.DocumentMeta
import com.neviswealth.search.domain.document.DocumentStorage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.postgresql.util.PSQLException
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresDocumentStorage
    @Inject
    constructor(
        private val dataSource: DataSource,
    ) : DocumentStorage {
        override fun create(
            clientId: UUID,
            title: String,
            content: String,
        ): Document {
            val id = UUID.randomUUID()
            dataSource.connection.use { connection ->
                try {
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO documents (id, client_id, title, content)
                            VALUES (?, ?, ?, ?)
                            RETURNING summary, index_status, created_at
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, id)
                            statement.setObject(2, clientId)
                            statement.setString(3, title)
                            statement.setString(4, content)
                            statement.executeQuery().use { rs ->
                                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                                return Document(
                                    id,
                                    clientId,
                                    title,
                                    content,
                                    rs.getString("summary"),
                                    rs.getString("index_status"),
                                    rs.getObject("created_at", java.time.OffsetDateTime::class.java),
                                )
                            }
                        }
                } catch (e: PSQLException) {
                    if (e.sqlState == "23503") throw ClientNotFoundException(clientId)
                    throw e
                }
            }
        }

        override fun findById(
            clientId: UUID,
            documentId: UUID,
        ): Document? {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, client_id, title, content, summary, index_status, created_at
                        FROM documents WHERE id = ? AND client_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, documentId)
                        statement.setObject(2, clientId)
                        statement.executeQuery().use { rs ->
                            return if (rs.next()) PgJdbc.mapDocument(rs) else null
                        }
                    }
            }
        }

        /** Keyset page: `(created_at, id) < (?, ?)` served by documents_client_created_idx. */
        override fun listByClient(
            clientId: UUID,
            fetchLimit: Int,
            before: DocumentKeyset?,
        ): List<DocumentMeta>? {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT 1 FROM clients WHERE id = ?").use { statement ->
                    statement.setObject(1, clientId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return null
                    }
                }
                val sql =
                    if (before == null) {
                        """
                        SELECT id, client_id, title, summary, index_status, created_at
                        FROM documents WHERE client_id = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT ?
                        """.trimIndent()
                    } else {
                        """
                        SELECT id, client_id, title, summary, index_status, created_at
                        FROM documents WHERE client_id = ? AND (created_at, id) < (?, ?)
                        ORDER BY created_at DESC, id DESC
                        LIMIT ?
                        """.trimIndent()
                    }
                connection.prepareStatement(sql).use { statement ->
                    var i = 0
                    statement.setObject(++i, clientId)
                    if (before != null) {
                        statement.setObject(++i, before.createdAt)
                        statement.setObject(++i, before.id)
                    }
                    statement.setInt(++i, fetchLimit)
                    statement.executeQuery().use { rs ->
                        val rows = mutableListOf<DocumentMeta>()
                        while (rs.next()) rows += PgJdbc.mapDocumentMeta(rs)
                        return rows
                    }
                }
            }
        }
    }
