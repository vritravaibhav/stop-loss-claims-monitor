# Convenience wrappers. Everything here is a thin alias; `docker compose up --build` alone
# is enough to run the app.

COMPOSE ?= docker compose

.DEFAULT_GOAL := help
.PHONY: help up down reset ps logs load-sample e2e test test-api test-app

help: ## List targets
	@grep -E '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

up: ## Build and start the stack, wait until healthy (http://localhost:8081)
	$(COMPOSE) up -d --build --wait
	@echo "web http://localhost:8081  api http://localhost:8080  emulator UI http://localhost:4000"

down: ## Stop the stack (keeps the database volume)
	$(COMPOSE) down

reset: ## Stop the stack and delete the database volume
	$(COMPOSE) down -v

ps: ## Show service status
	$(COMPOSE) ps

logs: ## Follow logs of all services
	$(COMPOSE) logs -f --tail=100

load-sample: ## Load every sample claim file (one-shot api container, same ingest code)
	$(COMPOSE) run --rm api --app.mode=load-sample

e2e: ## End-to-end check against a fresh stack (make reset up e2e)
	python3 tools/e2e_check.py

test: test-api test-app ## Run api and app tests

test-api: ## API build + tests via Maven (Testcontainers starts its own Postgres; needs Docker)
	cd api && ./mvnw -B verify

test-app: ## Flutter analyze + tests on the host
	cd app && flutter pub get && flutter analyze && flutter test
