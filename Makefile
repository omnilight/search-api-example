.DEFAULT_GOAL := help

.PHONY: help up down demo logs dev test lint fmt native psql clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

up: ## Build the native image and start the full stack (Postgres + Ollama + API)
	docker compose up -d --build

demo: ## Start the stack and seed demo data, then print example searches
	docker compose --profile demo up -d --build
	@docker compose logs -f seed 2>/dev/null || true

down: ## Stop the stack and remove containers
	docker compose down

logs: ## Follow API logs
	docker compose logs -f app

dev: ## Run the API locally in JVM dev mode (Dev Services starts Postgres; Ollama via compose)
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
