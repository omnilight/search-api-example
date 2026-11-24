package com.neviswealth.search.db

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.postgresql.util.PGobject
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class ClientNotFoundException(
    val clientId: UUID,
) : RuntimeException("Client $clientId not found")

@ApplicationScoped
class DocumentRepository
    @Inject
    constructor(
        private val dataSource: DataSource,
    ) {
        data class DocumentRow(
            val id: UUID,
            val clientId: UUID,
            val title: String,
            val content: String,
            val summary: String?,
            val indexStatus: String,
            val createdAt: OffsetDateTime,
        )

        /** Document metadata without content (lists and search results). */
        data class DocumentMetaRow(
            val id: UUID,
            val clientId: UUID,
            val title: String,
            val summary: String?,
            val indexStatus: String,
            val createdAt: OffsetDateTime,
        )

        data class DocumentSearchRow(
            val document: DocumentMetaRow,
            val score: Double,
            val matched: Set<String>,
            val snippet: String?,
        )

        fun create(
            clientId: UUID,
            title: String,
            content: String,
        ): DocumentRow {
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
                                return DocumentRow(
                                    id,
                                    clientId,
                                    title,
                                    content,
                                    rs.getString("summary"),
                                    rs.getString("index_status"),
                                    rs.getObject("created_at", OffsetDateTime::class.java),
                                )
                            }
                        }
                } catch (e: PSQLException) {
                    if (e.sqlState == "23503") throw ClientNotFoundException(clientId)
                    throw e
                }
            }
        }

        fun findById(
            clientId: UUID,
            documentId: UUID,
        ): DocumentRow? {
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
                            return if (rs.next()) mapDocument(rs) else null
                        }
                    }
            }
        }

        /** Null means the client does not exist. */
        fun listByClient(clientId: UUID): List<DocumentMetaRow>? {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT 1 FROM clients WHERE id = ?").use { statement ->
                    statement.setObject(1, clientId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return null
                    }
                }
                connection
                    .prepareStatement(
                        """
                        SELECT id, client_id, title, summary, index_status, created_at
                        FROM documents WHERE client_id = ? ORDER BY created_at DESC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, clientId)
                        statement.executeQuery().use { rs ->
                            val rows = mutableListOf<DocumentMetaRow>()
                            while (rs.next()) rows += mapMeta(rs)
                            return rows
                        }
                    }
            }
        }

        /**
         * Atomically claims pending documents for indexing. SKIP LOCKED keeps
         * the query safe under any number of parallel workers.
         */
        fun claimPendingBatch(
            batchSize: Int,
            maxAttempts: Int,
        ): List<DocumentRow> {
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
                        val rows = mutableListOf<DocumentRow>()
                        while (rs.next()) {
                            rows +=
                                DocumentRow(
                                    rs.getObject("id") as UUID,
                                    rs.getObject("client_id") as UUID,
                                    rs.getString("title"),
                                    rs.getString("content"),
                                    summary = null,
                                    indexStatus = "indexing",
                                    createdAt = OffsetDateTime.now(), // not used by the worker
                                )
                        }
                        return rows
                    }
                }
            }
        }

        /**
         * Stores chunk embeddings and marks the document searchable in one
         * transaction: DELETE + INSERT chunks, then status 'indexed'. The summary
         * is written separately, so a failure of the optional chat model never
         * blocks semantic search.
         */
        fun completeIndexing(
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
                                statement.setObject(4, pgVector(vector))
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

        /** Best-effort summary update; the document is already searchable. */
        fun updateSummary(
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

        /** Records why the optional summary is missing; status stays 'indexed'. */
        fun recordSummaryError(
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

        /** Back to 'pending': transient LLM failures are expected to heal. */
        fun releaseClaim(documentId: UUID) {
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

        /** Counts a hard failure; documents end up in 'failed' after [maxAttempts]. */
        fun markFailed(
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

        /** Recovery after a crash: documents stuck in 'indexing' go back to the queue. */
        fun resetStaleIndexing() {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE documents SET index_status = 'pending' WHERE index_status = 'indexing'",
                    ).use { statement ->
                        statement.executeUpdate()
                    }
            }
        }

        /** Semantic search: cosine similarity between the query vector and chunk embeddings. */
        fun semanticSearch(
            queryVector: FloatArray,
            minScore: Double,
            limit: Int,
        ): List<DocumentSearchRow> {
            val vector = pgVector(queryVector)
            val sql =
                """
                SELECT d.id, d.client_id, d.title, d.summary, d.index_status, d.created_at,
                       MAX(1 - (dc.embedding <=> ?)) AS score,
                       (ARRAY_AGG(dc.content ORDER BY 1 - (dc.embedding <=> ?) DESC))[1] AS snippet
                FROM documents d
                JOIN document_chunks dc ON dc.document_id = d.id
                WHERE 1 - (dc.embedding <=> ?) >= ?
                GROUP BY d.id, d.client_id, d.title, d.summary, d.index_status, d.created_at
                ORDER BY score DESC
                LIMIT ?
                """.trimIndent()
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, vector)
                    statement.setObject(2, vector)
                    statement.setObject(3, vector)
                    statement.setDouble(4, minScore)
                    statement.setInt(5, limit)
                    statement.executeQuery().use { rs ->
                        val rows = mutableListOf<DocumentSearchRow>()
                        while (rs.next()) {
                            rows += DocumentSearchRow(mapMeta(rs), rs.getDouble("score"), setOf("content"), rs.getString("snippet"))
                        }
                        return rows
                    }
                }
            }
        }

        /** Lexical search over title and content; snippet is cut around the first hit. */
        fun lexicalSearch(
            phrase: String,
            tokenRegex: String?,
            minSimilarity: Double,
            limit: Int,
        ): List<DocumentSearchRow> {
            val regex = tokenRegex ?: "a^"
            val sql =
                """
                SELECT id, client_id, title, summary, index_status, created_at, content,
                       GREATEST(similarity(lower(title), ?), similarity(lower(content), ?)) AS sim,
                       (lower(title) ~ ?) AS title_hit,
                       (lower(content) ~ ?) AS content_hit
                FROM documents
                WHERE lower(title) ~ ?
                   OR lower(content) ~ ?
                   OR similarity(lower(title), ?) >= ?
                   OR similarity(lower(content), ?) >= ?
                ORDER BY sim DESC
                LIMIT ?
                """.trimIndent()
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    var i = 0
                    statement.setString(++i, phrase)
                    statement.setString(++i, phrase)
                    statement.setString(++i, regex)
                    statement.setString(++i, regex)
                    statement.setString(++i, regex)
                    statement.setString(++i, regex)
                    statement.setString(++i, phrase)
                    statement.setDouble(++i, minSimilarity)
                    statement.setString(++i, phrase)
                    statement.setDouble(++i, minSimilarity)
                    statement.setInt(++i, limit)

                    statement.executeQuery().use { rs ->
                        val rows = mutableListOf<DocumentSearchRow>()
                        while (rs.next()) {
                            val matched =
                                buildSet {
                                    if (rs.getBoolean("title_hit")) add("title")
                                    if (rs.getBoolean("content_hit")) add("content")
                                }
                            val similarity = rs.getDouble("sim")
                            val content = rs.getString("content")
                            rows +=
                                DocumentSearchRow(
                                    mapMeta(rs),
                                    if (matched.isNotEmpty()) 1.0 else similarity,
                                    matched.ifEmpty {
                                        setOf(if (similarity >= minSimilarity) "content" else "title")
                                    },
                                    snippetAround(content, tokenRegex),
                                )
                        }
                        return rows
                    }
                }
            }
        }

        private fun mapMeta(rs: ResultSet): DocumentMetaRow =
            DocumentMetaRow(
                id = rs.getObject("id") as UUID,
                clientId = rs.getObject("client_id") as UUID,
                title = rs.getString("title"),
                summary = rs.getString("summary"),
                indexStatus = rs.getString("index_status"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            )

        private fun mapDocument(rs: ResultSet): DocumentRow =
            DocumentRow(
                id = rs.getObject("id") as UUID,
                clientId = rs.getObject("client_id") as UUID,
                title = rs.getString("title"),
                content = rs.getString("content"),
                summary = rs.getString("summary"),
                indexStatus = rs.getString("index_status"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            )

        private fun snippetAround(
            content: String,
            tokenRegex: String?,
        ): String {
            val window = 160
            val match =
                tokenRegex?.let { regex -> Regex(regex).find(content.lowercase()) }
                    ?: return content.take(window).replace(Regex("\\s+"), " ")
            val start = maxOf(0, match.range.first - 40)
            val end = minOf(content.length, match.range.last + window)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < content.length) "…" else ""
            return (prefix + content.substring(start, end) + suffix).replace(Regex("\\s+"), " ")
        }

        private fun pgVector(vector: FloatArray): PGobject =
            PGobject().apply {
                type = "vector"
                value = vector.joinToString(",", prefix = "[", postfix = "]") { it.toString() }
            }
    }
