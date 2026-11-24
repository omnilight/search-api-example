# AGENTS.md

- Use the commands in [README.md](README.md). Run `make fmt`, `make lint`, and `make test` for Kotlin changes. The test target configures Testcontainers for the active Docker context.
- Keep HTTP and scheduling in `entrypoint`, use cases and ports in `domain`, PostgreSQL access in `storage`, and Ollama access in `integration`.
- Edit the [OpenAPI contract](src/main/resources/META-INF/openapi.yaml) for API changes. Gradle generates the DTOs used at the HTTP boundary.
- Manage schema changes through [Flyway migrations](src/main/resources/db/migration/). When changing the embedding model, check the vector dimension and reindex existing documents.
