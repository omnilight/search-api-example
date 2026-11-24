# Nevis Search API

A Kotlin and Quarkus REST API for searching clients and their documents.

Request and response types are defined under `components.schemas` in the
[OpenAPI contract](src/main/resources/META-INF/openapi.yaml). The [generated DTOs](src/generated/kotlin/com/neviswealth/search/generated/model) are available for review in the source tree.
The [Kotlin mappers](src/main/kotlin/com/neviswealth/search/entrypoint/api/DtoMappers.kt) show how they map to the domain models.

## How it works

The REST API (`entrypoint/api`) and scheduled indexer (`entrypoint/worker`) call the use cases in `domain`. The `storage/postgres` adapters store clients, documents and search vectors. The `integration/ollama` adapter generates embeddings and document summaries.

Text search can find a document as soon as it is saved. The worker indexes its chunks in the background, and `/search` combines text and vector matches as the index becomes available.

## Run with Docker

Requires Docker Compose and Make.

```bash
make up
```

Compose builds the native API image and starts PostgreSQL and Ollama. The `ollama-init` job downloads the embedding and chat models if needed. The API then applies the Flyway migrations, starts the indexing worker and listens on port 8080.

Open [Swagger UI](http://localhost:8080/q/swagger-ui/) to try the API.

Use `make down` to stop the stack or `make purge` to remove its containers, volumes and locally built image. Purging deletes the database and downloaded models.

## Develop locally

```bash
mise install
make dev
```

`make dev` starts PostgreSQL and Ollama in Compose and initializes the models. Quarkus then runs on the host with hot reload. Attach your IDE debugger to `localhost:5005`. Stop the host process with Ctrl+C and use `make down` to stop the containers.

## Try the demo

```bash
make demo
```

The demo seeds sample data and prints example search responses. `NevisWealth` finds `john.doe@neviswealth.com`, and `address proof` finds the utility bill.

## Check changes

```bash
make lint
make test
```

Use `make fmt` to format Kotlin. Tests start a temporary PostgreSQL container and use a fake LLM. Run `make help` for all commands.

After editing the OpenAPI contract, run `mise exec -- ./gradlew syncApiModels`. Gradle checks for stale DTOs during `build`.
