# rmq-verify developer entry points. Every target is idempotent; run `make help` for the list.

SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

MVN ?= ./mvnw
MVN_FLAGS ?= -B -ntp

.PHONY: help
help: ## List the available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_-]+:.*## / {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: build
build: ## Compile and package every module without running tests
	$(MVN) $(MVN_FLAGS) -DskipTests install

.PHONY: test
test: ## Run the unit and property-based tests
	$(MVN) $(MVN_FLAGS) test

.PHONY: verify
verify: ## Full quality gate: format, lint, tests, coverage, ArchUnit, SpotBugs, mutation
	$(MVN) $(MVN_FLAGS) -Pmutation clean verify

.PHONY: mutation
mutation: ## PIT mutation testing on the model and checker modules
	$(MVN) $(MVN_FLAGS) -Pmutation -pl rmq-verify-model,rmq-verify-checker -am verify

RABBITMQ ?= 4.4
TLS ?= 0
export RABBITMQ TLS

.PHONY: e2e
e2e: build ## End-to-end tests: real cluster via Testcontainers, real faults (needs Docker, cluster down)
	$(MVN) $(MVN_FLAGS) -Pe2e -pl rmq-verify-e2e verify

.PHONY: up
up: ## Start the 3-node cluster + Toxiproxy (RABBITMQ=3.13|4.4, TLS=0|1)
	deploy/scripts/up.sh

.PHONY: down
down: ## Stop the cluster and delete its containers, network and volumes
	deploy/scripts/down.sh

.PHONY: demo
demo: build ## Start the cluster, run a 60 s scenario with faults and open the HTML report
	deploy/scripts/demo.sh

.PHONY: chaos
chaos: build ## One pass of the scenario matrix on the running cluster (REPS=1 DURATION=60)
	deploy/scripts/chaos.sh

.PHONY: bench
bench: build ## Full matrix on RabbitMQ 3.13 and 4.4 (REPS=2), regenerates RESULTS.md
	deploy/scripts/bench.sh

.PHONY: report
report: build ## Regenerate RESULTS.md from the archived runs in results/
	java -jar rmq-verify-cli/target/rmq-verify.jar report --matrix results --results-md RESULTS.md --readme README.md \
	  --commit "$$(git rev-parse --short HEAD)" --images deploy/images.env --host "$$(uname -sm)"

.PHONY: security
security: ## SBOM + OWASP Dependency-Check (Maven), gitleaks and Trivy (Docker)
	$(MVN) $(MVN_FLAGS) -Psecurity -DskipTests install
	deploy/scripts/scan.sh

.PHONY: fmt
fmt: ## Format Java sources and POMs in place
	$(MVN) $(MVN_FLAGS) -pl '!rmq-verify-bom' com.diffplug.spotless:spotless-maven-plugin:apply

.PHONY: clean
clean: ## Remove build outputs
	$(MVN) $(MVN_FLAGS) clean
