# Nevis Search API

Search across clients and their documents. The service uses Kotlin, Quarkus, PostgreSQL with `pg_trgm` and `pgvector`, and Ollama. Docker Compose runs the local stack.

## API

| Endpoint | Purpose |
| --- | --- |
| `POST /clients` | Create a client |
| `GET /clients/{id}` | Fetch a client |
| `POST /clients/{client_id}/documents` | Attach a document to a client |
| `GET /clients/{client_id}/documents` | List a client's documents with cursor pagination |
| `GET /clients/{client_id}/documents/{document_id}` | Fetch a document with its content and summary |
| `GET /search?q=...` | Search clients by email, name or description and documents by text or meaning |

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

On a fresh stack, seed sample clients and documents and wait for indexing.

```bash
make demo
```

The responses below show the matching hit with selected fields and rounded scores.

```bash
curl 'http://localhost:8080/search?q=NevisWealth'
```

```json
[{"type":"client","score":1.0,"client":{"email":"john.doe@neviswealth.com"}}]
```

```bash
curl 'http://localhost:8080/search?q=address%20proof'
```

```json
[{"type":"document","score":0.533,"document":{"title":"Utility bill for 42 Baker Street"}}]
```

## Check changes

```bash
make lint
make test
```

Use `make fmt` to format Kotlin. Tests start a temporary PostgreSQL container and use a fake LLM. Run `make help` for all commands.

After editing the [OpenAPI contract](src/main/resources/META-INF/openapi.yaml), run `mise exec -- ./gradlew syncApiModels`. Gradle checks for stale DTOs during `build`.
