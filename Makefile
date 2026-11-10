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

.PHONY: help up up-observability down logs ps smoke e2e test test-agent test-api test-web \
	lint lint-contract lint-config lint-agent lint-api lint-web \
	check-iac check-terraform check-helm check-observability check-dockerfiles \
	k8s-up k8s-smoke k8s-down fmt fmt-agent fmt-api fmt-web clean

help: ## Show this help
	@printf 'Usage: make <target>\n'
	@awk 'BEGIN { FS = ":.*## " } \
		/^##@ / { printf "\n%s\n", substr($$0, 5); next } \
		/^[a-z][a-z0-9-]*:.*## / { printf "  %-20s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\nWindows: run these from Git Bash, or run the docker compose commands directly.\n'

##@ Local stack

up: ## Build and start the stack, waiting until every service is healthy
	$(COMPOSE) up --detach --build --wait

up-observability: ## Same as up, plus Prometheus (:39090), Alertmanager (:39093) and Grafana (:33000)
	$(COMPOSE) --profile observability up --detach --build --wait

# The profile is named so that down also stops the observability services when they run.
down: ## Stop the stack (observability included) and keep its data volumes
	$(COMPOSE) --profile observability down

logs: ## Follow logs (narrow with SERVICE=api)
	$(COMPOSE) logs --follow $(SERVICE)

ps: ## Show service status and health
	$(COMPOSE) ps --all

smoke: ## Run the end-to-end smoke test, auth included, against the running stack (needs curl and jq)
	bash scripts/smoke.sh

e2e: ## Browser tests (Playwright, Chromium) against the running stack: sign-in, roles, decommission
	cd e2e && npm ci && npx playwright install chromium && npm test

##@ Kubernetes (k3d cluster "driftwatch"; needs k3d, kubectl and helm)

k8s-up: ## Create the cluster, build and import images, install both charts
	bash deploy/k3d/up.sh

k8s-smoke: ## Smoke test through the ingress at http://localhost:38080
	bash deploy/k3d/smoke.sh

k8s-down: ## Delete the cluster
	bash deploy/k3d/down.sh

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
	docker run --rm --volume "$(CURDIR):/mnt" $(SHELLCHECK_IMAGE) scripts/*.sh scripts/lib/*.sh deploy/k3d/*.sh
	$(COMPOSE) config --quiet
	$(COMPOSE) --profile observability config --quiet

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

##@ Infrastructure checks (only Docker needed; tool images pinned in scripts/lib/tools.sh)

check-iac: check-terraform check-helm check-observability check-dockerfiles ## Run every infrastructure check

check-terraform: ## terraform fmt/validate/test, tflint and Trivy for infra/terraform (never plans)
	bash scripts/check-terraform.sh

check-helm: ## helm lint/unittest, kubeconform and Trivy for both charts
	bash scripts/check-helm.sh

check-observability: ## promtool config/rules/tests, amtool and dashboard JSON (needs jq)
	bash scripts/check-observability.sh

check-dockerfiles: ## Trivy misconfiguration scan of every Dockerfile
	bash scripts/trivy.sh config --exit-code 1 --severity HIGH,CRITICAL \
		--misconfig-scanners dockerfile --skip-dirs web/node_modules --skip-dirs '**/.terraform' .

##@ Cleanup

clean: ## Remove the stack, its volumes and locally built images, and build outputs
	$(COMPOSE) --profile observability down --volumes --remove-orphans --rmi local
	rm -rf agent/coverage.out agent/driftwatch-agent agent/driftwatch-agent.exe \
		api/target web/dist web/coverage
