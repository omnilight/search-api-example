package com.neviswealth.search.storage.postgres

import com.neviswealth.search.domain.document.Document
import com.neviswealth.search.domain.document.indexing.DocumentIndexStorage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class PostgresDocumentIndexStorage
    @Inject
    constructor(
        private val dataSource: DataSource,
    ) : DocumentIndexStorage {
        /**
         * Atomically claims pending documents for indexing. SKIP LOCKED keeps the
         * query safe under any number of parallel workers.
         */
        override fun claimPendingBatch(
            batchSize: Int,
            maxAttempts: Int,
        ): List<Document> {
            val sql =
                """
                UPDATE documents SET index_status = 'indexing'
                WHERE id IN (
                    SELECT id FROM documents
                    WHERE index_status = 'pending' AND attempts < ?
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, client_id, title, content
                """.trimIndent()
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setInt(1, maxAttempts)
                    statement.setInt(2, batchSize)
                    statement.executeQuery().use { rs ->
                        val rows = mutableListOf<Document>()
                        while (rs.next()) {
                            rows +=
                                Document(
                                    rs.getObject("id") as UUID,
                                    rs.getObject("client_id") as UUID,
                                    rs.getString("title"),
                                    rs.getString("content"),
                                    summary = null,
                                    indexStatus = "indexing",
                                    createdAt = OffsetDateTime.now(), // not used by the indexing scenario
                                )
                        }
                        return rows
                    }
                }
            }
        }

        override fun completeIndexing(
            documentId: UUID,
            chunks: List<Pair<String, FloatArray>>,
        ) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("DELETE FROM document_chunks WHERE document_id = ?").use { statement ->
                        statement.setObject(1, documentId)
                        statement.executeUpdate()
                    }
                    connection
                        .prepareStatement(
                            "INSERT INTO document_chunks (document_id, chunk_no, content, embedding) VALUES (?, ?, ?, ?)",
                        ).use { statement ->
                            chunks.forEachIndexed { chunkNo, (text, vector) ->
                                statement.setObject(1, documentId)
                                statement.setInt(2, chunkNo)
                                statement.setString(3, text)
                                statement.setObject(4, PgJdbc.pgVector(vector))
                                statement.addBatch()
                            }
                            statement.executeBatch()
                        }
                    connection
                        .prepareStatement(
                            "UPDATE documents SET index_status = 'indexed', summary = NULL, index_error = NULL WHERE id = ?",
                        ).use { statement ->
                            statement.setObject(1, documentId)
                            statement.executeUpdate()
                        }
                    connection.commit()
                } catch (e: Exception) {
                    runCatching { connection.rollback() }
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
        }

        override fun updateSummary(
            documentId: UUID,
            summary: String,
        ) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE documents SET summary = ?, index_error = NULL WHERE id = ?",
                    ).use { statement ->
                        statement.setString(1, summary)
                        statement.setObject(2, documentId)
                        statement.executeUpdate()
                    }
            }
        }

        override fun recordSummaryError(
            documentId: UUID,
            error: String,
        ) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE documents SET index_error = ? WHERE id = ?",
                    ).use { statement ->
                        statement.setString(1, "summary unavailable: ${error.take(2000)}")
                        statement.setObject(2, documentId)
                        statement.executeUpdate()
                    }
            }
        }

        override fun releaseClaim(documentId: UUID) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE documents SET index_status = 'pending' WHERE id = ? AND index_status = 'indexing'",
                    ).use { statement ->
                        statement.setObject(1, documentId)
                        statement.executeUpdate()
                    }
            }
        }

        override fun markFailed(
            documentId: UUID,
            error: String,
            maxAttempts: Int,
        ) {
            val sql =
                """
                UPDATE documents
                SET attempts = attempts + 1,
                    index_status = CASE WHEN attempts + 1 >= ? THEN 'failed' ELSE 'pending' END,
                    index_error = ?
                WHERE id = ?
                """.trimIndent()
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setInt(1, maxAttempts)
                    statement.setString(2, error.take(2000))
                    statement.setObject(3, documentId)
                    statement.executeUpdate()
                }
            }
        }

        override fun resetStaleIndexing() {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE documents SET index_status = 'pending' WHERE index_status = 'indexing'",
                    ).use { statement ->
                        statement.executeUpdate()
                    }
            }
        }
    }
