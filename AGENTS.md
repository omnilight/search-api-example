# AGENTS.md — working conventions for this repository

Nevis home assignment: a Kotlin/Quarkus service exposing a Search API across
clients and their documents. Lexical search runs on pg_trgm, semantic search
on pgvector + a local Ollama model. Single native binary via GraalVM.

## Toolchain

- JDK 25 is pinned in `.mise.toml` (mise) — it matches the Mandrel 25
  builder image required by Quarkus 3.40 for native builds. All gradle
  commands run through `mise exec -- ./gradlew ...`.
- No local GraalVM needed: the native image is built inside Docker
  (`quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25`), see `Dockerfile`.
- Versions are pinned in `build.gradle.kts`: Kotlin 2.4.21, Quarkus platform
  3.40.1.2. Bump together, keep in sync with `gradle/wrapper` distribution.
- Docker here is a lima VM, not Docker Desktop: tests need
  `DOCKER_HOST` (the Mac-side socket from `docker context inspect`) plus
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (the socket as
  seen inside the VM), otherwise the testcontainers ryuk container fails to
  mount the socket and Dev Services dies with a daemon 500. Both are wired
  into the `make test` target — always run tests through it.

## Commands

- `make test` — full test suite (unit + integration). Integration tests start
  a throwaway `pgvector/pgvector:pg17` container via Quarkus Dev Services;
  Docker must be running. The LLM is faked via a CDI alternative, no Ollama
  needed for tests.
- `make lint` / `make fmt` — ktlint check / auto-format (one tool for both;
  wired into `check`, so `./gradlew build` and the Docker image build fail on
  style violations).
- `make up` — native build in Docker + full stack via compose.
- `make demo` — same + demo data and example searches (`--profile demo`).
- `make dev` — JVM dev mode against local Postgres (:5432) and Ollama (:11434).
- `make openapi` — export `docs/openapi.yaml` from a running instance.
  Re-export and commit after any API change.

## Layout

- `api/` — JAX-RS resources + exception mappers. Wire fields are snake_case
  because the contract in `src/main/resources/META-INF/openapi.yaml` declares
  them so; DTOs under `com.neviswealth.search.generated.model` are generated
  from that spec at build time (`build/generated`). Never edit generated
  code — change the spec and re-run the build.
- Spec-first rules: `META-INF/openapi.yaml` is the single source of truth;
  Quarkus serves it verbatim (`mp.openapi.scan.disable=true`), so do not add
  OpenAPI annotations to resources. Validation lives in the spec via
  `x-field-extra-annotation` (Kotlin `@field:jakarta.validation…` targets);
  required properties become non-null Kotlin parameters with Jackson
  `nulls=FAIL`, optional ones become nullable with `null` defaults. The
  generator is pinned: OpenAPI Generator 7.27.0, `kotlin` +
  `jvm-microprofile` + `serializationLibrary=jackson` + `useJakartaEe` — the
  combination that keeps `UUID`, `OffsetDateTime`, nullability and
  snake_case-in-JSON/camelCase-in-Kotlin intact. Models only: the
  `org/openapitools` scaffolding is deleted after generation.
- `db/` — repositories, plain JDBC, no ORM. SQL lives here. The schema is
  `src/main/resources/db/migration/V1__init.sql` (Flyway) — the single source
  of truth; there are no compose-side init scripts.
- `text/` — query tokenizer (camelCase → tokens) and content chunker.
- `llm/` — `LlmClient` interface + Ollama implementation (REST client,
  configKey `ollama-api`).
- `worker/` — background index worker: claims pending documents, embeds
  chunks, writes summaries.
- `service/` — search orchestration: merges lexical and semantic document
  results, ranks the unified response.

## Conventions and pitfalls

- `ClientRepository.SEARCH_SQL` binds parameters positionally
  (`bindPhrases()`/`bindRegexes()` helpers mirror the field list
  email/first_name/last_name/description ×4). If you add a field, update the
  SQL and the bind helpers together — parameter counts must stay in sync.
- Embedding dimension is tied to the model: `document_chunks.embedding` is
  `vector(768)` because default `nomic-embed-text:v1.5` outputs 768 dims.
  Nomic wants the `search_query:`/`search_document:` prefixes — they live in
  `ollama.embedding-query-prefix` / `ollama.embedding-document-prefix` and are
  applied by `OllamaLlmClient`; keep the interface split (`embedQuery` vs
  `embedDocuments`) so a prefix never lands on the wrong side. Switching the
  embedding model requires a schema change, a full reindex and threshold
  recalibration; document this wherever you change it.
- Worker status machine: `pending → indexing → indexed`, `indexing → pending`
  on transient LLM failure (no attempt counted), `pending → failed` after
  `search.worker.max-attempts` hard failures. Embeddings are committed first
  (`completeIndexing`); the summary is a best-effort follow-up
  (`updateSummary` / `recordSummaryError`) so a broken chat model never
  blocks semantic search. Stuck `indexing` rows are reset to `pending` on
  first scheduler tick after restart.
- Scores are per type and not comparable across types: clients get 1.0 for
  substring hits else pg_trgm similarity; documents get cosine similarity of
  the best chunk (semantic) or the same lexical scheme. Explained in README —
  keep that explanation truthful when changing scoring.
- Errors: RFC 7807 (`application/problem+json`) via `api/ApiExceptionMappers`.
  Validation errors (400) come from Hibernate Validator on the DTOs.
- Tests replace `LlmClient` with `FakeLlmClient` (CDI alternative enabled by
  `FakeLlmTestProfile`); the scheduler is frozen (`search.worker.interval=1h`)
  and tests call `IndexWorker.pollOnce()` directly. Keep it that way:
  deterministic, no sleeping.
- Integration test data must stay isolated: use `System.nanoTime()` in emails
  and query for specific ids instead of asserting global result counts.

## Definition of done for a change

1. `make test` green; `make lint` green (`make fmt` first if needed).
2. API changed → edit `src/main/resources/META-INF/openapi.yaml` only; the
   DTOs regenerate on the next build and `/q/openapi` serves the new contract.
   README examples updated where they quote responses.
3. Schema changed → new Flyway migration (never edit `V1__init.sql` after it
   has shipped; in this repo, while it is still uncommitted, edit V1 and drop
   the dev volume).
4. `make demo` smoke: the two canonical examples from the task
   (`q=NevisWealth` finds `john.doe@neviswealth.com`; `q=address proof` finds
   the utility bill document) still work with the real models.
