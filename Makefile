# Everyday commands. Run `make` to list them.

# Load .env if it exists, and export its values to the commands below.
-include .env
export

.DEFAULT_GOAL := help

.PHONY: help run build test vet fmt check db-up db-down db-reset

help: ## List the commands
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

run: ## Run the API (reads .env)
	go run ./cmd/api

build: ## Build the API binary into bin/
	go build -ldflags "-X main.version=$$(git rev-parse --short HEAD 2>/dev/null || echo dev)" -o bin/api ./cmd/api

test: ## Run the tests
	go test ./...

vet: ## Catch common mistakes the compiler doesn't
	go vet ./...

fmt: ## Format all Go code
	gofmt -w .

check: fmt vet test ## Format, vet and test: run before committing

db-up: ## Start Postgres in Docker and wait until it's ready
	docker compose up -d --wait db

db-down: ## Stop Postgres (data is kept)
	docker compose down

db-reset: ## Stop Postgres and delete all its data
	docker compose down -v
