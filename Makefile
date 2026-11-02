# Convenience wrappers. Everything here is a thin alias; `docker compose up --build` alone
# is enough to run the app.

COMPOSE ?= docker compose
DB_HOST_PORT ?= 55433
# The compose db publishes on 127.0.0.1:$(DB_HOST_PORT); stoploss_test is created by
# infra/db/init on first start and is safe for the tests to truncate.
TEST_DATABASE_URL ?= postgres://postgres:postgres@localhost:$(DB_HOST_PORT)/stoploss_test

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

load-sample: ## Load every sample claim file through the ingest code
	$(COMPOSE) exec api npm run load-sample

e2e: ## End-to-end check against a fresh stack (make reset up e2e)
	python3 tools/e2e_check.py

test: test-api test-app ## Run api and app tests

test-api: ## API tests on the host against the compose Postgres (stoploss_test)
	$(COMPOSE) up -d --wait db
	cd api && npm ci && npm run build && \
	  DATABASE_URL=$(TEST_DATABASE_URL) npm run migrate && \
	  DATABASE_URL=$(TEST_DATABASE_URL) npm test

test-app: ## Flutter analyze + tests on the host
	cd app && flutter pub get && flutter analyze && flutter test
