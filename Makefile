# Thin wrappers around the commands CI runs. Every target is one or two plain commands,
# so nothing here is required: on Windows without make, run the same docker compose and
# component commands from PowerShell, or run make from Git Bash.

SHELL := bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help
MAKEFLAGS += --no-print-directory

COMPOSE ?= docker compose
REDOCLY_CLI_VERSION ?= 2.65.1
ACTIONLINT_IMAGE ?= rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
SHELLCHECK_IMAGE ?= koalaman/shellcheck:v0.11.0@sha256:61862eba1fcf09a484ebcc6feea46f1782532571a34ed51fedf90dd25f925a8d

.PHONY: help up down logs ps smoke test test-agent test-api test-web \
	lint lint-contract lint-config lint-agent lint-api lint-web \
	fmt fmt-agent fmt-api fmt-web clean

help: ## Show this help
	@printf 'Usage: make <target>\n'
	@awk 'BEGIN { FS = ":.*## " } \
		/^##@ / { printf "\n%s\n", substr($$0, 5); next } \
		/^[a-z][a-z-]*:.*## / { printf "  %-16s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\nWindows: run these from Git Bash, or run the docker compose commands directly.\n'

##@ Local stack

up: ## Build and start the stack, waiting until every service is healthy
	$(COMPOSE) up --detach --build --wait

down: ## Stop the stack and keep its data volumes
	$(COMPOSE) down

logs: ## Follow logs (narrow with SERVICE=api)
	$(COMPOSE) logs --follow $(SERVICE)

ps: ## Show service status and health
	$(COMPOSE) ps --all

smoke: ## Run the end-to-end smoke test against the running stack (needs curl and jq)
	bash scripts/smoke.sh

##@ Tests

test: test-agent test-api test-web ## Run every component's test suite

test-agent: ## Agent unit tests with the race detector and coverage
	cd agent && go test -race -coverprofile=coverage.out ./...

test-api: ## API formatting check, unit and Testcontainers integration tests (needs Docker)
	cd api && ./mvnw -B verify

test-web: ## Dashboard unit tests
	cd web && npm test

##@ Lint and format

lint: lint-contract lint-config lint-agent lint-api lint-web ## Run every linter

lint-contract: ## Lint the OpenAPI contract
	npx --yes @redocly/cli@$(REDOCLY_CLI_VERSION) lint contracts/openapi.yaml

lint-config: ## actionlint the workflows, shellcheck the scripts, validate compose (needs Docker)
	docker run --rm --volume "$(CURDIR):/repo" --workdir /repo $(ACTIONLINT_IMAGE) -color
	docker run --rm --volume "$(CURDIR):/mnt" $(SHELLCHECK_IMAGE) scripts/*.sh
	$(COMPOSE) config --quiet

lint-agent: ## go vet and golangci-lint
	cd agent && go vet ./... && golangci-lint run

lint-api: ## Java formatting check
	cd api && ./mvnw -B spotless:check

lint-web: ## ESLint, Prettier check and typecheck
	cd web && npm run lint && npm run typecheck

fmt: fmt-agent fmt-api fmt-web ## Format all source code in place

fmt-agent: ## gofmt and goimports via golangci-lint (formatters in agent/.golangci.yml)
	cd agent && golangci-lint fmt

fmt-api: ## Apply palantir-java-format via Spotless
	cd api && ./mvnw -B spotless:apply

fmt-web: ## Prettier the dashboard
	cd web && npm run format

##@ Cleanup

clean: ## Remove the stack, its volumes and locally built images, and build outputs
	$(COMPOSE) down --volumes --remove-orphans --rmi local
	rm -rf agent/coverage.out agent/driftwatch-agent agent/driftwatch-agent.exe \
		api/target web/dist web/coverage
