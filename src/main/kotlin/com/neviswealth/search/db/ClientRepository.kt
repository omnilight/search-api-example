package com.neviswealth.search.db

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class EmailAlreadyUsedException(
    val email: String,
) : RuntimeException("Email '$email' is already registered")

@ApplicationScoped
class ClientRepository
    @Inject
    constructor(
        private val dataSource: DataSource,
    ) {
        data class ClientRow(
            val id: UUID,
            val firstName: String,
            val lastName: String,
            val email: String,
            val description: String?,
            val socialLinks: List<String>,
            val createdAt: OffsetDateTime,
        )

        data class ClientSearchRow(
            val client: ClientRow,
            val similarity: Double,
            /** Fields with a direct substring hit, by their snake_case names. */
            val hits: Set<String>,
            /** Trigram similarity per field, keyed by snake_case field name. */
            val fieldSimilarities: Map<String, Double>,
        )

        fun create(
            firstName: String,
            lastName: String,
            email: String,
            description: String?,
            socialLinks: List<String>,
        ): ClientRow {
            val id = UUID.randomUUID()
            dataSource.connection.use { connection ->
                try {
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO clients (id, first_name, last_name, email, description, social_links)
                            VALUES (?, ?, ?, ?, ?, ?)
                            RETURNING created_at
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, id)
                            statement.setString(2, firstName)
                            statement.setString(3, lastName)
                            statement.setString(4, email)
                            statement.setString(5, description)
                            statement.setArray(6, connection.createArrayOf("text", socialLinks.toTypedArray()))
                            statement.executeQuery().use { rs ->
                                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                                return ClientRow(
                                    id,
                                    firstName,
                                    lastName,
                                    email,
                                    description,
                                    socialLinks,
                                    rs.getObject("created_at", OffsetDateTime::class.java),
                                )
                            }
                        }
                } catch (e: PSQLException) {
                    if (e.sqlState == "23505") throw EmailAlreadyUsedException(email)
                    throw e
                }
            }
        }

        fun findById(id: UUID): ClientRow? {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "SELECT id, first_name, last_name, email, description, social_links, created_at FROM clients WHERE id = ?",
                    ).use { statement ->
                        statement.setObject(1, id)
                        statement.executeQuery().use { rs ->
                            return if (rs.next()) mapClient(rs) else null
                        }
                    }
            }
        }

        /** Matches a token substring in any field, or a trigram similarity in any field. */
        fun search(
            phrase: String,
            tokenRegex: String?,
            minSimilarity: Double,
            limit: Int,
        ): List<ClientSearchRow> {
            // A query made only of punctuation has no tokens; this regex never matches.
            val regex = tokenRegex ?: "a^"
            dataSource.connection.use { connection ->
                connection.prepareStatement(SEARCH_SQL).use { statement ->
                    var i = 0

                    fun bindPhrases() = repeat(4) { statement.setString(++i, phrase) }

                    fun bindRegexes() = repeat(4) { statement.setString(++i, regex) }

                    bindPhrases() // select: per-field similarity
                    bindRegexes() // select: substring hit flags
                    bindRegexes() // where: substring hits
                    repeat(4) {
                        statement.setString(++i, phrase)
                        statement.setDouble(++i, minSimilarity)
                    } // where: trigram similarity
                    bindPhrases() // order: best similarity
                    statement.setInt(++i, limit)

                    statement.executeQuery().use { rs ->
                        val rows = mutableListOf<ClientSearchRow>()
                        while (rs.next()) {
                            val similarities =
                                mapOf(
                                    "email" to rs.getDouble("email_sim"),
                                    "first_name" to rs.getDouble("first_name_sim"),
                                    "last_name" to rs.getDouble("last_name_sim"),
                                    "description" to rs.getDouble("description_sim"),
                                )
                            val hits =
                                buildSet {
                                    if (rs.getBoolean("email_hit")) add("email")
                                    if (rs.getBoolean("first_name_hit")) add("first_name")
                                    if (rs.getBoolean("last_name_hit")) add("last_name")
                                    if (rs.getBoolean("description_hit")) add("description")
                                }
                            rows += ClientSearchRow(mapClient(rs), similarities.values.max(), hits, similarities)
                        }
                        return rows
                    }
                }
            }
        }

        private fun mapClient(rs: ResultSet): ClientRow =
            ClientRow(
                id = rs.getObject("id") as UUID,
                firstName = rs.getString("first_name"),
                lastName = rs.getString("last_name"),
                email = rs.getString("email"),
                description = rs.getString("description"),
                socialLinks = (rs.getArray("social_links").array as Array<*>).map { it.toString() },
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            )

        private companion object {
            /**
             * Fields: email, first_name, last_name, description. `description` is
             * coalesced because trigram functions are strict about NULLs.
             */
            val SEARCH_SQL =
                """
                SELECT id, first_name, last_name, email, description, social_links, created_at,
                       similarity(lower(email), ?) AS email_sim,
                       similarity(lower(first_name), ?) AS first_name_sim,
                       similarity(lower(last_name), ?) AS last_name_sim,
                       similarity(lower(coalesce(description, '')), ?) AS description_sim,
                       (lower(email) ~ ?) AS email_hit,
                       (lower(first_name) ~ ?) AS first_name_hit,
                       (lower(last_name) ~ ?) AS last_name_hit,
                       (lower(coalesce(description, '')) ~ ?) AS description_hit
                FROM clients
                WHERE lower(email) ~ ?
                   OR lower(first_name) ~ ?
                   OR lower(last_name) ~ ?
                   OR lower(coalesce(description, '')) ~ ?
                   OR similarity(lower(email), ?) >= ?
                   OR similarity(lower(first_name), ?) >= ?
                   OR similarity(lower(last_name), ?) >= ?
                   OR similarity(lower(coalesce(description, '')), ?) >= ?
                ORDER BY GREATEST(
                           similarity(lower(email), ?),
                           similarity(lower(first_name), ?),
                           similarity(lower(last_name), ?),
                           similarity(lower(coalesce(description, '')), ?)
                       ) DESC
                LIMIT ?
                """.trimIndent()
        }
    }
