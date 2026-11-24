.DEFAULT_GOAL := help

.PHONY: help up down purge demo logs dev test lint fmt native psql clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

up: ## Build the native image and start the full stack (Postgres + Ollama + API)
	docker compose up -d --build

demo: ## Start the stack and seed demo data, then print example searches
	docker compose --profile demo up -d --build
	@docker compose logs -f seed 2>/dev/null || true

down: ## Stop the stack and remove containers
	docker compose down

purge: ## Remove the Compose stack, local images and volumes (database and models)
	docker compose --profile '*' down --volumes --remove-orphans --rmi local

logs: ## Follow API logs
	docker compose logs -f app

dev: ## Run the API on the host with hot reload against Compose Postgres + Ollama
	docker compose up -d --wait postgres ollama
	docker compose up --abort-on-container-exit --exit-code-from ollama-init ollama-init
	QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/nevis \
	OLLAMA_URL=http://localhost:11434 \
	mise exec -- ./gradlew quarkusDev

test: ## Run tests (starts a throwaway pgvector container via Dev Services)
	DOCKER_HOST="$$(docker context inspect --format '{{.Endpoints.docker.Host}}')" \
	TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
	mise exec -- ./gradlew test

lint: ## Check Kotlin code style (ktlint)
	mise exec -- ./gradlew ktlintCheck

fmt: ## Auto-format Kotlin code (ktlint)
	mise exec -- ./gradlew ktlintFormat

native: ## Build the native binary in a Mandrel container without running it
	docker build -t nevis-search-native .

psql: ## Open psql inside the Postgres container
	docker compose exec postgres psql -U nevis -d nevis

clean: ## Remove build outputs
	mise exec -- ./gradlew clean
