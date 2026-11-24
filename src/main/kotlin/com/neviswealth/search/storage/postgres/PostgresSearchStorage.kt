package com.neviswealth.search.storage.postgres

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.neviswealth.search.domain.client.Client
import com.neviswealth.search.domain.document.DocumentMeta
import com.neviswealth.search.domain.query.SearchStorage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Search adapter. Every /search request runs ONE prepared statement with
 * bounded branches: clients (substring regex + trigram `%`), lexical document
 * candidates, and vector candidates read from the HNSW index
 * (`ORDER BY embedding <=> ? LIMIT n`) before per-document dedup. Branch rows
 * travel as jsonb payloads; merging/ranking happens in the domain scenario.
 *
 * Trigram predicates use the `%` operator with the session threshold set to
 * `search.lexical.min-similarity` (applied on connection init), so the
 * expression GIN indexes from V2__search_indexes.sql can serve the filters.
 * Each branch reads at most `search.lexical.candidate-limit` matching rows
 * before ranking (early cutoff): for very common terms the ranked top-N is a
 * sample of the match set, for selective terms it is the exact set.
 */
@ApplicationScoped
class PostgresSearchStorage
    @Inject
    constructor(
        private val dataSource: DataSource,
        private val objectMapper: ObjectMapper,
    ) : SearchStorage {
        override fun findCandidates(criteria: SearchStorage.CandidateCriteria): SearchStorage.CandidateSet {
            dataSource.connection.use { connection ->
                val sql =
                    if (criteria.queryVector != null) {
                        SEARCH_WITH_VECTOR_SQL
                    } else {
                        // Embedding unavailable: same statement shape, no vector branch.
                        SEARCH_LEXICAL_ONLY_SQL
                    }
                val clients = mutableListOf<SearchStorage.ClientMatch>()
                val lexical = mutableListOf<SearchStorage.DocumentCandidate>()
                val semantic = mutableListOf<SearchStorage.DocumentCandidate>()

                connection.prepareStatement(sql).use { statement ->
                    // Repeated executions would switch to a server-side prepared
                    // statement with a generic plan: a parameterised LIMIT hides
                    // n from the planner, which then picks a sequential scan over
                    // the HNSW index (EXPLAIN (GENERIC_PLAN) reproduces it). Opt
                    // this statement out of the prepare threshold only — every
                    // other query keeps the default.
                    runCatching {
                        statement.unwrap(org.postgresql.PGStatement::class.java).prepareThreshold = 0
                    }
                    bindCriteria(statement, criteria)
                    statement.executeQuery().use { rs ->
                        while (rs.next()) {
                            val payload = objectMapper.readTree(rs.getString("payload"))
                            when (rs.getString("kind")) {
                                "client" -> if (criteria.wantClients) clients += clientMatch(payload)
                                "lexical" -> if (criteria.wantDocuments) lexical += lexicalCandidate(payload, criteria)
                                "semantic" -> if (criteria.wantDocuments) semantic += semanticCandidate(payload)
                            }
                        }
                    }
                }
                return SearchStorage.CandidateSet(clients, lexical, semantic)
            }
        }

        /** Binds parameters in SQL order; the branches are skipped with zero limits. */
        private fun bindCriteria(
            statement: PreparedStatement,
            criteria: SearchStorage.CandidateCriteria,
        ) {
            var i = 0
            val phrase = criteria.phrase
            val regex = posixRegexOr(criteria.tokens)
            val clientCandidateLimit = if (criteria.wantClients) criteria.candidateLimit else 0
            val clientLimit = if (criteria.wantClients) criteria.limit else 0
            val documentCandidateLimit = if (criteria.wantDocuments) criteria.candidateLimit else 0
            val documentLimit = if (criteria.wantDocuments) criteria.limit else 0

            // client branch: 4 sims, 4 hit flags, 4 ~ predicates (regex), 4 % predicates
            // (phrase: the operator compares trigram similarity against the query phrase),
            // candidate LIMIT, LIMIT
            repeat(4) { statement.setString(++i, phrase) }
            repeat(4) { statement.setString(++i, regex) }
            repeat(4) { statement.setString(++i, regex) }
            repeat(4) { statement.setString(++i, phrase) }
            statement.setInt(++i, clientCandidateLimit)
            statement.setInt(++i, clientLimit)

            // lexical branch: 2 sims, 2 hit flags, 2 ~ predicates (regex), 2 % predicates
            // (phrase), candidate LIMIT, LIMIT
            repeat(2) { statement.setString(++i, phrase) }
            repeat(2) { statement.setString(++i, regex) }
            repeat(2) { statement.setString(++i, regex) }
            repeat(2) { statement.setString(++i, phrase) }
            statement.setInt(++i, documentCandidateLimit)
            statement.setInt(++i, documentLimit)

            if (criteria.queryVector != null) {
                // vector branch: distance + order vector, HNSW candidate bound, score threshold, LIMIT
                val vector = PgJdbc.pgVector(criteria.queryVector)
                statement.setObject(++i, vector)
                statement.setObject(++i, vector)
                statement.setInt(++i, criteria.chunkCandidates)
                statement.setDouble(++i, criteria.minScore)
                statement.setInt(++i, criteria.limit)
            }
        }

        private fun clientMatch(payload: JsonNode): SearchStorage.ClientMatch {
            val similarities =
                mapOf(
                    "email" to payload["sim_email"].asDouble(),
                    "first_name" to payload["sim_first_name"].asDouble(),
                    "last_name" to payload["sim_last_name"].asDouble(),
                    "description" to payload["sim_description"].asDouble(),
                )
            val hits =
                buildSet {
                    if (payload["hit_email"].asBoolean()) add("email")
                    if (payload["hit_first_name"].asBoolean()) add("first_name")
                    if (payload["hit_last_name"].asBoolean()) add("last_name")
                    if (payload["hit_description"].asBoolean()) add("description")
                }
            val client =
                Client(
                    id = UUID.fromString(payload["id"].asText()),
                    firstName = payload["first_name"].asText(),
                    lastName = payload["last_name"].asText(),
                    email = payload["email"].asText(),
                    description = payload["description"].takeUnless { it.isNull }?.asText(),
                    socialLinks = payload["social_links"].map { it.asText() },
                    createdAt = OffsetDateTime.parse(payload["created_at"].asText()),
                )
            return SearchStorage.ClientMatch(
                client = client,
                similarity = similarities.values.max(),
                hits = hits,
                fieldSimilarities = similarities,
            )
        }

        private fun lexicalCandidate(
            payload: JsonNode,
            criteria: SearchStorage.CandidateCriteria,
        ): SearchStorage.DocumentCandidate {
            val similarities =
                mapOf(
                    "title" to payload["sim_title"].asDouble(),
                    "content" to payload["sim_content"].asDouble(),
                )
            val hits =
                buildSet {
                    if (payload["hit_title"].asBoolean()) add("title")
                    if (payload["hit_content"].asBoolean()) add("content")
                }
            val content = payload["content"].asText()
            return SearchStorage.DocumentCandidate(
                document = documentMeta(payload),
                score = if (hits.isNotEmpty()) 1.0 else similarities.values.max(),
                matched = hits.ifEmpty { similarities.filterValues { it >= criteria.minSimilarity }.keys },
                snippet = snippetAround(content, posixRegexOr(criteria.tokens)),
                content = null,
            )
        }

        private fun semanticCandidate(payload: JsonNode): SearchStorage.DocumentCandidate =
            SearchStorage.DocumentCandidate(
                document = documentMeta(payload),
                score = payload["score"].asDouble(),
                matched = setOf("content"),
                snippet = payload["snippet"].asText(),
                content = null,
            )

        private fun documentMeta(payload: JsonNode): DocumentMeta =
            DocumentMeta(
                id = UUID.fromString(payload["id"].asText()),
                clientId = UUID.fromString(payload["client_id"].asText()),
                title = payload["title"].asText(),
                summary = payload["summary"].takeUnless { it.isNull }?.asText(),
                indexStatus = payload["index_status"].asText(),
                createdAt = OffsetDateTime.parse(payload["created_at"].asText()),
            )

        private fun snippetAround(
            content: String,
            regex: String,
        ): String {
            val window = 160
            // Match on the original string with IGNORE_CASE: lowercasing first
            // can change string length (e.g. 'İ'), which would shift the slice
            // indices out of range.
            val match = Regex(regex, RegexOption.IGNORE_CASE).find(content)
            if (match == null) return content.take(window).replace(Regex("\\s+"), " ")
            val start = maxOf(0, match.range.first - 40)
            val end = minOf(content.length, match.range.last + window)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < content.length) "…" else ""
            return (prefix + content.substring(start, end) + suffix).replace(Regex("\\s+"), " ")
        }

        private companion object {
            /** Postgres POSIX regex that never matches: a query without tokens matches nothing. */
            private const val NEVER_MATCHES = "a^"

            // Postgres uses POSIX-ish regex, which does not understand \Q...\E
            // from java.util.regex. Tokens are alphanumeric by construction; the
            // escaping is a safety net for anything regex-special.
            private val regexSpecial = Regex("[.^$*+?()\\[\\]{}|\\\\]")

            fun posixRegexOr(tokens: List<String>): String =
                if (tokens.isEmpty()) {
                    NEVER_MATCHES
                } else {
                    tokens.joinToString("|") { token -> regexSpecial.replace(token) { "\\${it.value}" } }
                }

            private fun branches(extra: String): String =
                """
                WITH
                client_branch AS (
                    SELECT 'client'::text AS kind, to_jsonb(t) AS payload
                    FROM (
                        SELECT *
                        FROM (
                            SELECT c.id, c.first_name, c.last_name, c.email, c.description, c.social_links, c.created_at,
                                   similarity(lower(c.email), ?) AS sim_email,
                                   similarity(lower(c.first_name), ?) AS sim_first_name,
                                   similarity(lower(c.last_name), ?) AS sim_last_name,
                                   similarity(lower(coalesce(c.description, '')), ?) AS sim_description,
                                   (lower(c.email) ~ ?) AS hit_email,
                                   (lower(c.first_name) ~ ?) AS hit_first_name,
                                   (lower(c.last_name) ~ ?) AS hit_last_name,
                                   (lower(coalesce(c.description, '')) ~ ?) AS hit_description
                            FROM clients c
                            WHERE lower(c.email) ~ ?
                               OR lower(c.first_name) ~ ?
                               OR lower(c.last_name) ~ ?
                               OR lower(coalesce(c.description, '')) ~ ?
                               OR lower(c.email) % ?
                               OR lower(c.first_name) % ?
                               OR lower(c.last_name) % ?
                               OR lower(coalesce(c.description, '')) % ?
                            LIMIT ?
                        ) capped
                        ORDER BY GREATEST(sim_email, sim_first_name, sim_last_name, sim_description) DESC
                        LIMIT ?
                    ) t
                ),
                lexical_branch AS (
                    SELECT 'lexical'::text AS kind, to_jsonb(u) AS payload
                    FROM (
                        SELECT *
                        FROM (
                            SELECT d.id, d.client_id, d.title, d.summary, d.index_status, d.created_at, d.content,
                                   similarity(lower(d.title), ?) AS sim_title,
                                   similarity(lower(d.content), ?) AS sim_content,
                                   (lower(d.title) ~ ?) AS hit_title,
                                   (lower(d.content) ~ ?) AS hit_content
                            FROM documents d
                            WHERE lower(d.title) ~ ?
                               OR lower(d.content) ~ ?
                               OR lower(d.title) % ?
                               OR lower(d.content) % ?
                            LIMIT ?
                        ) capped
                        ORDER BY GREATEST(sim_title, sim_content) DESC
                        LIMIT ?
                    ) u
                )$extra
                """.trimIndent()

            private const val VECTOR_BRANCH =
                """,
                vector_chunks AS (
                    SELECT c.document_id, c.content, c.score
                    FROM (
                        SELECT dc.document_id, dc.content,
                               1 - (dc.embedding <=> ?::vector) AS score
                        FROM document_chunks dc
                        ORDER BY dc.embedding <=> ?::vector
                        LIMIT ?
                    ) c
                    WHERE c.score >= ?
                ),
                semantic_branch AS (
                    SELECT 'semantic'::text AS kind, to_jsonb(v) AS payload
                    FROM (
                        SELECT d.id, d.client_id, d.title, d.summary, d.index_status, d.created_at,
                               MAX(v.score) AS score,
                               (ARRAY_AGG(v.content ORDER BY v.score DESC))[1] AS snippet
                        FROM vector_chunks v
                        JOIN documents d ON d.id = v.document_id
                        GROUP BY d.id, d.client_id, d.title, d.summary, d.index_status, d.created_at
                        ORDER BY MAX(v.score) DESC
                        LIMIT ?
                    ) v
                )"""

            private const val UNION =
                """
                SELECT kind, payload FROM client_branch
                UNION ALL SELECT kind, payload FROM lexical_branch
                """

            val SEARCH_WITH_VECTOR_SQL =
                branches(VECTOR_BRANCH) + "\n" + UNION + "\nUNION ALL SELECT kind, payload FROM semantic_branch"

            val SEARCH_LEXICAL_ONLY_SQL = branches("") + "\n" + UNION
        }
    }
